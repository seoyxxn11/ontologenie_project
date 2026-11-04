package com.axcore.platform.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository.CheckRunStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, ProposalDecisionCheckRepository.class})
class ProposalDecisionCheckRepositoryTests {
    @Autowired ProposalDecisionCheckRepository repository;
    @Autowired ProposalOptionRepository options;
    @Autowired JdbcClient jdbc;
    private long accountId;
    private long proposalId;

    @BeforeEach
    void prepareCandidate() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id)
                values (:login, '검토자', 'test', (select id from role where code='ADMIN')) returning id
                """)
                        .param("login", "decision-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        proposalId = insertProposal();
    }

    @Test
    void doesNotApproveWithoutAnyCheck() {
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isFalse();
        assertThat(repository.findLatestCheck(proposalId, 1)).isEmpty();
    }

    @Test
    void acceptsASuccessfulCheckWithUnmatchedRows() {
        long run = insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 1);
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void excludesUnsuccessfulRunsEvenIfAResultExists(String status) {
        long run = insertRun(proposalId, 1, "CANDIDATE", status, "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 1);
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isFalse();
        assertThat(repository.findLatestCheck(proposalId, 1))
                .contains(new CheckRunStatus(run, status));
    }

    @Test
    void requiresAResultInAdditionToASuccessfulRun() {
        insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isFalse();
    }

    @Test
    void doesNotUseAPreviousRevisionAfterEditing() {
        long run = insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 1);
        addRevision(proposalId, 2);
        assertThat(repository.hasSuccessfulCheck(proposalId, 2)).isFalse();
        assertThat(repository.findLatestCheck(proposalId, 2)).isEmpty();
    }

    @Test
    void acceptsTheEditedRevisionAfterItsOwnCheckCompletes() {
        addRevision(proposalId, 2);
        long run = insertRun(proposalId, 2, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 2);
        assertThat(repository.hasSuccessfulCheck(proposalId, 2)).isTrue();
    }

    @Test
    void doesNotUseAnotherCandidatesResultInTheSameRun() {
        long other = insertProposal();
        long run = insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, other, 1);
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isFalse();
        assertThat(repository.hasSuccessfulCheck(other, 1)).isFalse();
    }

    @Test
    void requiresTheResultRevisionToMatchTheRequestedRevision() {
        addRevision(proposalId, 2);
        long run = insertRun(proposalId, 2, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 1);
        assertThat(repository.hasSuccessfulCheck(proposalId, 2)).isFalse();
    }

    @Test
    void excludesNonCandidateChecks() {
        long run = insertRun(proposalId, 1, "MANUAL", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(run, proposalId, 1);
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isFalse();
        assertThat(repository.findLatestCheck(proposalId, 1)).isEmpty();
    }

    @Test
    void findsAnEligibleSuccessEvenWhenANewerAttemptFailed() {
        long success = insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T00:00:00Z");
        insertResult(success, proposalId, 1);
        long failed = insertRun(proposalId, 1, "CANDIDATE", "FAILED", "2026-11-03T01:00:00Z");
        assertThat(repository.hasSuccessfulCheck(proposalId, 1)).isTrue();
        assertThat(repository.findLatestCheck(proposalId, 1))
                .contains(new CheckRunStatus(failed, "FAILED"));
    }

    @Test
    void findsTheLatestDiagnosticByRequestedTimeThenId() {
        long recent = insertRun(proposalId, 1, "CANDIDATE", "FAILED", "2026-11-03T02:00:00Z");
        insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-11-03T01:00:00Z");
        assertThat(repository.findLatestCheck(proposalId, 1))
                .contains(new CheckRunStatus(recent, "FAILED"));
        long tie = insertRun(proposalId, 1, "CANDIDATE", "QUEUED", "2026-11-03T02:00:00Z");
        assertThat(repository.findLatestCheck(proposalId, 1))
                .contains(new CheckRunStatus(tie, "QUEUED"));
    }

    @Test
    void restrictsSelectedOptionsToTheSameCandidateAndRevision() {
        long option =
                jdbc.sql(
                                """
                insert into proposal_option(proposal_item_id, revision, option_no, payload)
                values (:id, 1, 1, '{}'::jsonb) returning id
                """)
                        .param("id", proposalId)
                        .query(Long.class)
                        .single();
        long other = insertProposal();
        addRevision(proposalId, 2);
        assertThat(options.existsByIdAndProposalItemIdAndRevision(option, proposalId, 1)).isTrue();
        assertThat(options.existsByIdAndProposalItemIdAndRevision(option, proposalId, 2)).isFalse();
        assertThat(options.existsByIdAndProposalItemIdAndRevision(option, other, 1)).isFalse();
    }

    private long insertProposal() {
        return jdbc.sql(
                        """
                with candidate as (
                    insert into proposal_item(origin, item_kind, payload, created_by)
                    values ('MANUAL', 'LINK_RULE', '{}'::jsonb, :account) returning *
                )
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, created_by)
                select id, revision, payload, evidence, created_by from candidate returning proposal_item_id
                """)
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    private void addRevision(long id, int revision) {
        jdbc.sql(
                        """
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, created_by)
                select id, :revision, payload, evidence, created_by from proposal_item where id=:id
                """)
                .param("id", id)
                .param("revision", revision)
                .update();
        jdbc.sql("update proposal_item set revision=:revision where id=:id")
                .param("id", id)
                .param("revision", revision)
                .update();
    }

    private long insertRun(
            long id, int revision, String trigger, String status, String requestedAt) {
        return jdbc.sql(
                        """
                insert into check_run(trigger_kind, status, scope, work_key, requested_by,
                    requested_at, started_at, finished_at, error_message)
                values (:trigger, :status,
                    jsonb_build_object('proposalItems', jsonb_build_array(jsonb_build_object(
                        'proposalItemId', cast(:id as bigint), 'revision', cast(:revision as integer)))),
                    :key, :account, cast(:time as timestamptz),
                    case when :status <> 'QUEUED' then cast(:time as timestamptz) end,
                    case when :status in ('SUCCEEDED', 'FAILED') then cast(:time as timestamptz) end,
                    case when :status = 'FAILED' then '검사 실행 실패' end) returning id
                """)
                .param("id", id)
                .param("revision", revision)
                .param("trigger", trigger)
                .param("status", status)
                .param("key", "decision-" + UUID.randomUUID())
                .param("account", accountId)
                .param("time", requestedAt)
                .query(Long.class)
                .single();
    }

    private void insertResult(long run, long id, int revision) {
        jdbc.sql(
                        """
                insert into check_result(check_run_id, proposal_item_id, proposal_item_revision,
                    total_count, matched_count, unmatched_count)
                values (:run, :id, :revision, 10, 9, 1)
                """)
                .param("run", run)
                .param("id", id)
                .param("revision", revision)
                .update();
    }
}

package com.axcore.platform.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.review.dto.LatestCheckResponse;
import java.math.BigDecimal;
import java.util.Map;
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
@Import({TestcontainersConfiguration.class, ProposalLatestCheckRepository.class})
class ProposalLatestCheckRepositoryTests {

    @Autowired ProposalLatestCheckRepository repository;

    @Autowired JdbcClient jdbcClient;

    private long accountId;

    @BeforeEach
    void prepareAccount() {
        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (
                                    login_id, display_name, password_hash, role_id
                                )
                                values (
                                    :loginId, '테스트 사용자', 'test-hash',
                                    (select id from role where code = 'ADMIN')
                                )
                                returning id
                                """)
                        .param("loginId", "latest-check-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void returnsEmptyWhenNoCheckExists() {
        long id = insertProposal();

        assertThat(repository.findLatestByProposalRevisions(Map.of(id, 1))).isEmpty();
    }

    @Test
    void returnsSucceededCountsAndRoundedRate() {
        long id = insertProposal();

        long runId = insertRun(scope(id, 1), "CANDIDATE", "SUCCEEDED", "2026-10-29T01:00:00Z");

        insertResult(runId, id, 1, 2, 3);

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(runId);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.matchRate()).isEqualByComparingTo(new BigDecimal("0.6667"));
    }

    @Test
    void returnsNullRateWhenTotalCountIsZero() {
        long id = insertProposal();

        long runId = insertRun(scope(id, 1), "CANDIDATE", "SUCCEEDED", "2026-10-29T01:00:00Z");

        insertResult(runId, id, 1, 0, 0);

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.matchedCount()).isZero();
        assertThat(result.totalCount()).isZero();
        assertThat(result.matchRate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void hidesCountsForUnsuccessfulRunEvenIfResultExists(String status) {
        long id = insertProposal();

        long runId = insertRun(scope(id, 1), "CANDIDATE", status, "2026-10-29T01:00:00Z");

        insertResult(runId, id, 1, 2, 3);

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(runId);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.matchedCount()).isNull();
        assertThat(result.totalCount()).isNull();
        assertThat(result.matchRate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void findsRunWithoutAnyResult(String status) {
        long id = insertProposal();

        long runId = insertRun(scope(id, 1), "CANDIDATE", status, "2026-10-29T01:00:00Z");

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(runId);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.matchedCount()).isNull();
        assertThat(result.totalCount()).isNull();
        assertThat(result.matchRate()).isNull();
    }

    @Test
    void excludesOtherRevisionAndOtherTriggerKind() {
        long id = insertProposal();
        addRevision(id, 2);

        insertRun(scope(id, 1), "CANDIDATE", "SUCCEEDED", "2026-10-29T01:00:00Z");

        insertRun(scope(id, 2), "MANUAL", "SUCCEEDED", "2026-10-29T02:00:00Z");

        assertThat(repository.findLatestByProposalRevisions(Map.of(id, 2))).isEmpty();
    }

    @Test
    void usesRequestedAtBeforeIdAndDoesNotFallBackToOlderSuccess() {
        long id = insertProposal();

        long latestId = insertRun(scope(id, 1), "CANDIDATE", "FAILED", "2026-10-29T02:00:00Z");

        long olderId = insertRun(scope(id, 1), "CANDIDATE", "SUCCEEDED", "2026-10-29T01:00:00Z");

        insertResult(olderId, id, 1, 3, 3);

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(latestId);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.matchRate()).isNull();
    }

    @Test
    void usesHigherIdWhenRequestedAtIsEqual() {
        long id = insertProposal();

        insertRun(scope(id, 1), "CANDIDATE", "FAILED", "2026-10-29T01:00:00Z");

        long retryId = insertRun(scope(id, 1), "CANDIDATE", "QUEUED", "2026-10-29T01:00:00Z");

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(retryId);
    }

    @Test
    void returnsEachProposalsResultFromOneRun() {
        long firstId = insertProposal();
        long secondId = insertProposal();

        String targetScope =
                """
                {
                    "proposalItems": [
                        {"proposalItemId": %d, "revision": 1},
                        {"proposalItemId": %d, "revision": 1}
                    ]
                }
                """
                        .formatted(firstId, secondId);

        long runId = insertRun(targetScope, "CANDIDATE", "SUCCEEDED", "2026-10-29T01:00:00Z");

        insertResult(runId, firstId, 1, 2, 3);
        insertResult(runId, secondId, 1, 1, 4);

        Map<Long, LatestCheckResponse> results =
                repository.findLatestByProposalRevisions(Map.of(firstId, 1, secondId, 1));

        assertThat(results).hasSize(2);
        assertThat(results.get(firstId).matchedCount()).isEqualTo(2);
        assertThat(results.get(secondId).matchedCount()).isEqualTo(1);
        assertThat(results.get(secondId).matchRate()).isEqualByComparingTo("0.2500");
    }

    @Test
    void matchesIdAndRevisionWithinTheSameScopeItem() {
        long id = insertProposal();
        long otherId = insertProposal();

        addRevision(id, 2);
        addRevision(otherId, 2);

        String targetScope =
                """
                {
                    "proposalItems": [
                        {"proposalItemId": %d, "revision": 1},
                        {"proposalItemId": %d, "revision": 2}
                    ]
                }
                """
                        .formatted(id, otherId);

        insertRun(targetScope, "CANDIDATE", "QUEUED", "2026-10-29T01:00:00Z");

        assertThat(repository.findLatestByProposalRevisions(Map.of(id, 2))).isEmpty();
    }

    @Test
    void usesSuppliedRevisionEvenWhenCurrentRevisionChanges() {
        long id = insertProposal();

        long oldRunId = insertRun(scope(id, 1), "CANDIDATE", "FAILED", "2026-10-29T01:00:00Z");

        addRevision(id, 2);

        insertRun(scope(id, 2), "CANDIDATE", "QUEUED", "2026-10-29T02:00:00Z");

        LatestCheckResponse result =
                repository.findLatestByProposalRevisions(Map.of(id, 1)).get(id);

        assertThat(result).isNotNull();
        assertThat(result.checkRunId()).isEqualTo(oldRunId);
    }

    @Test
    void returnsEmptyForEmptyTargets() {
        assertThat(repository.findLatestByProposalRevisions(Map.of())).isEmpty();
    }

    private String scope(long id, int revision) {
        return """
                {
                    "proposalItems": [
                        {"proposalItemId": %d, "revision": %d}
                    ]
                }
                """
                .formatted(id, revision);
    }

    private long insertProposal() {
        return jdbcClient
                .sql(
                        """
                        with inserted as (
                            insert into proposal_item (
                                origin, item_kind, payload, created_by
                            )
                            values (
                                'MANUAL', 'LINK_RULE',
                                '{"linkTypeId":5}'::jsonb, :accountId
                            )
                            returning *
                        )
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, created_by
                        )
                        select
                            id, revision, payload, evidence, created_by
                        from inserted
                        returning proposal_item_id
                        """)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private void addRevision(long id, int revision) {
        jdbcClient
                .sql(
                        """
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, created_by
                        )
                        select
                            id, :revision, payload, evidence, created_by
                        from proposal_item
                        where id = :id
                        """)
                .param("id", id)
                .param("revision", revision)
                .update();

        jdbcClient
                .sql(
                        """
                        update proposal_item
                        set revision = :revision
                        where id = :id
                        """)
                .param("id", id)
                .param("revision", revision)
                .update();
    }

    private long insertRun(
            String targetScope, String triggerKind, String status, String requestedAt) {

        return jdbcClient
                .sql(
                        """
                        insert into check_run (
                            trigger_kind, status, scope, work_key,
                            requested_by, requested_at,
                            started_at, finished_at, error_message
                        )
                        values (
                            :triggerKind,
                            :status,
                            cast(:scope as jsonb),
                            :workKey,
                            :accountId,
                            cast(:requestedAt as timestamptz),
                            case
                                when :status <> 'QUEUED'
                                then cast(:requestedAt as timestamptz)
                            end,
                            case
                                when :status in ('SUCCEEDED', 'FAILED')
                                then cast(:requestedAt as timestamptz)
                            end,
                            case
                                when :status = 'FAILED'
                                then '테스트용 검사 실패'
                            end
                        )
                        returning id
                        """)
                .param("triggerKind", triggerKind)
                .param("status", status)
                .param("scope", targetScope)
                .param("workKey", "latest-test-" + UUID.randomUUID())
                .param("accountId", accountId)
                .param("requestedAt", requestedAt)
                .query(Long.class)
                .single();
    }

    private void insertResult(long runId, long id, int revision, int matched, int total) {

        jdbcClient
                .sql(
                        """
                        insert into check_result (
                            check_run_id,
                            proposal_item_id,
                            proposal_item_revision,
                            matched_count,
                            total_count,
                            unmatched_count
                        )
                        values (
                            :runId, :id, :revision,
                            :matched, :total, :unmatched
                        )
                        """)
                .param("runId", runId)
                .param("id", id)
                .param("revision", revision)
                .param("matched", matched)
                .param("total", total)
                .param("unmatched", total - matched)
                .update();
    }
}

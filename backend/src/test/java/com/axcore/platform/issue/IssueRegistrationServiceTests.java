package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.issue.domain.IssueDedupeKey;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.IssueRegistration;
import com.axcore.platform.issue.domain.NewIssue;
import com.axcore.platform.issue.service.IssueRegistrationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 열린 문제 중복 방지와 등록 이력을 실제 PostgreSQL 제약으로 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueRegistrationServiceTests {

    @Autowired IssueRegistrationService registrationService;

    @Autowired JdbcClient jdbcClient;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long runId;
    private long mappingId;
    private String ruleCode;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        accountId = fixtures.insertAdmin();
        runId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
        ruleCode = "W2E-" + UUID.randomUUID();
    }

    @Test
    void createsIssueWithDetectedHistory() {
        IssueRegistration registration = registrationService.register(issue(runId, 501));

        assertThat(registration.created()).isTrue();
        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
                                select issue_kind, status, dedupe_key, link_mapping_id,
                                       detected_check_run_id, evidence->>'causeConfirmed' as cause
                                from issue where id = :id
                                """)
                        .param("id", registration.issueId())
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("issue_kind", "UNMATCHED")
                .containsEntry("status", "NEW")
                .containsEntry("dedupe_key", "UNMATCHED:rule:" + ruleCode + ":object:501")
                .containsEntry("link_mapping_id", mappingId)
                .containsEntry("detected_check_run_id", runId)
                .containsEntry("cause", "false");

        Map<String, Object> history =
                jdbcClient
                        .sql(
                                """
                                select from_status, to_status, check_run_id, changed_by, note
                                from issue_history where issue_id = :id
                                """)
                        .param("id", registration.issueId())
                        .query()
                        .singleRow();
        assertThat(history)
                .containsEntry("from_status", null)
                .containsEntry("to_status", "NEW")
                .containsEntry("check_run_id", runId)
                .containsEntry("changed_by", null)
                .containsEntry("note", null);
    }

    @Test
    void returnsOpenIssueInsteadOfDuplicating() {
        IssueRegistration first = registrationService.register(issue(runId, 501));
        long laterRunId = fixtures.insertCandidateRun();

        IssueRegistration again = registrationService.register(issue(laterRunId, 501));

        assertThat(again.created()).isFalse();
        assertThat(again.issueId()).isEqualTo(first.issueId());
        assertThat(countIssues()).isEqualTo(1);
        assertThat(countHistory(first.issueId())).isEqualTo(1);
        // 다시 발견했다고 처음 등록한 검사를 바꾸지 않는다.
        assertThat(detectedRun(first.issueId())).isEqualTo(runId);
    }

    @Test
    void keepsOpenIssueWhileBeingHandled() {
        IssueRegistration first = registrationService.register(issue(runId, 501));
        jdbcClient
                .sql("update issue set status = 'FIXED_PENDING_REVERIFY' where id = :id")
                .param("id", first.issueId())
                .update();

        IssueRegistration again = registrationService.register(issue(runId, 501));

        assertThat(again.created()).isFalse();
        assertThat(again.issueId()).isEqualTo(first.issueId());
    }

    @Test
    void registersRecurrenceAfterResolution() {
        IssueRegistration first = registrationService.register(issue(runId, 501));
        fixtures.resolve(first.issueId(), fixtures.insertReverifyRun(runId, accountId));
        long laterRunId = fixtures.insertCandidateRun();

        IssueRegistration recurrence = registrationService.register(issue(laterRunId, 501));

        assertThat(recurrence.created()).isTrue();
        assertThat(recurrence.issueId()).isNotEqualTo(first.issueId());
        assertThat(countIssues()).isEqualTo(2);
    }

    @Test
    void separatesIssuesByObjectAndKind() {
        long objectA = registrationService.register(issue(runId, 501)).issueId();
        long objectB = registrationService.register(issue(runId, 502)).issueId();
        long otherKind =
                registrationService
                        .register(
                                new NewIssue(
                                        IssueKind.DUPLICATE,
                                        IssueDedupeKey.forRule(IssueKind.DUPLICATE, ruleCode, 501),
                                        runId,
                                        "같은 직원이 두 명 있습니다.",
                                        mappingId,
                                        null,
                                        null,
                                        "W23",
                                        "E023",
                                        Map.of()))
                        .issueId();

        assertThat(objectA).isNotEqualTo(objectB).isNotEqualTo(otherKind);
        assertThat(countIssues()).isEqualTo(3);
    }

    private NewIssue issue(long checkRunId, long objectId) {
        return new NewIssue(
                IssueKind.UNMATCHED,
                IssueDedupeKey.forRule(IssueKind.UNMATCHED, ruleCode, objectId),
                checkRunId,
                "변환 값 E023과 같은 직원이 없습니다.",
                mappingId,
                null,
                null,
                "W23",
                "E023",
                Map.of("source", "MES", "causeConfirmed", false));
    }

    private long countIssues() {
        return jdbcClient
                .sql("select count(*) from issue where dedupe_key like :prefix")
                .param("prefix", "%:rule:" + ruleCode + ":%")
                .query(Long.class)
                .single();
    }

    private long countHistory(long issueId) {
        return jdbcClient
                .sql("select count(*) from issue_history where issue_id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }

    private long detectedRun(long issueId) {
        return jdbcClient
                .sql("select detected_check_run_id from issue where id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }
}

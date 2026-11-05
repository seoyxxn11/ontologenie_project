package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.service.IssueStatusService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 재검증 실패 때 검사가 문제를 확인중으로 되돌리는 규칙을 실제 PostgreSQL 제약까지 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueReopenServiceTests {

    private static final String EVIDENCE = "{\"source\": \"MES\"}";
    private static final Instant FIXED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER_FIX = Instant.parse("2026-11-01T01:00:00Z");
    private static final Instant BEFORE_FIX = Instant.parse("2026-10-31T23:00:00Z");

    @Autowired IssueStatusService statusService;

    @Autowired JdbcClient jdbcClient;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long detectedRunId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        accountId = fixtures.insertAdmin();
        detectedRunId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
    }

    @Test
    void reopensFixedIssueWhenReverificationFails() {
        long issueId = fixedIssue();
        long runId = failedReverification(issueId, AFTER_FIX);

        assertThat(statusService.reopenAfterFailedReverification(issueId, runId)).isTrue();

        assertThat(issueRow(issueId))
                .containsEntry("status", "CHECKING")
                .containsEntry("row_version", 2)
                .containsEntry("resolved_check_run_id", null);
        // 검사가 바꾼 행: 처리자 없이 그 재검증을 가리킨다.
        assertThat(lastHistory(issueId))
                .containsEntry("from_status", "FIXED_PENDING_REVERIFY")
                .containsEntry("to_status", "CHECKING")
                .containsEntry("changed_by", null)
                .containsEntry("check_run_id", runId)
                .containsEntry("note", null);
    }

    @Test
    void ignoresReverificationStartedBeforeFix() {
        long issueId = fixedIssue();
        long staleRunId = failedReverification(issueId, BEFORE_FIX);

        assertThat(statusService.reopenAfterFailedReverification(issueId, staleRunId)).isFalse();

        assertThat(issueRow(issueId))
                .containsEntry("status", "FIXED_PENDING_REVERIFY")
                .containsEntry("row_version", 1);
        assertThat(historyCount(issueId)).isEqualTo(1);
    }

    @Test
    void leavesIssueNotWaitingForReverification() {
        long newIssueId = newIssue();
        long checkingIssueId = newIssue();
        fixtures.setStatus(checkingIssueId, "CHECKING");
        long runId = fixtures.insertRun("REVERIFY", "RUNNING", AFTER_FIX, detectedRunId, accountId);
        fixtures.insertVerification(newIssueId, runId, "FAILED");
        fixtures.insertVerification(checkingIssueId, runId, "FAILED");

        assertThat(statusService.reopenAfterFailedReverification(newIssueId, runId)).isFalse();
        assertThat(statusService.reopenAfterFailedReverification(checkingIssueId, runId)).isFalse();

        assertThat(issueRow(newIssueId)).containsEntry("status", "NEW");
        assertThat(issueRow(checkingIssueId)).containsEntry("status", "CHECKING");
        assertThat(historyCount(newIssueId) + historyCount(checkingIssueId)).isZero();
    }

    @Test
    void doesNotTouchResolvedIssue() {
        long issueId = fixedIssue();
        long passedId =
                fixtures.insertRun("REVERIFY", "SUCCEEDED", AFTER_FIX, detectedRunId, accountId);
        fixtures.insertVerification(issueId, passedId, "PASSED");
        statusService.change(
                issueId,
                new IssueStatusChangeRequest("RESOLVED", "통과 확인", passedId, 1L),
                accountId);
        long laterRunId = failedReverification(issueId, AFTER_FIX.plusSeconds(600));

        assertThat(statusService.reopenAfterFailedReverification(issueId, laterRunId)).isFalse();

        assertThat(issueRow(issueId)).containsEntry("status", "RESOLVED");
    }

    @Test
    void rejectsCallWithoutFailedRecord() {
        long issueId = fixedIssue();
        long passedId =
                fixtures.insertRun("REVERIFY", "RUNNING", AFTER_FIX, detectedRunId, accountId);
        fixtures.insertVerification(issueId, passedId, "PASSED");
        long manualId =
                fixtures.insertRun("MANUAL", "RUNNING", AFTER_FIX, detectedRunId, accountId);
        fixtures.insertVerification(issueId, manualId, "FAILED");

        assertThatThrownBy(() -> statusService.reopenAfterFailedReverification(issueId, passedId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> statusService.reopenAfterFailedReverification(issueId, manualId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(issueRow(issueId)).containsEntry("status", "FIXED_PENDING_REVERIFY");
    }

    @Test
    void canBeFixedAndResolvedAgainAfterReopen() {
        long issueId = fixedIssue();
        long failedId = failedReverification(issueId, AFTER_FIX);
        statusService.reopenAfterFailedReverification(issueId, failedId);

        // 관리자가 다시 고쳤다고 표시하면 기준 시각이 새로 찍힌다.
        statusService.change(
                issueId,
                new IssueStatusChangeRequest("FIXED_PENDING_REVERIFY", "다시 수정함", null, 2L),
                accountId);
        long passedId =
                fixtures.insertRun(
                        "REVERIFY",
                        "SUCCEEDED",
                        Instant.now().plusSeconds(60),
                        detectedRunId,
                        accountId);
        fixtures.insertVerification(issueId, passedId, "PASSED");
        statusService.change(
                issueId,
                new IssueStatusChangeRequest("RESOLVED", "재검증 통과", passedId, 3L),
                accountId);

        assertThat(issueRow(issueId))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_check_run_id", passedId);
        assertThat(historyCount(issueId)).isEqualTo(4);
    }

    private long newIssue() {
        return fixtures.insertIssue(detectedRunId, mappingId, "UNMATCHED", "NEW", EVIDENCE);
    }

    /** FIXED_AT에 조치완료·재검증대기(rowVersion 1)가 된 문제. 이력은 그 한 행이다. */
    private long fixedIssue() {
        long issueId = newIssue();
        fixtures.markFixedAt(issueId, accountId, FIXED_AT);
        return issueId;
    }

    /** startedAt에 시작해 아직 실행 중인 재검증에서 이 문제가 FAILED로 기록된 상태. */
    private long failedReverification(long issueId, Instant startedAt) {
        long runId = fixtures.insertRun("REVERIFY", "RUNNING", startedAt, detectedRunId, accountId);
        fixtures.insertVerification(issueId, runId, "FAILED");
        return runId;
    }

    private Map<String, Object> issueRow(long issueId) {
        return jdbcClient
                .sql("select status, row_version, resolved_check_run_id from issue where id = :id")
                .param("id", issueId)
                .query()
                .singleRow();
    }

    private Map<String, Object> lastHistory(long issueId) {
        return jdbcClient
                .sql(
                        """
                        select from_status, to_status, changed_by, check_run_id, note
                        from issue_history where issue_id = :id
                        order by id desc limit 1
                        """)
                .param("id", issueId)
                .query()
                .singleRow();
    }

    private long historyCount(long issueId) {
        return jdbcClient
                .sql("select count(*) from issue_history where issue_id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }
}

package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.service.IssueStatusService;
import java.time.Instant;
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
    private CheckRunTestFixtures syncFixtures;
    private long dataSourceId;
    private long accountId;
    private long detectedRunId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        syncFixtures = new CheckRunTestFixtures(jdbcClient);
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
    void ignoresFullCompareDataReadBeforeFixEvenIfTheCheckStartedAfter() {
        // 수동 다시 읽기가 이미 읽고 저장 중이던 전체 읽기에 합류한 경우: 수집은 조치완료 뒤에 끝났어도 읽기는 그 전에 시작했다.
        long issueId = fixedIssue();
        long runId = failedReverification(issueId, AFTER_FIX);
        basedOn(runId, resource("FULL_COMPARE"), BEFORE_FIX);

        assertThat(statusService.reopenAfterFailedReverification(issueId, runId)).isFalse();

        assertThat(issueRow(issueId)).containsEntry("status", "FIXED_PENDING_REVERIFY");
        assertThat(historyCount(issueId)).isEqualTo(1);
    }

    @Test
    void reopensWhenFullCompareDataWasReadAfterFix() {
        long issueId = fixedIssue();
        long runId = failedReverification(issueId, AFTER_FIX.plusSeconds(60));
        basedOn(runId, resource("FULL_COMPARE"), AFTER_FIX);

        assertThat(statusService.reopenAfterFailedReverification(issueId, runId)).isTrue();
        assertThat(issueRow(issueId)).containsEntry("status", "CHECKING");
    }

    @Test
    void ignoresFullCompareTableWithoutAnySuccessfulLoad() {
        long issueId = fixedIssue();
        long runId = failedReverification(issueId, AFTER_FIX);
        long resourceId = resource("FULL_COMPARE");
        jdbcClient
                .sql(
                        """
                        update check_run set data_basis = jsonb_build_object('resources',
                            jsonb_build_array(jsonb_build_object(
                                'sourceResourceId', :resource, 'lastSuccessfulSyncRunId', null)))
                        where id = :run
                        """)
                .param("resource", resourceId)
                .param("run", runId)
                .update();

        assertThat(statusService.reopenAfterFailedReverification(issueId, runId)).isFalse();
    }

    @Test
    void cdcTablesOnlyNeedTheCheckToStartAfterFix() {
        // CDC 표는 첫 적재가 조치완료 한참 전이어도 이후 변경이 계속 반영된다.
        long issueId = fixedIssue();
        long runId = failedReverification(issueId, AFTER_FIX);
        basedOn(runId, resource("CDC"), BEFORE_FIX.minusSeconds(86_400));

        assertThat(statusService.reopenAfterFailedReverification(issueId, runId)).isTrue();
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
                issueId, new IssueStatusChangeRequest("RESOLVED", "통과 확인", passedId, 1L), admin());
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
                admin());
        long passedId =
                fixtures.insertRun(
                        "REVERIFY",
                        "SUCCEEDED",
                        Instant.now().plusSeconds(60),
                        detectedRunId,
                        accountId);
        fixtures.insertVerification(issueId, passedId, "PASSED");
        statusService.change(
                issueId, new IssueStatusChangeRequest("RESOLVED", "재검증 통과", passedId, 3L), admin());

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

    /** 수집 전략이 strategy인 원천 표. */
    private long resource(String strategy) {
        if (dataSourceId == 0) {
            dataSourceId = syncFixtures.insertDataSource();
        }
        long resourceId =
                syncFixtures.insertResource(dataSourceId, "mes.table_" + UUID.randomUUID());
        jdbcClient
                .sql("update source_resource set sync_strategy = :strategy where id = :id")
                .param("strategy", strategy)
                .param("id", resourceId)
                .update();
        return resourceId;
    }

    /** 재검증의 데이터 기준을 "그 표의 마지막 성공 수집 = readStartedAt에 시작한 전체 읽기"로 둔다. */
    private void basedOn(long checkRunId, long resourceId, Instant readStartedAt) {
        long syncRunId =
                syncFixtures.insertSync(
                        dataSourceId, resourceId, accountId, "SUCCEEDED", readStartedAt);
        jdbcClient
                .sql(
                        """
                        update check_run set data_basis = jsonb_build_object('resources',
                            jsonb_build_array(jsonb_build_object(
                                'sourceResourceId', :resource, 'lastSuccessfulSyncRunId', :sync)))
                        where id = :run
                        """)
                .param("resource", resourceId)
                .param("sync", syncRunId)
                .param("run", checkRunId)
                .update();
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

    private LoginAccount admin() {
        return new LoginAccount(accountId, "admin", "ADMIN", false);
    }
}

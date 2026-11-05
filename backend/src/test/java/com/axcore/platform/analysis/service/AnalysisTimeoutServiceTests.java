package com.axcore.platform.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.analysis.dto.AnalysisResultRequest;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 멈춘 분석 실행 정리(S15P31C106-273). 기준은 기본값(QUEUED 10분, RUNNING 60분)이다. 다른 테스트와 DB를 같이 쓰므로 이 테스트가 만든 실행만
 * 확인한다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AnalysisTimeoutServiceTests {

    @Autowired AnalysisTimeoutService timeoutService;

    @Autowired AnalysisQueryService queryService;

    @Autowired AnalysisResultService resultService;

    @Autowired JdbcClient jdbcClient;

    private long accountId;

    @BeforeEach
    void createAccount() {
        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '테스트 관리자', 'x', (select id from role where code = 'ADMIN'),
                                        false)
                                returning id
                                """)
                        .param("loginId", "timeout-" + UUID.randomUUID().toString().substring(0, 8))
                        .query(Long.class)
                        .single();
    }

    @Test
    void failsQueuedAndRunningRunsPastTheirLimit() {
        long staleQueued = insertRun("QUEUED", "11 minutes", null);
        long staleRunning = insertRun("RUNNING", "3 hours", "61 minutes");

        assertThat(timeoutService.failStaleRuns()).contains(staleQueued, staleRunning);

        var queued = queryService.get(staleQueued);
        assertThat(queued.status()).isEqualTo("FAILED");
        assertThat(queued.errorMessage()).isEqualTo("분석이 10분 안에 시작되지 않아 시간 초과로 중단했습니다.");
        var running = queryService.get(staleRunning);
        assertThat(running.status()).isEqualTo("FAILED");
        assertThat(running.errorMessage()).isEqualTo("분석이 시작 후 60분 안에 끝나지 않아 시간 초과로 중단했습니다.");
        assertThat(finishedAt(staleRunning)).isNotNull();
        assertThat(auditCount(staleQueued)).isEqualTo(1);
        assertThat(auditCount(staleRunning)).isEqualTo(1);
    }

    @Test
    void leavesRunsWithinTheirLimitAndFinishedRuns() {
        long recentQueued = insertRun("QUEUED", "5 minutes", null);
        // 요청은 오래됐어도 시작한 지 30분이면 정상 진행 중이다. RUNNING은 시작 시각으로 잰다.
        long recentRunning = insertRun("RUNNING", "3 hours", "30 minutes");
        long oldSucceeded = insertRun("SUCCEEDED", "5 hours", "4 hours");

        assertThat(timeoutService.failStaleRuns())
                .doesNotContain(recentQueued, recentRunning, oldSucceeded);

        assertThat(queryService.get(recentQueued).status()).isEqualTo("QUEUED");
        assertThat(queryService.get(recentRunning).status()).isEqualTo("RUNNING");
        assertThat(queryService.get(oldSucceeded).status()).isEqualTo("SUCCEEDED");
        assertThat(auditCount(recentQueued)).isZero();
    }

    @Test
    void releasesWorkKeySoTheSameAnalysisCanRunAgain() {
        String workKey = "timeout-" + UUID.randomUUID();
        insertRun("RUNNING", "3 hours", "2 hours", workKey);

        // 정리 전에는 같은 대상의 새 실행을 만들 수 없다(uq_analysis_run_active).
        assertThatThrownBy(() -> insertRun("QUEUED", "0 minutes", null, workKey))
                .isInstanceOf(DuplicateKeyException.class);

        timeoutService.failStaleRuns();

        long rerun = insertRun("QUEUED", "0 minutes", null, workKey);
        assertThat(queryService.get(rerun).status()).isEqualTo("QUEUED");
    }

    @Test
    void rejectsLateCompletionAfterTimeout() {
        long run = insertRun("RUNNING", "3 hours", "2 hours");
        timeoutService.failStaleRuns();

        assertThatThrownBy(
                        () ->
                                resultService.accept(
                                        run,
                                        new AnalysisResultRequest(
                                                "SUCCEEDED", null, Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(CommonErrorCode.CONFLICT));
        assertThat(queryService.get(run).status()).isEqualTo("FAILED");
    }

    @Test
    void labelsDurationInMinutesOrSeconds() {
        assertThat(AnalysisTimeoutService.label(Duration.ofMinutes(10))).isEqualTo("10분");
        assertThat(AnalysisTimeoutService.label(Duration.ofMinutes(60))).isEqualTo("60분");
        assertThat(AnalysisTimeoutService.label(Duration.ofSeconds(90))).isEqualTo("90초");
    }

    private long insertRun(String status, String requestedAgo, String startedAgo) {
        return insertRun(status, requestedAgo, startedAgo, "timeout-" + UUID.randomUUID());
    }

    private long insertRun(String status, String requestedAgo, String startedAgo, String workKey) {
        return jdbcClient
                .sql(
                        """
                        insert into analysis_run (run_kind, work_key, status, requested_by, requested_at,
                                                  started_at, finished_at)
                        values ('INITIAL', :workKey, :status, :account,
                                now() - cast(:requestedAgo as interval),
                                now() - cast(:startedAgo as interval),
                                case when :status in ('SUCCEEDED', 'FAILED') then now() end)
                        returning id
                        """)
                .param("workKey", workKey)
                .param("status", status)
                .param("account", accountId)
                .param("requestedAgo", requestedAgo)
                .param("startedAgo", startedAgo, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    private Object finishedAt(long runId) {
        return jdbcClient
                .sql("select finished_at from analysis_run where id = :id")
                .param("id", runId)
                .query(Object.class)
                .single();
    }

    private long auditCount(long runId) {
        return jdbcClient
                .sql(
                        """
                        select count(*) from action_execution
                        where event_kind = 'ANALYSIS_RUN' and target_type = 'analysis_run'
                          and target_id = :id and status = 'FAILED' and entry_point = 'BATCH'
                        """)
                .param("id", runId)
                .query(Long.class)
                .single();
    }
}

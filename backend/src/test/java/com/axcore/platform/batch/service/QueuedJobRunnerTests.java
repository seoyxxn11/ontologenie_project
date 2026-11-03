package com.axcore.platform.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.domain.QueuedJobType;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 작업 표에서 작업을 가져와 처리하는 흐름(215)을 실제 DB 제약·잠금으로 확인한다. */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import({TestcontainersConfiguration.class, QueuedJobRunnerTests.HandlerConfig.class})
class QueuedJobRunnerTests {

    // 주기 실행이 테스트가 넣은 행을 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;

    @Autowired QueuedJobRunner queuedJobRunner;

    @Autowired TestCheckHandler handler;

    @Autowired JdbcClient jdbcClient;

    @Autowired DataSource dataSource;

    @BeforeEach
    void clear() {
        jdbcClient.sql("delete from entity_change").update();
        jdbcClient.sql("delete from action_execution").update();
        jdbcClient.sql("delete from check_run").update();
        handler.reset();
    }

    @Test
    void runsQueuedJobAndRecordsResultLine() {
        long id = queueCheckRun("k-1");

        assertThat(queuedJobRunner.runNext(handler)).isTrue();

        Map<String, Object> run = checkRun(id);
        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("started_at")).isNotNull();
        assertThat(run.get("finished_at")).isNotNull();

        Map<String, Object> audit = auditFor(id);
        assertThat(audit)
                .containsEntry("event_kind", "CHECK_RUN")
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("target_type", "check_run");
        assertThat(audit.get("result").toString()).contains("\"checked\": 3");
        assertThat(audit.get("duration_ms")).isNotNull();
    }

    @Test
    void returnsFalseWhenNothingIsQueued() {
        assertThat(queuedJobRunner.runNext(handler)).isFalse();
        assertThat(handler.runCount).hasValue(0);
    }

    @Test
    void keepsHandlerFailureMessage() {
        long id = queueCheckRun("k-1");
        handler.onRun(
                () -> {
                    throw new QueuedJobFailure("원천에 접속하지 못했습니다.");
                });

        assertThat(queuedJobRunner.runNext(handler)).isTrue();

        assertThat(checkRun(id))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", "원천에 접속하지 못했습니다.");
        assertThat(auditFor(id)).containsEntry("status", "FAILED");
        assertThat(auditFor(id).get("result").toString()).contains("원천에 접속하지 못했습니다.");
    }

    @Test
    void hidesUnexpectedErrorDetails() {
        long id = queueCheckRun("k-1");
        handler.onRun(
                () -> {
                    throw new IllegalStateException("jdbc password=secret-value");
                });

        queuedJobRunner.runNext(handler);

        assertThat(checkRun(id))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", QueuedJobRunner.UNEXPECTED_FAILURE);
        assertThat(auditFor(id).get("result").toString()).doesNotContain("secret-value");
    }

    @Test
    void failsWhenHandlerDoesNotFinishRow() {
        long id = queueCheckRun("k-1");
        handler.finishRow = false;

        queuedJobRunner.runNext(handler);

        assertThat(checkRun(id))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", QueuedJobRunner.UNEXPECTED_FAILURE);
    }

    @Test
    void rollsBackCompletionWhenCompleteFails() {
        long id = queueCheckRun("k-1");
        handler.failAfterFinish = true;

        queuedJobRunner.runNext(handler);

        // complete 안에서 SUCCEEDED로 바꾼 뒤 실패했으므로 그 변경은 되돌리고 FAILED로 남긴다.
        assertThat(checkRun(id)).containsEntry("status", "FAILED");
        assertThat(
                        jdbcClient
                                .sql("select count(*) from action_execution where target_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void takesOldestQueuedJobFirst() {
        long first = queueCheckRun("k-1");
        long second = queueCheckRun("k-2");

        queuedJobRunner.runNext(handler);

        assertThat(checkRun(first)).containsEntry("status", "SUCCEEDED");
        assertThat(checkRun(second)).containsEntry("status", "QUEUED");
    }

    @Test
    void skipsJobLockedByAnotherWorker() throws Exception {
        long locked = queueCheckRun("k-1");
        long free = queueCheckRun("k-2");

        // 다른 배치 인스턴스가 먼저 잡은 상황을 다른 연결의 행 잠금으로 만든다.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var statement =
                    other.prepareStatement("select id from check_run where id = ? for update")) {
                statement.setLong(1, locked);
                statement.executeQuery();
            }

            assertThat(queuedJobRunner.runNext(handler)).isTrue();

            other.rollback();
        }

        assertThat(checkRun(locked)).containsEntry("status", "QUEUED");
        assertThat(checkRun(free)).containsEntry("status", "SUCCEEDED");
    }

    private long queueCheckRun(String workKey) {
        // 후보 시험(CANDIDATE)은 요청자 없이 넣을 수 있어 계정을 만들지 않아도 된다.
        return jdbcClient
                .sql(
                        """
                        insert into check_run (trigger_kind, work_key, requested_at)
                        values ('CANDIDATE', :workKey, clock_timestamp())
                        returning id
                        """)
                .param("workKey", workKey)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> checkRun(long id) {
        return jdbcClient
                .sql(
                        "select status, started_at, finished_at, error_message from check_run where id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    private Map<String, Object> auditFor(long id) {
        return jdbcClient
                .sql(
                        """
                        select event_kind, actor_type, entry_point, status, target_type,
                               result::text as result, duration_ms
                        from action_execution where target_type = 'check_run' and target_id = :id
                        """)
                .param("id", id)
                .query()
                .singleRow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        TestCheckHandler testCheckHandler(JdbcClient jdbcClient) {
            return new TestCheckHandler(jdbcClient);
        }
    }

    /** 검사 작업을 흉내 낸다. run은 검사한 건수를 돌려주고 complete가 행을 SUCCEEDED로 바꾼다. */
    static class TestCheckHandler implements QueuedJobHandler<Integer> {

        private final JdbcClient jdbcClient;
        private final AtomicReference<Supplier<Integer>> onRun = new AtomicReference<>();
        final AtomicInteger runCount = new AtomicInteger();
        boolean finishRow;
        boolean failAfterFinish;

        TestCheckHandler(JdbcClient jdbcClient) {
            this.jdbcClient = jdbcClient;
            reset();
        }

        void reset() {
            onRun.set(() -> 3);
            runCount.set(0);
            finishRow = true;
            failAfterFinish = false;
        }

        void onRun(Supplier<Integer> behavior) {
            onRun.set(behavior);
        }

        @Override
        public QueuedJobType type() {
            return QueuedJobType.CHECK_RUN;
        }

        @Override
        public Integer run(long jobId) {
            runCount.incrementAndGet();
            return onRun.get().get();
        }

        @Override
        public void complete(long jobId, Integer checked) {
            if (finishRow) {
                jdbcClient
                        .sql(
                                "update check_run set status = 'SUCCEEDED', finished_at = now() where id = :id")
                        .param("id", jobId)
                        .update();
            }
            if (failAfterFinish) {
                throw new IllegalStateException("결과 저장 실패");
            }
        }

        @Override
        public Map<String, Object> auditResult(Integer checked) {
            return Map.of("checked", checked);
        }
    }
}

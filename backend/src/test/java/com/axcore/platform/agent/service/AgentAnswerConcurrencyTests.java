package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 5분 정리와 답변 결과 저장이 같은 답변을 동시에 고칠 때를 실제 DB로 확인한다(160). 한쪽이 답변을 잠근 채 커밋 전에 멈춰 있고, 다른 쪽이 실제로 그 잠금을
 * 기다리는 것을 DB에서 확인한 뒤 풀어 준다. 시간에 기대지 않으므로 느린 환경에서도 같은 순서가 된다. AgentMessageApiTests와 같은 테스트 환경을 써서
 * Spring 환경을 다시 만들지 않는다.
 */
@SpringBootTest(properties = "test.context=agent-message")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AgentAnswerConcurrencyTests {
    private static final AgentActionOutcome PLAN_CREATED =
            new AgentActionOutcome(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "SUCCEEDED",
                    512L,
                    Map.of("planId", 3, "itemCount", 1),
                    null);

    @Autowired JdbcClient jdbc;
    @Autowired AgentMessageRepository messages;
    @Autowired AgentMessageService messageService;
    @Autowired AgentAnswerService answerService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean QuestionInterpreter interpreter;
    @MockitoBean AgentResponder responder;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger holderPid = new AtomicInteger();
    private long owner;
    private long conversation;
    private long answerId;

    @BeforeEach
    void setUp() {
        owner = account();
        conversation = conversation(owner);
        answerId = runningAnswer(conversation, 10);
    }

    /** 실패한 테스트의 스레드가 다음 테스트까지 남아 잠금을 쥐지 않도록, 남은 작업이 끝날 때까지 기다린 뒤 닫는다. */
    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupWaitsForAnswerBeingSavedAndDoesNotOverwriteIt() throws Exception {
        // 처리기가 계획 결과를 저장하는 중이다(답변을 잠그고 아직 커밋 전).
        Future<?> saving =
                holdLockWhile(
                        () ->
                                messages.findForUpdateById(answerId)
                                        .orElseThrow()
                                        .succeed(
                                                "재배정 계획을 만들었습니다.",
                                                Map.of(
                                                        "syncStatus", "NONE",
                                                        "results", List.of(),
                                                        "actions", List.of(PLAN_CREATED.toMap()))));

        // 그 사이 084 조회가 5분 넘은 답변을 정리하려 한다. 정리는 잠금을 기다려야 한다.
        Future<?> cleanup =
                pool.submit(
                        () ->
                                messageService.search(
                                        ownerAccount(), conversation, PageRequest.of(0, 20)));
        awaitLockWaiter();
        release.countDown();
        saving.get(10, TimeUnit.SECONDS);
        cleanup.get(10, TimeUnit.SECONDS);

        // 정리가 저장된 결과를 "처리 시간 초과"로 덮어쓰지 않는다.
        Map<String, Object> row = answerRow();
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat((String) row.get("meta")).contains("CREATE_REASSIGNMENT_PLAN");
    }

    @Test
    void savingWaitsForCleanupAndOnlyAttachesActions() throws Exception {
        // 084 조회가 5분 넘은 답변을 정리하는 중이다(실제 정리 경로로 답변을 잠그고 아직 커밋 전).
        // search는 @Transactional(REQUIRED)이라 아래 트랜잭션에 합류해, 커밋 전에 멈출 수 있다.
        Future<?> cleaning =
                holdLockWhile(
                        () ->
                                messageService.search(
                                        ownerAccount(), conversation, PageRequest.of(0, 20)));

        // 그 사이 처리기가 계획을 만든 결과를 저장하려 한다. 저장은 잠금을 기다려야 한다.
        Future<?> saving =
                pool.submit(
                        () ->
                                answerService.answerWithResults(
                                        answerId,
                                        "재배정 계획을 만들었습니다.",
                                        new QuestionInterpretation(
                                                "재배정",
                                                List.of("PR-03"),
                                                null,
                                                null,
                                                List.of(),
                                                null),
                                        List.of(),
                                        List.of(PLAN_CREATED)));
        awaitLockWaiter();
        release.countDown();
        cleaning.get(10, TimeUnit.SECONDS);
        saving.get(10, TimeUnit.SECONDS);

        // 정리된 답변은 결과로 덮지 않고, 만든 계획만 덧붙인다.
        Map<String, Object> row = answerRow();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_message")).isEqualTo(AgentMessageService.STALE_REASON);
        assertThat(row.get("content")).isEqualTo(AgentAnswerService.ACTIONS_DONE_FAILED_CONTENT);
        assertThat((String) row.get("meta")).contains("CREATE_REASSIGNMENT_PLAN");
    }

    /**
     * 다른 스레드에서 트랜잭션을 열어 work를 하고, 커밋하기 전에 release를 기다린다. 잠금을 쥔 뒤 그 연결의 pid를 남기고 locked를 알린다.
     * release가 오지 않으면 예외로 롤백해, 혼자 커밋해 다른 쪽 결과를 흐리지 않게 한다.
     */
    private Future<?> holdLockWhile(Runnable work) throws Exception {
        Future<?> holder =
                pool.submit(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                status -> {
                                                    work.run();
                                                    messages.flush();
                                                    holderPid.set(
                                                            jdbc.sql("select pg_backend_pid()")
                                                                    .query(Integer.class)
                                                                    .single());
                                                    locked.countDown();
                                                    awaitRelease();
                                                }));
        if (!locked.await(10, TimeUnit.SECONDS)) {
            // 잠그기 전에 실패했으면 그 원래 예외를 드러낸다.
            if (holder.isDone()) {
                holder.get();
            }
            throw new AssertionError("10초 안에 답변을 잠그지 못했습니다.");
        }
        return holder;
    }

    /** 위에서 잠금을 쥔 연결을 기다리는 다른 연결이 생길 때까지 기다린다(최대 10초). 이 테스트가 쥔 잠금만 센다. */
    private void awaitLockWaiter() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting =
                    jdbc.sql(
                                    """
                            select count(*) from pg_stat_activity
                            where datname = current_database() and :holder = any(pg_blocking_pids(pid))
                            """)
                            .param("holder", holderPid.get())
                            .query(Integer.class)
                            .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("다른 쪽이 10초 안에 답변 잠금을 기다리지 않았습니다.");
    }

    private void awaitRelease() {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금을 풀라는 신호가 오지 않아 롤백합니다.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기다리는 중에 중단되어 롤백합니다.", ex);
        }
    }

    private Map<String, Object> answerRow() {
        return jdbc.sql(
                        """
                select status, error_message, content, answer_meta::text as meta
                from agent_message where id = :id
                """)
                .param("id", answerId)
                .query()
                .singleRow();
    }

    private long account() {
        return jdbc.sql(
                        """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '동시 저장 사용자', 'test-hash', (select id from role where code='VIEWER'), false) returning id
                """)
                .param("login", "agent-lock-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private long conversation(long accountId) {
        return jdbc.sql("insert into agent_conversation(account_id) values (:account) returning id")
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    private long runningAnswer(long conversationId, int minutesAgo) {
        return jdbc.sql(
                        """
                insert into agent_message(conversation_id, sender, content, status, answer_meta, created_at)
                values (:conversation, 'AGENT', '', 'RUNNING', '{"stage":"QUERYING"}'::jsonb,
                        now() - make_interval(mins => :minutes)) returning id
                """)
                .param("conversation", conversationId)
                .param("minutes", minutesAgo)
                .query(Long.class)
                .single();
    }

    private LoginAccount ownerAccount() {
        return new LoginAccount(owner, "owner", "ADMIN", false);
    }
}

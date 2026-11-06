package com.axcore.platform.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.service.QuestionInterpretation;
import com.axcore.platform.agent.service.QuestionInterpreter;
import com.axcore.platform.global.security.LoginAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// csrf()를 쓰므로 테스트 환경을 따로 쓴다. 커밋 뒤 처리기를 확인하려고 @Transactional은 붙이지 않는다.
@SpringBootTest(properties = "test.context=agent-message")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AgentMessageApiTests {
    private static final String MESSAGES = "/api/v1/agent/conversations/{id}/messages";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean QuestionInterpreter interpreter;
    long owner;
    long conversation;

    @BeforeEach
    void setUp() {
        owner = account();
        conversation = conversation(owner);
    }

    @Test
    void acceptsQuestionAndAnswersWithUnderstoodConditions() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-27"),
                                List.of(301L)));

        askRaw(
                        conversation,
                        """
                        {"content":"PR-03 비가동 이력 알려줘","referenceObjectIds":[301],
                         "context":{"period":{"from":"2026-10-01","to":"2026-10-27"}}}
                        """)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sender").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("PR-03 비가동 이력 알려줘"))
                .andExpect(jsonPath("$.data[0].context.referenceObjectIds[0]").value(301))
                .andExpect(jsonPath("$.data[0].context.period.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data[0].status").value(nullValue()))
                .andExpect(jsonPath("$.data[1].sender").value("AGENT"))
                .andExpect(jsonPath("$.data[1].status").value("RUNNING"))
                .andExpect(jsonPath("$.data[1].content").value(""))
                .andExpect(jsonPath("$.data[1].answerMeta.stage").value("INTERPRETING"));
        awaitAnswered(conversation);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].content").value(containsString("대상: PR-03")))
                .andExpect(jsonPath("$.data[1].answerMeta.stage").doesNotExist())
                .andExpect(jsonPath("$.data[1].answerMeta.syncStatus").value("NONE"))
                .andExpect(jsonPath("$.data[1].answerMeta.results.length()").value(0))
                .andExpect(jsonPath("$.data[1].answerMeta.conditions.purpose").value("비가동 이력"))
                .andExpect(jsonPath("$.data[1].answerMeta.conditions.targets[0]").value("PR-03"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.period.from").value("2026-10-01"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.referenceObjectIds[0]")
                                .value(301));
        assertThat(title(conversation)).isEqualTo("PR-03 비가동 이력 알려줘");
    }

    @Test
    void failsAnswerWhenAiIsNotConfigured() throws Exception {
        when(interpreter.isConfigured()).thenReturn(false);

        ask(conversation, "PR-03 상태 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].errorMessage").value("AI 설정 없음"));
    }

    @Test
    void failsAnswerWhenAiCallFails() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap()))
                .thenThrow(new IllegalStateException("GMS 시간 초과"));

        ask(conversation, "PR-03 상태 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].errorMessage").value("AI 응답 실패"))
                .andExpect(jsonPath("$.data[1].answerMeta.syncStatus").value("NONE"));
    }

    @Test
    void keepsFirstQuestionAsTitle() throws Exception {
        ask(conversation, "첫 질문").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "두 번째 질문").andExpect(status().isAccepted());
        assertThat(title(conversation)).isEqualTo("첫 질문");
    }

    @Test
    void rejectsQuestionWhileAnswerIsRunning() throws Exception {
        runningAnswer(conversation, 0);

        ask(conversation, "또 질문")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void failsAnswerStuckForMoreThanFiveMinutes() throws Exception {
        long stuck = runningAnswer(conversation, 10);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(stuck))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("처리 시간 초과"));
        ask(conversation, "다시 질문").andExpect(status().isAccepted());
    }

    @Test
    void hidesOtherUsersConversation() throws Exception {
        long others = conversation(account());

        mvc.perform(get(MESSAGES, others).with(loggedIn(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        ask(others, "남의 대화").andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidQuestionAndPaging() throws Exception {
        askRaw(conversation, "{\"content\":\"   \"}").andExpect(status().isBadRequest());
        askRaw(conversation, "{\"content\":\"" + "가".repeat(2001) + "\"}")
                .andExpect(status().isBadRequest());
        askRaw(conversation, "{\"content\":\"질문\",\"referenceObjectIds\":[0]}")
                .andExpect(status().isBadRequest());
        mvc.perform(get(MESSAGES, conversation).param("page", "-1").with(loggedIn(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get(MESSAGES, conversation)).andExpect(status().isUnauthorized());
    }

    private ResultActions ask(long conversationId, String content) throws Exception {
        return askRaw(conversationId, "{\"content\":\"%s\"}".formatted(content));
    }

    private ResultActions askRaw(long conversationId, String body) throws Exception {
        return mvc.perform(
                post(MESSAGES, conversationId)
                        .with(loggedIn(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long account() {
        return jdbc.sql(
                        """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '메시지 사용자', 'test-hash', (select id from role where code='VIEWER'), false) returning id
                """)
                .param("login", "agent-msg-" + UUID.randomUUID())
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
                values (:conversation, 'AGENT', '', 'RUNNING', '{"stage":"INTERPRETING"}'::jsonb,
                        now() - make_interval(mins => :minutes)) returning id
                """)
                .param("conversation", conversationId)
                .param("minutes", minutesAgo)
                .query(Long.class)
                .single();
    }

    private String title(long conversationId) {
        return jdbc.sql("select title from agent_conversation where id = :id")
                .param("id", conversationId)
                .query(String.class)
                .single();
    }

    /** 답변은 다른 스레드에서 끝나므로 RUNNING이 없어질 때까지 최대 5초 기다린다. */
    private void awaitAnswered(long conversationId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer running =
                    jdbc.sql(
                                    "select count(*) from agent_message where conversation_id = :c and status = 'RUNNING'")
                            .param("c", conversationId)
                            .query(Integer.class)
                            .single();
            if (running == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("답변이 5초 안에 끝나지 않았습니다.");
    }

    private static RequestPostProcessor loggedIn(long accountId) {
        return authentication(
                new LoginAccount(accountId, "agent-user", "VIEWER", false).toAuthentication());
    }
}

package com.axcore.platform.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.service.AgentQuerySession;
import com.axcore.platform.agent.service.AgentQueryTools;
import com.axcore.platform.agent.service.AgentReferenceObject;
import com.axcore.platform.agent.service.AgentResponder;
import com.axcore.platform.agent.service.AgentTargetResolver;
import com.axcore.platform.agent.service.ConversationTurn;
import com.axcore.platform.agent.service.PendingClarification;
import com.axcore.platform.agent.service.PersonEvaluationExamples;
import com.axcore.platform.agent.service.QuestionInterpretation;
import com.axcore.platform.agent.service.QuestionInterpreter;
import com.axcore.platform.agent.service.QuestionScope;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
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
    @Autowired AuditExecutionQueryService executions;
    @Autowired ObjectSearchQuery objects;
    @MockitoBean QuestionInterpreter interpreter;
    @MockitoBean AgentResponder responder;
    @MockitoBean AgentTargetResolver targetResolver;
    long owner;
    long conversation;

    @BeforeEach
    void setUp() {
        owner = account();
        conversation = conversation(owner);
        // 대상 찾기(147)는 객체 검색 데이터가 필요해 AgentTargetResolverTests에서 따로 시험한다. 여기서는 질문의 대상이 객체 302로
        // 정해진다고 둔다. 화면 참고 객체(301)와 다른 값으로 두어, 답변 조건이 화면 값이 아니라 찾은 값에서 온 것을 구별한다.
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(
                        new AgentTargetResolver.Resolution(
                                List.of(new AgentReferenceObject(302L, "PR-03", "설비")), List.of()));
    }

    @Test
    void acceptsQuestionAndAnswersWithQueryTools() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            AgentQuerySession session = call.getArgument(2);
                            new AgentQueryTools(session, executions, objects)
                                    .objectExecutions(302L, null, null);
                            return "PR-03의 실행 이력이 없습니다.";
                        });
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-27"),
                                List.of(new AgentReferenceObject(301L, null, null)),
                                null));

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
                .andExpect(jsonPath("$.data[1].content").value("PR-03의 실행 이력이 없습니다."))
                .andExpect(jsonPath("$.data[1].answerMeta.stage").doesNotExist())
                .andExpect(jsonPath("$.data[1].answerMeta.syncStatus").value("NONE"))
                .andExpect(jsonPath("$.data[1].answerMeta.results.length()").value(1))
                .andExpect(jsonPath("$.data[1].answerMeta.results[0].label").value("실행 이력"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.results[0].toolCode")
                                .value("OBJECT_EXECUTIONS"))
                .andExpect(jsonPath("$.data[1].answerMeta.results[0].status").value("SUCCEEDED"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.results[0].resultCategory").value("NO_DATA"))
                .andExpect(jsonPath("$.data[1].answerMeta.results[0].dataAsOf").value(nullValue()))
                .andExpect(jsonPath("$.data[1].answerMeta.conditions.purpose").value("비가동 이력"))
                .andExpect(jsonPath("$.data[1].answerMeta.conditions.targets[0]").value("PR-03"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.period.from").value("2026-10-01"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.referenceObjectIds[0]")
                                .value(302))
                // 이 질문은 Action을 부르지 않았으므로 실행 결과는 비어 있다(160).
                .andExpect(jsonPath("$.data[1].answerMeta.actions.length()").value(0));
        // 화면 참고 객체(301)는 대상을 고를 때 먼저 볼 객체로 넘긴다.
        verify(targetResolver)
                .resolve(
                        eq(List.of("PR-03")),
                        argThat(known -> known.stream().anyMatch(object -> object.id() == 301L)),
                        any());
        assertThat(title(conversation)).isEqualTo("PR-03 비가동 이력 알려줘");
        // 조회 기록은 질문 메시지에 연결되고, Agent가 질문한 사용자를 대신한 것으로 남는다.
        Map<String, Object> query =
                jdbc.sql(
                                """
                                select e.actor_type, e.actor_id, e.entry_point, e.tool_code, e.sequence_no,
                                       e.result_category, e.target_id, m.sender
                                from action_execution e join agent_message m on m.id = e.agent_message_id
                                where e.event_kind = 'AGENT_QUERY' and m.conversation_id = :c
                                """)
                        .param("c", conversation)
                        .query()
                        .singleRow();
        assertThat(query)
                .containsEntry("actor_type", "AGENT")
                .containsEntry("actor_id", owner)
                .containsEntry("entry_point", "AGENT")
                .containsEntry("tool_code", "OBJECT_EXECUTIONS")
                .containsEntry("sequence_no", 1)
                .containsEntry("result_category", "NO_DATA")
                .containsEntry("target_id", 302L)
                .containsEntry("sender", "USER");
    }

    @Test
    void asksBackWhenConditionsAreMissing() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(null, List.of(), null, null, List.of(), "최근"));

        ask(conversation, "최근 거 보여줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].content").value(containsString("어떤 설비")))
                .andExpect(jsonPath("$.data[1].content").value(containsString("'최근'")))
                .andExpect(jsonPath("$.data[1].answerMeta.syncStatus").value("NONE"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.clarification.missing[0]").value("TARGET"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.clarification.missing[1]").value("PURPOSE"))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.clarification.missing[2]").value("PERIOD"))
                .andExpect(jsonPath("$.data[1].answerMeta.clarification.vaguePeriod").value("최근"));
    }

    @Test
    void fillsAskedConditionsWithReply() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("PR-03 10월 비가동 이력 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03"), null, null, List.of(), "최근"),
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));

        ask(conversation, "PR-03 최근 비가동 이력 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "10월 1일부터 31일까지").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        verify(interpreter).interpret(anyString(), anyMap(), notNull(), any(), any(), any());
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.clarification.missing[0]").value("PERIOD"))
                .andExpect(jsonPath("$.data[3].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[3].content").value("PR-03 10월 비가동 이력 답변"))
                .andExpect(jsonPath("$.data[3].answerMeta.clarification").doesNotExist())
                .andExpect(jsonPath("$.data[3].answerMeta.conditions.purpose").value("비가동 이력"))
                .andExpect(
                        jsonPath("$.data[3].answerMeta.conditions.period.from")
                                .value("2026-10-01"));
    }

    @Test
    void followUpKeepsPreviousTargetAndChangesOnlyPeriod() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("비가동 이력 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null),
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(),
                                null));

        // 같은 사용자의 다른 대화에 있는 질문은 이 대화의 최근 대화 원문에 섞이지 않아야 한다.
        long other = conversation(owner);
        jdbc.sql(
                        """
                        insert into agent_message(conversation_id, sender, content, created_at)
                        values (:conversation, 'USER', '다른 대화의 질문', now())
                        """)
                .param("conversation", other)
                .update();

        ask(conversation, "PR-03 10월 비가동 이력 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "그럼 지난달은?").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 두 번째 질문은 첫 답변에서 확정한 조건과 이 대화의 최근 대화 원문을 받아 해석하고, 대상은 다시 찾지 않는다(147).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(interpreter)
                .interpret(eq("그럼 지난달은?"), anyMap(), isNull(), notNull(), turns.capture(), any());
        assertThat(turns.getValue())
                .containsExactly(new ConversationTurn("PR-03 10월 비가동 이력 알려줘", "비가동 이력 답변", 1));
        verify(targetResolver, times(1)).resolve(any(), any(), any());
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.referenceObjects[0].id")
                                .value(302))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.referenceObjects[0].name")
                                .value("PR-03"))
                .andExpect(jsonPath("$.data[3].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[3].answerMeta.conditions.purpose").value("비가동 이력"))
                .andExpect(jsonPath("$.data[3].answerMeta.conditions.targets[0]").value("PR-03"))
                .andExpect(
                        jsonPath("$.data[3].answerMeta.conditions.referenceObjectIds[0]")
                                .value(302))
                .andExpect(
                        jsonPath("$.data[3].answerMeta.conditions.period.from")
                                .value("2026-09-01"));
    }

    @Test
    void followUpSkipsClarificationAndFailedAnswerWhenContinuing() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("비가동 이력 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03"), null, null, List.of(), "최근"))
                .thenThrow(new IllegalStateException("GMS 시간 초과"))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(),
                                null));

        ask(conversation, "PR-03 10월 비가동 이력 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "PR-03 최근 비가동 이력은?").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "10월 1일부터").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "그럼 지난달은?").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 되묻기(조건 일부만 확정)와 실패한 답변은 건너뛰고, 끝까지 답한 첫 답변의 조건을 이어 쓴다(147).
        ArgumentCaptor<QuestionInterpretation> previous =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(interpreter)
                .interpret(eq("그럼 지난달은?"), anyMap(), isNull(), previous.capture(), any(), any());
        assertThat(previous.getValue().from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(previous.getValue().vaguePeriod()).isNull();
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[3].answerMeta.clarification").exists())
                .andExpect(jsonPath("$.data[5].status").value("FAILED"))
                .andExpect(jsonPath("$.data[7].status").value("SUCCEEDED"))
                .andExpect(
                        jsonPath("$.data[7].answerMeta.conditions.period.from").value("2026-09-01"))
                .andExpect(
                        jsonPath("$.data[7].answerMeta.conditions.referenceObjectIds[0]")
                                .value(302));
    }

    @Test
    void outOfScopeQuestionKeepsPreviousBusinessConditions() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("비가동 이력 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null))
                .thenReturn(outsideBusiness(QuestionScope.OUT_OF_SCOPE))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(),
                                null));

        ask(conversation, "PR-03 10월 비가동 이력 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "오늘 날씨 어때?").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "그럼 지난달은?").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 업무 외 안내는 건너뛰고 첫 업무 답변의 조건을 이어 쓴다. 최근 대화에는 안내 문장 없이 질문만 들어간다(361).
        ArgumentCaptor<QuestionInterpretation> previous =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(interpreter)
                .interpret(
                        eq("그럼 지난달은?"),
                        anyMap(),
                        isNull(),
                        previous.capture(),
                        turns.capture(),
                        any());
        assertThat(previous.getValue().from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(turns.getValue())
                .extracting(ConversationTurn::question, ConversationTurn::answer)
                .containsExactly(
                        tuple("PR-03 10월 비가동 이력 알려줘", "비가동 이력 답변"), tuple("오늘 날씨 어때?", null));
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[3].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[3].answerMeta.scope").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data[3].answerMeta.conditions").doesNotExist())
                .andExpect(
                        jsonPath("$.data[5].answerMeta.conditions.period.from").value("2026-09-01"))
                .andExpect(jsonPath("$.data[5].answerMeta.conditions.purpose").value("비가동 이력"));
    }

    @Test
    void narrowingFollowUpKeepsListKindAndSavesPropertyConditions() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("작업지시 목록 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "작업지시 목록",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of("작업지시"),
                                QuestionScope.BUSINESS,
                                List.of("진행 중")))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of(),
                                QuestionScope.BUSINESS,
                                List.of("진행 중", "1라인")));

        ask(conversation, "진행 중인 작업지시 목록 보여줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "그중 1라인만").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 목록의 속성 조건을 남기고, "그중 1라인만"은 대상을 찾지 않고 같은 종류의 목록을 좁힌다(360).
        ArgumentCaptor<QuestionInterpretation> previous =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(interpreter)
                .interpret(eq("그중 1라인만"), anyMap(), isNull(), previous.capture(), any(), any());
        assertThat(previous.getValue().propertyValues()).containsExactly("진행 중");
        verify(targetResolver, never()).resolve(any(), any(), any());
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(
                        jsonPath("$.data[1].answerMeta.conditions.propertyValues[0]").value("진행 중"))
                .andExpect(jsonPath("$.data[3].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[3].answerMeta.conditions.kinds[0]").value("작업지시"))
                .andExpect(
                        jsonPath("$.data[3].answerMeta.conditions.propertyValues")
                                .value(contains("진행 중", "1라인")))
                .andExpect(jsonPath("$.data[3].answerMeta.conditions.purpose").value("작업지시 목록"));
    }

    @ParameterizedTest
    @EnumSource(
            value = QuestionScope.class,
            names = {"HELP", "OUT_OF_SCOPE", "PERSON_EVALUATION"})
    void clarificationContinuesAfterOutOfScopeQuestion(QuestionScope scope) throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("비가동 이력 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of(), null, null, List.of(), null))
                .thenReturn(outsideBusiness(scope))
                .thenReturn(
                        new QuestionInterpretation(
                                null, List.of("PR-03"), null, null, List.of(), null));

        ask(conversation, "비가동 이력 보여줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        String aside =
                switch (scope) {
                    case HELP -> "근데 너는 뭘 할 수 있어?";
                    case PERSON_EVALUATION -> "작업자별 근무 시간 보여줘";
                    default -> "오늘 날씨 어때?";
                };
        ask(conversation, aside).andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "PR-03").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 사용 안내를 끼워도 되묻기는 이어져, 마지막 말을 처음 되물은 질문(대상)에 대한 답으로 받는다(361).
        ArgumentCaptor<PendingClarification> pending =
                ArgumentCaptor.forClass(PendingClarification.class);
        verify(interpreter)
                .interpret(eq("PR-03"), anyMap(), pending.capture(), any(), any(), any());
        assertThat(pending.getValue()).isNotNull();
        assertThat(pending.getValue().previous().purpose()).isEqualTo("비가동 이력");
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[1].answerMeta.clarification").exists())
                .andExpect(jsonPath("$.data[3].answerMeta.scope").value(scope.name()))
                .andExpect(jsonPath("$.data[5].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[5].content").value("비가동 이력 답변"))
                .andExpect(jsonPath("$.data[5].answerMeta.clarification").doesNotExist())
                .andExpect(jsonPath("$.data[5].answerMeta.conditions.purpose").value("비가동 이력"))
                .andExpect(jsonPath("$.data[5].answerMeta.conditions.targets[0]").value("PR-03"));
    }

    @Test
    void personEvaluationQuestionsGetFixedSentenceWhileRecordLevelQuestionsAreAnswered()
            throws Exception {
        // 분류는 해석 AI가 한다(실제 분류는 PersonEvaluationScopeGmsTests). 여기서는 분류 뒤 서버가 막을 질문은 조회 없이 정해진 문장으로,
        // 통과시킬 질문은 평소처럼 대상을 찾아 답하는지 같은 예시 질문으로 본다(384-④).
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any())).thenReturn("담당자 답변");
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenAnswer(
                        call ->
                                PersonEvaluationExamples.BLOCKED.contains(call.getArgument(0))
                                        ? outsideBusiness(QuestionScope.PERSON_EVALUATION)
                                        : new QuestionInterpretation(
                                                "담당자",
                                                List.of("PR-03"),
                                                null,
                                                null,
                                                List.of(),
                                                null));
        List<String> questions = new ArrayList<>();
        questions.addAll(PersonEvaluationExamples.BLOCKED);
        questions.addAll(PersonEvaluationExamples.ALLOWED);

        for (String question : questions) {
            ask(conversation, question).andExpect(status().isAccepted());
            awaitAnswered(conversation);
        }

        ResultActions messages =
                mvc.perform(get(MESSAGES, conversation).param("size", "50").with(loggedIn(owner)))
                        .andExpect(jsonPath("$.data.length()").value(2 * questions.size()));
        for (int i = 0; i < questions.size(); i++) {
            String answer = "$.data[" + (2 * i + 1) + "]";
            if (PersonEvaluationExamples.BLOCKED.contains(questions.get(i))) {
                messages.andExpect(jsonPath(answer + ".status").value("SUCCEEDED"))
                        .andExpect(
                                jsonPath(answer + ".content")
                                        .value(containsString("개인을 평가하거나 감시하는 질문에는 답하지 않아요")))
                        .andExpect(
                                jsonPath(answer + ".answerMeta.scope").value("PERSON_EVALUATION"))
                        .andExpect(jsonPath(answer + ".answerMeta.results.length()").value(0))
                        .andExpect(jsonPath(answer + ".answerMeta.conditions").doesNotExist());
            } else {
                messages.andExpect(jsonPath(answer + ".status").value("SUCCEEDED"))
                        .andExpect(jsonPath(answer + ".content").value("담당자 답변"))
                        .andExpect(jsonPath(answer + ".answerMeta.scope").doesNotExist())
                        .andExpect(
                                jsonPath(answer + ".answerMeta.conditions.purpose").value("담당자"));
            }
        }
        // 막은 질문은 답변 AI(조회 도구)를 부르지 않는다. 통과한 질문만 부른다.
        for (String blocked : PersonEvaluationExamples.BLOCKED) {
            verify(responder, never()).answer(eq(blocked), any(), any(), any());
        }
        for (String allowed : PersonEvaluationExamples.ALLOWED) {
            verify(responder).answer(eq(allowed), any(), any(), any());
        }
    }

    @Test
    void clarificationStopsWhenOutsideBusinessAnswersPushItOutOfRecentMessages() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of(), null, null, List.of(), null))
                .thenReturn(outsideBusiness(QuestionScope.HELP))
                .thenReturn(outsideBusiness(QuestionScope.HELP))
                .thenReturn(outsideBusiness(QuestionScope.HELP))
                .thenReturn(outsideBusiness(QuestionScope.HELP))
                .thenReturn(outsideBusiness(QuestionScope.OUT_OF_SCOPE))
                .thenThrow(new IllegalStateException("해석만 확인한다"));

        ask(conversation, "비가동 이력 보여줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        for (int i = 1; i <= 5; i++) {
            ask(conversation, "딴 얘기 " + i).andExpect(status().isAccepted());
            awaitAnswered(conversation);
        }
        ask(conversation, "PR-03").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 이번 질문을 저장하기 전 최근 메시지 10건 안에서만 되묻기를 찾는다(361). 안내 네 번 뒤에는 이어 가고,
        // 다섯 번째 안내 뒤에는 되묻기가 10건 밖으로 밀려 새 질문으로 본다.
        verify(interpreter).interpret(eq("딴 얘기 5"), anyMap(), notNull(), any(), any(), any());
        verify(interpreter).interpret(eq("PR-03"), anyMap(), isNull(), any(), any(), any());
    }

    @Test
    void clarificationDoesNotContinueAfterFailedAnswer() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(responder.answer(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("GMS 시간 초과"));
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of(), null, null, List.of(), null))
                .thenReturn(
                        new QuestionInterpretation(
                                null, List.of("PR-03"), null, null, List.of(), null))
                .thenThrow(new IllegalStateException("해석만 확인한다"));

        ask(conversation, "비가동 이력 보여줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "PR-03").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        ask(conversation, "다시 PR-03").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 실패 답변은 안내 답변과 달리 건너뛰지 않는다. 되묻기에 답했다가 실패했으면 다음 말은 새 질문이다.
        verify(interpreter).interpret(eq("PR-03"), anyMap(), notNull(), any(), any(), any());
        verify(interpreter).interpret(eq("다시 PR-03"), anyMap(), isNull(), any(), any(), any());
        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[3].status").value("FAILED"));
    }

    private static QuestionInterpretation outsideBusiness(QuestionScope scope) {
        return new QuestionInterpretation(
                null, List.of(), null, null, List.of(), null, List.of(), scope);
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
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("GMS 시간 초과"));

        ask(conversation, "PR-03 상태 알려줘").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        mvc.perform(get(MESSAGES, conversation).with(loggedIn(owner)))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].errorMessage").value("AI 응답 실패"))
                .andExpect(jsonPath("$.data[1].answerMeta.syncStatus").value("NONE"));
    }

    @Test
    void passesStoredOlderSummaryOfThisConversationToInterpreter() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("해석만 확인한다"));
        jdbc.sql(
                        """
                        update agent_conversation
                           set summary = '처음에 PR-06 비가동 이력을 물었다.', summarized_until_message_id = 1
                         where id = :id
                        """)
                .param("id", conversation)
                .update();

        ask(conversation, "처음에 물어본 설비는?").andExpect(status().isAccepted());
        awaitAnswered(conversation);

        // 앞 답변 뒤에 저장한 오래된 대화 요약을 질문 해석에 넘긴다(333).
        verify(interpreter)
                .interpret(
                        eq("처음에 물어본 설비는?"),
                        anyMap(),
                        any(),
                        any(),
                        any(),
                        eq("처음에 PR-06 비가동 이력을 물었다."));
    }

    @Test
    void numbersRecentTurnsByQuestionsOfWholeConversation() throws Exception {
        // 1 P101 → 2 P103(되묻기) → "번호 306"(2번째 질문의 답) → 3 B101 → 4 I101 → 5 W201
        turn(conversation, "P101 상세", "P101 답변", false);
        turn(conversation, "P103 상태", "P103이 여러 개입니다.", true);
        turn(conversation, "번호 306", "P103 상태 답변", false);
        turn(conversation, "B101 상세", "B101 답변", false);
        turn(conversation, "I101 상세", "I101 답변", false);
        turn(conversation, "W201 상세", "W201 답변", false);

        // 원문 4턴은 "번호 306"부터다. 첫 턴이 되묻기에 대한 답이라 되물은 질문과 같은 2번째다(333).
        assertThat(recentTurnOrdersOfNextQuestion()).containsExactly(2, 3, 4, 5);
    }

    @Test
    void keepsSameOrderForClarifyingReplyInsideRecentTurns() throws Exception {
        // 1 P101 → 2 P103 → 3 B101 → 4 I101(되묻기) → "번호 4"(4번째 질문의 답) → 5 W201
        turn(conversation, "P101 상세", "P101 답변", false);
        turn(conversation, "P103 상세", "P103 답변", false);
        turn(conversation, "B101 상세", "B101 답변", false);
        turn(conversation, "I101 상세", "I101이 여러 개입니다.", true);
        turn(conversation, "번호 4", "I101 답변", false);
        turn(conversation, "W201 상세", "W201 답변", false);

        assertThat(recentTurnOrdersOfNextQuestion()).containsExactly(3, 4, 4, 5);
    }

    /** 다음 질문의 해석에 넘긴 최근 대화 원문의 순번. 해석에서 멈추게 해 답변은 만들지 않는다. */
    private List<Integer> recentTurnOrdersOfNextQuestion() throws Exception {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(anyString(), anyMap(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("해석만 확인한다"));
        ask(conversation, "두 번째로 물어본 설비는?").andExpect(status().isAccepted());
        awaitAnswered(conversation);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(interpreter)
                .interpret(eq("두 번째로 물어본 설비는?"), anyMap(), any(), any(), turns.capture(), any());
        return turns.getValue().stream().map(ConversationTurn::order).toList();
    }

    /** 끝난 질문·답변 한 턴을 넣는다. clarified면 답변이 되묻기다(답변 메타에 clarification). */
    private void turn(long conversationId, String question, String answer, boolean clarified) {
        jdbc.sql(
                        """
                        insert into agent_message(conversation_id, sender, content, created_at)
                        values (:conversation, 'USER', :question, now())
                        """)
                .param("conversation", conversationId)
                .param("question", question)
                .update();
        jdbc.sql(
                        """
                        insert into agent_message(conversation_id, sender, content, status, answer_meta,
                                                  created_at)
                        values (:conversation, 'AGENT', :answer, 'SUCCEEDED', cast(:meta as jsonb), now())
                        """)
                .param("conversation", conversationId)
                .param("answer", answer)
                .param("meta", clarified ? "{\"clarification\":{\"missing\":[\"TARGET\"]}}" : "{}")
                .update();
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

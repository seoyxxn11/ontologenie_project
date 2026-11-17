package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.SyncTaskExecutor;

class AgentAnswerProcessorTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    // 설비 상태의 저장 값 종류. 서버 안내는 값 종류를 아는 속성으로 거른 값만 비교한다.
    private static final AgentObjectListTools.ObjectList EQUIPMENT_STATUS =
            new AgentObjectListTools.ObjectList(
                    1,
                    0,
                    false,
                    List.of(),
                    List.of(
                            new AgentObjectListTools.PropertyItem(
                                    "status", "상태", PropertyDataType.STRING, List.of("가동", "정비중"))),
                    Map.of());
    private static final AgentAnswerRequested EVENT =
            new AgentAnswerRequested(
                    900L, 899L, ASKER, "PR-03 작업을 PR-01로 옮겨줘", Map.of(), null, null);
    private static final QuestionInterpretation INTERPRETATION =
            new QuestionInterpretation("재배정", List.of("PR-03"), null, null, List.of(), null);
    private static final AgentActionOutcome PLAN_CREATED =
            new AgentActionOutcome(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "SUCCEEDED",
                    512L,
                    Map.of("planId", 3, "itemCount", 1),
                    null);

    private final AgentAnswerService answers = mock(AgentAnswerService.class);
    private final QuestionInterpreter interpreter = mock(QuestionInterpreter.class);
    private final AgentResponder responder = mock(AgentResponder.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentTargetResolver targetResolver = mock(AgentTargetResolver.class);
    private final ConversationSummaries summaries = mock(ConversationSummaries.class);
    private final AgentAnswerProcessor processor =
            new AgentAnswerProcessor(
                    answers,
                    interpreter,
                    responder,
                    recorder,
                    targetResolver,
                    Clock.systemUTC(),
                    new SyncTaskExecutor(),
                    mock(DataAsOfResolver.class),
                    summaries);

    @Test
    void answersOutsideBusinessWithoutLookingUpOrAskingBack() {
        when(interpreter.isConfigured()).thenReturn(true);
        for (QuestionScope scope :
                List.of(
                        QuestionScope.OUT_OF_SCOPE,
                        QuestionScope.HELP,
                        QuestionScope.PERSON_EVALUATION)) {
            // 업무 외 질문에서 AI가 대상 같은 값을 잘못 뽑아도 찾거나 되묻지 않는다.
            when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                    .thenReturn(
                            new QuestionInterpretation(
                                    null,
                                    List.of("서울"),
                                    null,
                                    null,
                                    List.of(),
                                    null,
                                    List.of(),
                                    scope));

            processor.onRequested(EVENT);

            verify(answers).answerOutsideBusiness(900L, scope);
        }
        // 업무 외 질문은 정해진 문장으로 끝내 조회 도구와 답변 AI를 부르지 않는다(업무 외 질문 가드레일).
        verify(targetResolver, never()).resolve(any(), any(), any());
        verify(responder, never()).answer(any(), any(), any(), any());
        verify(answers, never()).answerClarification(anyLong(), any(), any(), any());
        verify(answers, never()).answerWithResults(anyLong(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void passesOlderSummaryToInterpreterAndRefreshesItAfterAnswering() {
        when(summaries.enabled()).thenReturn(true);
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(INTERPRETATION);
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(
                        new AgentTargetResolver.Resolution(
                                List.of(new AgentReferenceObject(301L, "PR-03", "설비")), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("답변");

        processor.onRequested(withSummary("처음에 PR-03 비가동 이력을 물었다."));

        // 오래된 대화 요약은 질문 해석에만 넘기고, 답변을 저장한 뒤에 다음 질문을 위한 요약을 갱신한다(333).
        InOrder order = inOrder(interpreter, answers, summaries);
        order.verify(interpreter)
                .interpret(any(), any(), any(), any(), any(), eq("처음에 PR-03 비가동 이력을 물었다."));
        order.verify(answers).answerWithResults(eq(900L), any(), any(), any(), any(), anyInt());
        order.verify(summaries).refreshAfter(900L);
    }

    @Test
    void doesNotPassStoredSummaryWhenSummaryIsTurnedOff() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("AI 응답 실패"));

        processor.onRequested(withSummary("예전에 저장한 요약"));

        // 요약을 껐으면(summary-max-tokens 0) 저장돼 있던 요약도 넘기지 않는다. 답변이 실패해도 요약 갱신은 부른다(갱신 쪽에서 끈 것을 본다).
        verify(interpreter).interpret(any(), any(), any(), any(), any(), isNull());
        verify(answers).fail(eq(900L), anyString(), anyString());
        verify(summaries).refreshAfter(900L);
    }

    private static AgentAnswerRequested withSummary(String summary) {
        return new AgentAnswerRequested(
                900L, 899L, ASKER, EVENT.question(), Map.of(), null, null, List.of(), summary);
    }

    @Test
    void keepsActionAlreadyRunWhenAiFailsAfterward() {
        interpretAs(INTERPRETATION);
        // AI가 계획을 만든 뒤 답변 문장을 쓰다가 실패한 경우
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.addAction(PLAN_CREATED);
                            throw new IllegalStateException("AI 응답 시간 초과");
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        verify(answers).failWithResults(900L, List.of(), List.of(PLAN_CREATED), 0);
        verify(answers, never()).fail(anyLong(), anyString(), anyString());
    }

    @Test
    void dropsQueryResultsThatWereNotRecorded() {
        interpretAs(INTERPRETATION);
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());
        // 계획을 만든 뒤 조회했는데 그 조회 기록을 남기지 못한 경우
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.addAction(PLAN_CREATED);
                            return session.run(
                                    "OBJECT_EXECUTIONS",
                                    "실행 이력",
                                    Map.of("objectId", 301L),
                                    "object_instance",
                                    301L,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    List.of(),
                                                    AgentResultCategory.NO_DATA,
                                                    List.of(),
                                                    null));
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        // 기록 없는 조회 결과는 보여 주지 않고, 실행기가 따로 기록한 Action만 남긴다(160).
        verify(answers).failWithResults(900L, List.of(), List.of(PLAN_CREATED), 0);
    }

    @Test
    void failsWithoutResultsWhenQuestionCannotBeInterpreted() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("AI 응답 시간 초과"));

        processor.onRequested(EVENT);

        verify(answers)
                .fail(900L, AgentAnswerService.FAILED_CONTENT, AgentAnswerService.FAILED_REASON);
        verify(answers, never()).failWithResults(anyLong(), any(), any(), anyInt());
    }

    @Test
    void continuesPreviousTargetWhenOnlyPeriodChanges() {
        AgentAnswerRequested followUp = followUp("그럼 지난달은?");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(),
                                null));
        when(responder.answer(any(), any(), any(), any())).thenReturn("9월 비가동은 1건입니다.");

        processor.onRequested(followUp);

        // 대상은 다시 찾지 않고 이전 객체를 그대로 쓰며, 기간만 바뀐다.
        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.targets()).containsExactly("PR-03");
        assertThat(used.referenceObjectIds()).containsExactly(301L);
        assertThat(used.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(used.to()).isEqualTo(LocalDate.parse("2026-09-30"));
        // 되묻기에 대한 답이 아니므로 되물은 내용은 넘기지 않는다.
        verify(responder).answer(eq("그럼 지난달은?"), any(), any(), isNull());
    }

    @Test
    void keepsPreviousEquipmentAndQueriesWhenReasonIsAlsoExtractedAsTarget() {
        AgentAnswerRequested followUp = followUp("사유가 가이드 마모인 것만 보여줘");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        QuestionInterpreter.verify(
                                new QuestionInterpreter.Extracted(
                                        "가이드 마모 비가동 이력",
                                        List.of("가이드 마모"),
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "BUSINESS",
                                        List.of("가이드 마모")),
                                List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("가이드 마모 비가동 기록입니다.");

        processor.onRequested(followUp);

        verify(targetResolver, never()).resolve(any(), any(), any());
        verify(answers, never()).answerClarification(anyLong(), any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.targets()).containsExactly("PR-03");
        assertThat(used.referenceObjectIds()).containsExactly(301L);
        assertThat(used.from()).isEqualTo(followUp.previous().from());
        assertThat(used.to()).isEqualTo(followUp.previous().to());
        assertThat(used.purpose()).isEqualTo("가이드 마모 비가동 이력");
        verify(responder).answer(eq(followUp.question()), eq(used), any(), isNull());
    }

    @Test
    void asksAboutUnknownExplicitEquipmentEvenWhenReasonIsKnown() {
        AgentAnswerRequested followUp = followUp("P999에서 가이드 마모가 발생한 이력은?");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        QuestionInterpreter.verify(
                                new QuestionInterpreter.Extracted(
                                        "가이드 마모 비가동 이력",
                                        List.of("P999", "가이드 마모"),
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "BUSINESS",
                                        List.of("가이드 마모")),
                                List.of()));
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(new AgentTargetResolver.TargetIssue("P999", List.of()));
        when(targetResolver.resolve(eq(List.of("P999")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), issues));

        processor.onRequested(followUp);

        ArgumentCaptor<QuestionInterpretation> used =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(answers).answerClarification(eq(900L), used.capture(), eq(issues), any());
        assertThat(used.getValue().targets()).containsExactly("P999");
        assertThat(used.getValue().referenceObjects()).isEmpty();
        verify(responder, never()).answer(any(), any(), any(), any());
    }

    @Test
    void switchesToNewTargetInsteadOfPreviousObject() {
        AgentAnswerRequested followUp = followUp("06번의 실행 이력은?");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "실행 이력", List.of("06번"), null, null, List.of(), null));
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        when(targetResolver.resolve(eq(List.of("06번")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("PR-06 실행 이력은 없습니다.");

        processor.onRequested(followUp);

        // 다른 대상을 물었으므로 처음 객체(301)를 함께 넘기지 않는다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.referenceObjects()).containsExactly(pr06);
        assertThat(used.targets()).containsExactly("06번");
    }

    @Test
    void letsToolsQueryOnlyObjectsThisAnswerTargets() {
        when(interpreter.isConfigured()).thenReturn(true);
        // "현재 진행 중인 작업지시를 보여줘"처럼 종류 전체를 물어 대상 객체가 없는 질문
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "진행 중인 작업지시",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of("작업지시")));
        List<Map<String, Object>> replies = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            // AI가 대상이 없는데 객체 0의 실행 이력을 부른 경우(로컬 시험에서 본 경우)
                            replies.add(
                                    session.run(
                                            "OBJECT_EXECUTIONS",
                                            "실행 이력",
                                            Map.of("objectId", 0L),
                                            "object_instance",
                                            0L,
                                            () -> {
                                                throw new AssertionError("조회하면 안 된다");
                                            }));
                            return "작업지시 목록은 아직 조회할 수 없습니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        // 조회하지 않고 이유를 돌려주어, "데이터 없음"으로 답하지 않게 한다. 정보별 결과에도 남기지 않는다.
        assertThat(replies).containsExactly(Map.of("error", AgentQuerySession.NOT_TARGET));
        verify(answers).answerWithResults(eq(900L), any(), any(), eq(List.of()), any(), anyInt());
    }

    @Test
    void letsToolsQueryObjectsFoundInThisAnswerOnly() {
        interpretAs(INTERPRETATION);
        List<Map<String, Object>> replies = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            // PR-03(301)의 관계에서 연결된 금형 415를 찾고 그 금형의 상세를 본다.
                            session.run(
                                    "OBJECT_LINKS",
                                    "관계",
                                    Map.of("objectId", 301L),
                                    "object_instance",
                                    301L,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    List.of(),
                                                    AgentResultCategory.FOUND,
                                                    List.of(
                                                            Map.of("objectId", 301L),
                                                            Map.of("objectId", 415L)),
                                                    null));
                            replies.add(detail(session, 415L));
                            return "금형 상태 답변";
                        })
                .doAnswer(
                        invocation -> {
                            // 다음 답변에서 관계 조회 없이 같은 금형을 부른 경우
                            replies.add(detail(invocation.getArgument(2), 415L));
                            return "금형 상태 답변";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);
        processor.onRequested(EVENT);

        // 도구 결과로 허용한 객체는 그 답변에서만 조회할 수 있다(326).
        assertThat(replies.get(0)).containsEntry("resultCategory", "FOUND");
        assertThat(replies.get(1)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
    }

    @Test
    void tellsWhenListWasFilteredByDifferentStoredValue() {
        // "정지 중인 설비"에 저장 값 "정비중"으로 찾고도 답변 AI가 밝히지 않았다(360 L2). 서버가 비교해 답 끝에 알린다.
        interpretAs(
                new QuestionInterpretation(
                        "정지 중인 설비",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of("설비"),
                        QuestionScope.BUSINESS,
                        List.of("정지 중")));
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.run(
                                    "OBJECT_LIST",
                                    "객체 목록",
                                    Map.of(
                                            "objectTypeId",
                                            3L,
                                            "propertyValues",
                                            Map.of("status", "정비중")),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    EQUIPMENT_STATUS,
                                                    AgentResultCategory.FOUND,
                                                    List.of(),
                                                    null));
                            return "정지 중인 설비는 1라인 3호 프레스(정비중) 1대입니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(answers)
                .answerWithResults(eq(900L), content.capture(), any(), any(), any(), anyInt());
        assertThat(content.getValue())
                .isEqualTo(
                        "정지 중인 설비는 1라인 3호 프레스(정비중) 1대입니다.\n\n"
                                + "'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    @Test
    void doesNotTellWhenOtherConditionsAlsoDiffer() {
        // "정지 중인 설비의 비가동 이력": 설비는 상태 "정비중", 비가동은 설비 코드 "P103"으로 걸렀다. 설비가 20대 이하라 설비 코드에도 값 종류가
        // 온다. "'정지 중'… '정비중', 'P103'으로 찾았어요"를 붙이지 않는다(2026-11-16 MR 전 리뷰).
        interpretAs(
                new QuestionInterpretation(
                        "정지 중인 설비의 비가동 이력",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of("설비", "비가동"),
                        QuestionScope.BUSINESS,
                        List.of("정지 중")));
        AgentObjectListTools.ObjectList downtime =
                new AgentObjectListTools.ObjectList(
                        2,
                        0,
                        false,
                        List.of(),
                        List.of(
                                new AgentObjectListTools.PropertyItem(
                                        "equipment_code",
                                        "설비 코드",
                                        PropertyDataType.STRING,
                                        List.of("P101", "P103"))),
                        Map.of());
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.run(
                                    "OBJECT_LIST",
                                    "객체 목록",
                                    Map.of(
                                            "objectTypeId",
                                            3L,
                                            "propertyValues",
                                            Map.of("status", "정비중")),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    EQUIPMENT_STATUS,
                                                    AgentResultCategory.FOUND,
                                                    List.of(),
                                                    null));
                            session.run(
                                    "OBJECT_LIST",
                                    "객체 목록",
                                    Map.of(
                                            "objectTypeId",
                                            5L,
                                            "propertyValues",
                                            Map.of("equipment_code", "P103")),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    downtime,
                                                    AgentResultCategory.FOUND,
                                                    List.of(),
                                                    null));
                            return "P103(정비중)의 비가동 이력은 2건입니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        verify(answers)
                .answerWithResults(
                        eq(900L), eq("P103(정비중)의 비가동 이력은 2건입니다."), any(), any(), any(), anyInt());
    }

    @Test
    void doesNotTellAboutStatusCarriedOverFromPreviousQuestion() {
        // "지금 정지 중인 설비 있어?" 다음 "그 설비 비가동 이력은?"은 앞 질문의 종류·상태를 이어받는다. 이번 조회를 설비 코드로 걸러도
        // "'정지 중'… 'P103'으로 찾았어요"가 붙지 않아야 한다(2026-11-16 리뷰).
        QuestionInterpretation previous =
                new QuestionInterpretation(
                        "정지 중인 설비",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of("설비"),
                        QuestionScope.BUSINESS,
                        List.of("정지 중"));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of(), null, null, List.of(), null, List.of()));
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.run(
                                    "OBJECT_LIST",
                                    "객체 목록",
                                    Map.of(
                                            "objectTypeId",
                                            5L,
                                            "propertyValues",
                                            Map.of("equipment_code", "P103")),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    List.of(),
                                                    AgentResultCategory.FOUND,
                                                    List.of(),
                                                    null));
                            return "P103의 비가동 이력은 2건입니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "그 설비 비가동 이력은?", Map.of(), null, previous));

        // 답변에 넘긴 해석은 앞 질문의 상태를 이어받았지만 안내는 붙지 않는다.
        assertThat(answeredWith().propertyValues()).containsExactly("정지 중");
        verify(answers)
                .answerWithResults(
                        eq(900L), eq("P103의 비가동 이력은 2건입니다."), any(), any(), any(), anyInt());
    }

    @Test
    void tellsAboutStatusWhenReplyFinishesClarifiedListQuestion() {
        // "최근 정지 중인 설비"에 기간을 되물었고 "10월"로 답했다. 되묻기의 답은 같은 질문을 마무리하므로 합친 해석의 "정지 중"이 이번 질문의 값이다.
        PendingClarification pending =
                new PendingClarification(
                        "어느 기간을 볼까요?",
                        new QuestionInterpretation(
                                "정지 중인 설비",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                "최근",
                                List.of("설비"),
                                QuestionScope.BUSINESS,
                                List.of("정지 중")));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.run(
                                    "OBJECT_LIST",
                                    "객체 목록",
                                    Map.of(
                                            "objectTypeId",
                                            3L,
                                            "propertyValues",
                                            Map.of("status", "정비중")),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    EQUIPMENT_STATUS,
                                                    AgentResultCategory.FOUND,
                                                    List.of(),
                                                    null));
                            return "10월에 정비중이었던 설비는 P103입니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(
                new AgentAnswerRequested(900L, 899L, ASKER, "10월", Map.of(), pending, null));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(answers)
                .answerWithResults(eq(900L), content.capture(), any(), any(), any(), anyInt());
        assertThat(content.getValue())
                .endsWith("'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    @Test
    void letsWholeKindQuestionQueryObjectsFoundInThisAnswer() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 현황", List.of(), null, null, List.of(), null, List.of("설비")));
        List<Map<String, Object>> replies = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.run(
                                    "OBJECT_SEARCH",
                                    "객체 검색",
                                    Map.of("q", "설비"),
                                    null,
                                    null,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    List.of(),
                                                    AgentResultCategory.FOUND,
                                                    List.of(Map.of("objectId", 415L)),
                                                    null));
                            replies.add(detail(session, 415L));
                            replies.add(detail(session, 0L));
                            return "P103은 비가동 중입니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        // 종류 전체 질문도 이번 답변의 도구 결과에 나온 객체는 조회한다. "미착수 수주"처럼 목록의 객체에서 관계를 따라가야 답할 수 있다.
        // 결과에 없던 ID는 계속 막는다(326). 하나씩 끝없이 조회하는 것은 조회 횟수로 막는다.
        assertThat(replies.get(0)).containsEntry("resultCategory", "FOUND");
        assertThat(replies.get(1)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
    }

    @Test
    void asksBackWhenTargetMatchesSeveralObjects() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "실행 이력", List.of("06번"), null, null, List.of(), null));
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(
                        new AgentTargetResolver.TargetIssue(
                                "06번",
                                List.of(
                                        new AgentReferenceObject(306L, "PR-06", "설비"),
                                        new AgentReferenceObject(406L, "CNC-06", "설비"))));
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), issues));

        processor.onRequested(EVENT);

        verify(answers).answerClarification(eq(900L), any(), eq(issues), any());
        verify(responder, never()).answer(any(), any(), any(), any());
    }

    @Test
    void keepsSettledObjectWhenAnsweringOnlyTheAskedTarget() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        // "PR-03과 06번 비교" 중 06번만 되물었고, 사용자가 "PR-06"이라고 답했다.
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비교", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of(pr06));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null, List.of("PR-06"), null, null, List.of(), null));
        when(targetResolver.resolve(eq(List.of("PR-06")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("비교 답변");

        processor.onRequested(
                new AgentAnswerRequested(900L, 899L, ASKER, "PR-06", Map.of(), pending, null));

        QuestionInterpretation used = answeredWith();
        assertThat(used.targets()).containsExactly("PR-03", "PR-06");
        assertThat(used.referenceObjects()).containsExactly(pr03, pr06);
        assertThat(used.purpose()).isEqualTo("비교");
        // 답변 AI가 "PR-06"을 새 질문이 아니라 되물은 것에 대한 답으로 읽게 되물은 내용을 함께 넘긴다.
        verify(responder).answer(eq("PR-06"), any(), any(), eq("'06번'에 해당하는 대상이 여러 개입니다."));
    }

    @Test
    void asksAgainForUnsettledTargetWhenReplyGivesOnlyPeriod() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비교", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of());
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        List<AgentTargetResolver.TargetIssue> stillOpen =
                List.of(new AgentTargetResolver.TargetIssue("06번", List.of()));
        when(targetResolver.resolve(eq(List.of("06번")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), stillOpen));

        processor.onRequested(
                new AgentAnswerRequested(900L, 899L, ASKER, "10월", Map.of(), pending, null));

        // 정하지 못한 06번을 빼고 PR-03만으로 답하지 않고 다시 묻는다. 정한 PR-03은 남긴다.
        ArgumentCaptor<QuestionInterpretation> asked =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(answers).answerClarification(eq(900L), asked.capture(), eq(stillOpen), any());
        assertThat(asked.getValue().referenceObjects()).containsExactly(pr03);
        assertThat(asked.getValue().hasPeriod()).isTrue();
        verify(responder, never()).answer(any(), any(), any(), any());
    }

    @Test
    void keepsScreenObjectForNextReplyWhenFirstQuestionTargetIsUnsettled() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "작업 내용",
                                List.of("WO-1"),
                                null,
                                null,
                                List.of(AgentReferenceObject.idOnly(301L)),
                                null));
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(new AgentTargetResolver.TargetIssue("WO-1", List.of()));
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), issues));

        // Explorer 객체 301 화면에서 처음 질문했다.
        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        "이 설비의 WO-1 작업 알려줘",
                        Map.of("referenceObjectIds", List.of(301)),
                        null,
                        null));

        // 화면 객체는 첫 질문에만 오므로, 되물을 때 다음 답에서 먼저 고를 객체로 남긴다.
        verify(answers)
                .answerClarification(
                        eq(900L),
                        any(),
                        eq(issues),
                        eq(List.of(AgentReferenceObject.idOnly(301L))));
    }

    /** PR-03을 찾지 못하고 "최근"의 범위도 몰라 되물은 상태(로컬 시험에서 본 경우). */
    private static PendingClarification unfoundPr03() {
        return new PendingClarification(
                "'PR-03'에 해당하는 대상을 찾지 못했습니다.",
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근"),
                List.of("PR-03"),
                List.of());
    }

    @Test
    void treatsNewQuestionAfterClarificationAsNewQuestion() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "진행 중인 작업지시",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of("작업지시")));
        when(responder.answer(any(), any(), any(), any())).thenReturn("진행 중인 작업지시 답변");

        // 되묻기 전에 끝까지 답한 답변(10월 PR-03 비가동 이력)도 있다.
        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        "현재 진행 중인 작업지시를 보여줘",
                        Map.of(),
                        unfoundPr03(),
                        followUp("").previous()));

        // 되묻기에 답하지 않고 다른 목적을 새로 물었으므로, 찾지 못한 PR-03을 다시 찾거나 "최근"을 다시 묻지 않는다.
        // 예전 답변의 기간(10월)도 몰래 이어 쓰지 않는다.
        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.kinds()).containsExactly("작업지시");
        assertThat(used.targets()).isEmpty();
        assertThat(used.vaguePeriod()).isNull();
        assertThat(used.hasPeriod()).isFalse();
        // 새 질문이므로 답변 AI에 되물은 내용을 넘기지 않는다. 넘기면 새 질문을 되묻기에 대한 답으로 읽는다.
        verify(responder).answer(eq("현재 진행 중인 작업지시를 보여줘"), any(), any(), isNull());
    }

    @Test
    void treatsPurposeAndTargetAsReplyWhenBothWereAsked() {
        // "지난달 거 보여줘"에 목적과 대상을 되물었다. 기간(지난달)은 그때 정했다.
        PendingClarification pending =
                new PendingClarification(
                        "질문을 정확히 이해하려면 몇 가지를 더 알려 주세요.",
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        // 그 전에 끝까지 답한 답변은 9월 이야기였다.
        QuestionInterpretation older =
                new QuestionInterpretation(
                        "생산 실적",
                        List.of("PR-01"),
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-30"),
                        List.of(new AgentReferenceObject(303L, "PR-01", "설비")),
                        null);
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03"), null, null, List.of(), null));
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        when(targetResolver.resolve(eq(List.of("PR-03")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr03), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("PR-03 10월 비가동 이력 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "PR-03 비가동 이력", Map.of(), pending, older));

        // 물은 대로 목적과 대상을 답했으므로 되묻기에 대한 답이다. 되물을 때 정한 기간(지난달)을 쓴다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.referenceObjects()).containsExactly(pr03);
        assertThat(used.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(used.to()).isEqualTo(LocalDate.parse("2026-10-31"));
    }

    @Test
    void keepsSettledObjectWhenReplyRepeatsSamePurposeWithAskedTarget() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        // "PR-03과 06번 비가동 이력"에서 06번만 되물었다.
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of(pr06));
        when(interpreter.isConfigured()).thenReturn(true);
        // 사용자가 목적까지 다시 말해 답했다: "PR-06 비가동 이력 보여줘"
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "최근 비가동 이력", List.of("PR-06"), null, null, List.of(), null));
        when(targetResolver.resolve(eq(List.of("PR-06")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("비교 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "PR-06 비가동 이력 보여줘", Map.of(), pending, null));

        // 같은 목적을 다시 말한 것은 되묻기에 대한 답이다. 되물은 06번만 PR-06으로 바꾸고 PR-03은 남긴다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.targets()).containsExactly("PR-03", "PR-06");
        assertThat(used.referenceObjects()).containsExactly(pr03, pr06);
    }

    @Test
    void answersWithWholeKindWhenReplyNamesKindInsteadOfTarget() {
        when(interpreter.isConfigured()).thenReturn(true);
        // "그냥 전체 설비로 보여줘, 10월" — 목적은 말하지 않았으므로 되묻기에 대한 답이다.
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null,
                                List.of("설비")));
        when(responder.answer(any(), any(), any(), any())).thenReturn("전체 설비 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "그냥 전체 설비로, 10월", Map.of(), unfoundPr03(), null));

        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.targets()).isEmpty();
        assertThat(used.kinds()).containsExactly("설비");
        assertThat(used.hasPeriod()).isTrue();
    }

    @Test
    void passesRecentTurnsToQuestionInterpreter() {
        List<ConversationTurn> turns =
                List.of(new ConversationTurn("PR-03 실행 이력 알려줘", "PR-03 실행 이력은 3건입니다."));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        when(responder.answer(any(), any(), any(), any())).thenReturn("10월 실행 이력 답변");
        AgentAnswerRequested followUp = followUp("그럼 10월 것만");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        followUp.question(),
                        Map.of(),
                        null,
                        followUp.previous(),
                        turns));

        // 해석 AI는 받은 최근 대화 원문을 그대로 받는다(147).
        verify(interpreter).interpret(any(), any(), any(), any(), eq(turns), any());
    }

    /** 직전 답변에서 PR-03(객체 301)의 10월 비가동 이력을 답한 대화의 후속 질문. */
    private static AgentAnswerRequested followUp(String question) {
        QuestionInterpretation previous =
                new QuestionInterpretation(
                        "비가동 이력",
                        List.of("PR-03"),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(new AgentReferenceObject(301L, "PR-03", "설비")),
                        null);
        return new AgentAnswerRequested(900L, 899L, ASKER, question, Map.of(), null, previous);
    }

    private QuestionInterpretation answeredWith() {
        ArgumentCaptor<QuestionInterpretation> used =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(responder).answer(any(), used.capture(), any(), any());
        verify(answers)
                .answerWithResults(eq(900L), any(), eq(used.getValue()), any(), any(), anyInt());
        return used.getValue();
    }

    private void interpretAs(QuestionInterpretation interpretation) {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(EVENT.question(), EVENT.context(), null, null, List.of(), null))
                .thenReturn(interpretation);
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(
                        new AgentTargetResolver.Resolution(
                                List.of(new AgentReferenceObject(301L, "PR-03", "설비")), List.of()));
    }

    private static Map<String, Object> detail(AgentQuerySession session, long objectId) {
        return session.run(
                "OBJECT_DETAIL",
                "객체 상세",
                Map.of("objectId", objectId),
                "object_instance",
                objectId,
                () ->
                        new AgentQuerySession.Outcome(
                                Map.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", objectId)),
                                null));
    }
}

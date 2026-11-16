package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AgentResponderTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T05:40:12Z"), ZoneOffset.UTC);

    private final ChatModel chatModel = mock(ChatModel.class);
    private final EquipmentCapabilityComparer comparer = mock(EquipmentCapabilityComparer.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private boolean objectSetsEnabled;
    private boolean objectSetFindEnabled;
    private String answer;

    /**
     * 모델이 도구를 끝없이 부르면 이번 답변의 도구 호출 한도가 먼저 걸려 "지금까지 결과로 답하라"고 돌려준다. 그래도 계속 부르면 Spring AI 한도에 닿는데,
     * Spring AI는 이때 영어 안내를 본문으로 한 응답을 돌려준다. 본문만 읽으면 그 안내가 정상 답변으로 저장됐다(Q12). 종료 사유로 알아채 실패로 끝낸다.
     */
    @Test
    void failsInsteadOfAnsweringWithTheLibraryNoticeWhenTheModelKeepsCallingTools() {
        AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("gpt-5.6-sol").build());
        AssistantMessage callAgain =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call",
                                                "function",
                                                AgentModelSummaryTools.MODEL_SUMMARY,
                                                "{}")))
                        .build();
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(invocation -> new ChatResponse(List.of(new Generation(callAgain))));
        AgentQuerySession session = new AgentQuerySession(ASKER, 11L, recorder, CLOCK);

        assertThatThrownBy(
                        () ->
                                responder("none")
                                        .answer(
                                                "설비 유형 알려줘",
                                                new QuestionInterpretation(
                                                        "유형", List.of(), null, null, List.of(),
                                                        null),
                                                session,
                                                null))
                .isInstanceOf(AgentResponder.ToolCallLimitReached.class);

        // 한도를 넘은 호출은 부르지 않고 이유를 실패 조회로 기록했다. Spring AI 한도에 닿은 마지막 호출은 실행되지 않는다.
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, times(AgentResponder.MAX_CALLS_PER_TOOL))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), recorded.capture(), any());
        assertThat(recorded.getAllValues())
                .filteredOn(
                        result -> AgentQuerySession.TOOL_CALL_LIMIT.equals(result.errorMessage()))
                .hasSize(AgentResponder.MAX_CALLS_PER_TOOL - AgentQuerySession.MAX_TOOL_CALLS);
        verify(chatModel, times(AgentResponder.MAX_CALLS_PER_TOOL + 1)).call(any(Prompt.class));
    }

    @Test
    void turnsOffReasoningOnlyForToolRequest() {
        Prompt prompt = askAboutPr03();

        assertThat(answer).isEqualTo("실행 이력이 없습니다.");
        assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        // GMS chat/completions는 도구와 추론을 함께 쓰는 것을 거절하므로 기본값으로 추론을 끈다(설정
        // axcore.agent.reasoning-effort, 147).
        assertThat(options.getReasoningEffort()).isEqualTo("none");
        assertThat(options.getModel()).isEqualTo("gpt-5.6-sol");
        assertThat(((ToolCallingChatOptions) options).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(AgentQueryTools.OBJECT_EXECUTIONS, AgentObjectListTools.OBJECT_LIST);
    }

    @Test
    void tellsCurrentTimeInUserZone() {
        Prompt prompt = askAboutPr03();

        // AI는 지금 시각을 모르므로 한국 시각으로 알려 준다. "지금"을 0시로 지어내지 않게 한다(160).
        assertThat(prompt.getUserMessage().getText()).contains("현재 시각: 2026-11-09T14:40:12+09:00");
        // 계획의 기준 시점은 현재 시각보다 조회한 데이터의 시점을 먼저 쓰게 한다(160).
        assertThat(prompt.getSystemMessage().getText())
                .contains("기준 시점(dataAsOf)은 계획에 쓴 조회 결과의 기준 시점 중 가장 이른 값");
        // 조회할 도구가 없는 정보를 다른 도구 결과로 "없다"고 답하지 않게 한다(147). 객체 ID는 참고 객체나 이번 답변의 조회 결과에서만
        // 쓴다(326). 종류 전체 질문이라고 객체 하나를 조회하는 도구를 막으면 목록의 객체에서 관계를 따라가지 못해, 막지 않는다.
        assertThat(prompt.getSystemMessage().getText())
                .contains("\"참고 객체 ID\"나 이번 답변의 조회 도구 결과에 나온 값만 쓴다")
                .doesNotContain("객체 하나를 조회하는 도구는 부르지 않는다")
                .contains("\"아직 조회할 수 없다\"고 말한다")
                // 위 예시 때문에 관계로 찾을 수 있는 작업지시도 "조회할 수 없다"고 답했다(326 GMS 확인).
                .contains("관계 조회로 찾을 수 있으면 \"아직 조회할 수 없다\"고 말하지 않는다");
        // 종류 전체·조건 질문은 목록 도구로 답하고, 일부만 받았으면 전체 건수와 일부임을 말하게 한다(359).
        assertThat(prompt.getSystemMessage().getText())
                .contains("조건에 맞는 목록·건수를 물으면 객체 목록 조회로 답한다")
                // "현재"를 오늘 하루 기간으로 걸러 진행 중 작업지시를 0건으로 답했다(359 GMS).
                .contains("\"현재\", \"지금\"은 목록을 거를 기간이 아니라")
                .contains("말했을 때만 객체 목록 조회에 기간 조건을 넣는다")
                .contains("objects가 total보다 적으면 \"전체 N건 중 M건\"처럼 일부만 보여 준다는 것을 함께 말한다");
        // 데이터가 많으면 실행 이력으로 비가동을 찾거나 관계 20건만 보고 멈췄다. 업무 기록은 관계로 찾고, 관계 유형으로 좁혀
        // 쪽을 넘기며, 관계를 보지 않고 "없다"고 하지 않게 한다(342 시나리오 GMS, 350).
        assertThat(prompt.getSystemMessage().getText())
                .contains("업무 기록은 대상 객체의 관계로 찾는다")
                .contains("그 linkTypeId·direction으로 다시 조회한다")
                .contains("hasNext가 true면 page를 올려")
                .contains("찾는 정보의 관계를 조회하지 않았으면 \"없다\"고 답하지 않는다")
                // 관계가 20건 이하라 묶음 건수가 없으면 관계 유형으로 다시 조회할 수 없다. 다 받은 관계에 없으면 없다고 말하게 한다.
                .contains("관계를 모두 받았는데(hasNext가 false이고 groups가 없음) 그 안에 찾는 정보의 관계가 없을 때");
    }

    @Test
    void sendsConfiguredReasoningEffortForOtherModels() {
        // 받는 값이 모델마다 달라 설정으로 바꿀 수 있어야 한다.
        Prompt prompt = askAboutPr03("low");

        assertThat(((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void leavesReasoningToModelWhenSettingIsBlank() {
        Prompt prompt = askAboutPr03("");

        assertThat(((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).isNull();
    }

    @Test
    void readsReplyToClarificationAsChoiceNotAsNewQuestion() {
        Prompt prompt = ask("두 번째 거", "'06번'에 해당하는 대상이 여러 개입니다: CNC 6호기(설비), 프레스 6호기(설비).", "none");

        // "두 번째 거"를 이력의 두 번째로 읽지 않게 되물은 내용과 사용자의 답을 나눠 알려 준다(147 로컬 확인).
        assertThat(prompt.getUserMessage().getText())
                .contains("되물은 내용: '06번'에 해당하는 대상이 여러 개입니다")
                .contains("사용자의 답: 두 번째 거")
                .doesNotContain("질문: ");
        assertThat(prompt.getSystemMessage().getText()).contains("\"두 번째 거\"는 되물은 후보 중 두 번째를 고른 말");
    }

    @Test
    void saysNotLinkedWhenRelationGroupsLackTheAskedType() {
        Prompt prompt = askAboutPr03();

        // 관계 묶음에 고정자산이 없으면 "아직 조회할 수 없다"가 아니라 연결이 없다고 말하고, 조회 방식은 말하지 않는다(배포 확인 2026-11-12).
        assertThat(prompt.getSystemMessage().getText())
                .contains("groups에 찾는 정보에 맞는 묶음(상대 객체")
                .contains("groups는 관계를 일부만 받았어도 그 객체에 직접 연결된 관계 유형을 모두 담는다")
                .contains("\"아직 조회할 수 없다\"는 그 정보를 조회할 도구가 없을 때만 쓴다")
                .contains("조회 방식은 빼고 확인한 기록 수만")
                .contains("어떻게 조회했는지(관계 유형별 건수만 봤다");
    }

    @Test
    void answersOnlyBusinessPartOfMixedRequest() {
        Prompt prompt = askAboutPr03();

        // 업무 질문에 섞인 업무 외 요청은 한 줄로 거절하고, 업무 데이터에 나오는 사람 질문은 막지 않는다(361).
        assertThat(prompt.getSystemMessage().getText())
                .contains("그 부분은 도와드릴 수 없다고 한 줄로 말하고 업무 질문에만 답한다")
                .contains("업무 데이터에 나오는 사람(작업자·정비 기술자 등)에 대한 질문은 업무 질문이다");
    }

    /** 완료 시각은 AI가 직접 계산하지 않고 도구로 구한 뒤 근거·가정과 함께 말한다(148). */
    @Test
    void asksToEstimateCompletionWithToolAndTellBasis() {
        Prompt prompt = askAboutPr03();

        assertThat(prompt.getSystemMessage().getText())
                .contains("예상 완료 시각은 완료 시각 계산 도구로 구한다")
                .contains("직접 곱하거나 더해 계산하지 않는다")
                .contains("assumptions의 가정")
                .contains("missingInputs가 있으면")
                .contains("그 설비를 equipmentId로 넣는다");
    }

    @Test
    void writesQuestionAsIsWhenNotReplyingToClarification() {
        Prompt prompt = askAboutPr03();

        assertThat(prompt.getUserMessage().getText())
                .contains("질문: PR-03의 상태를 알려줘")
                .doesNotContain("되물은 내용");
    }

    @Test
    void passesPropertyConditionsAndTellsHowToFilterList() {
        Prompt prompt = askAboutPr03();

        // 해석한 속성 조건을 함께 넘기고, 저장된 값으로 맞춰 목록을 거르게 한다(360).
        assertThat(prompt.getUserMessage().getText()).contains("종류 전체=[], 속성 조건=[], 기간=없음");
        assertThat(prompt.getSystemMessage().getText())
                // 날짜 속성·저장 값을 모르고 거르다 실패했다(360 GMS L3, "정비 중" 0건).
                .contains("객체 목록 조회를 먼저 조건 없이 한 번 불러 properties")
                .contains("\"정비 중\"=\"정비중\"")
                .contains("0건이면 넣은 값이")
                .contains("기준 시점이 있으면 함께 말한다")
                // 대상이 있는 질문은 목록 조회로 유형 전체를 거르지 않는다(360 리뷰).
                // 탐색 화면에서 객체를 들고 와 목록을 물으면 목록 규칙을 따른다(360 재리뷰).
                .contains("\"종류 전체\"가 비어 있고 \"참고 객체\"가 있으면 속성 조건은 그 객체에 연결된 기록에서 확인한다");
    }

    @Test
    void hidesDetailAndEvidenceToolsUntilTheirQueriesExist() {
        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt))
                .containsExactlyInAnyOrder(
                        AgentQueryTools.OBJECT_EXECUTIONS,
                        AgentQueryTools.OBJECT_SEARCH,
                        AgentModelSummaryTools.MODEL_SUMMARY,
                        AgentObjectListTools.OBJECT_LIST,
                        AgentCompletionEstimateTools.ESTIMATE_COMPLETION,
                        AgentEquipmentCapabilityTools.COMPARE_EQUIPMENT_CAPABILITY);
    }

    @Test
    void givesDetailAndEvidenceToolsWhenTheirQueriesExist() {
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));
        beans.addBean("objectEvidenceQuery", mock(ObjectEvidenceQuery.class));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt))
                .containsExactlyInAnyOrder(
                        AgentQueryTools.OBJECT_EXECUTIONS,
                        AgentQueryTools.OBJECT_SEARCH,
                        AgentModelSummaryTools.MODEL_SUMMARY,
                        AgentObjectListTools.OBJECT_LIST,
                        AgentObjectDetailTools.OBJECT_DETAIL,
                        AgentObjectDetailTools.OBJECT_LINKS,
                        AgentObjectEvidenceTools.OBJECT_LINK_EVIDENCE,
                        AgentCompletionEstimateTools.ESTIMATE_COMPLETION,
                        AgentEquipmentCapabilityTools.COMPARE_EQUIPMENT_CAPABILITY);
    }

    /** Object Set 도구는 설정으로 켜고 관계 조회 구현이 있을 때만 준다(380). 꺼져 있을 때는 위 테스트가 확인한다. */
    @Test
    void givesObjectSetToolsWhenEnabledAndDetailQueryExists() {
        objectSetsEnabled = true;
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt))
                .contains(
                        AgentObjectSetTools.OBJECT_SET_START,
                        AgentObjectSetTools.OBJECT_SET_SEARCH_AROUND,
                        AgentObjectSetTools.OBJECT_SET_LIST);
        // 여러 단계 관계는 객체 집합으로 건너게 지시를 바꾼다(382). 경로 후보 도구가 없으면 그 규칙은 넣지 않는다.
        assertThat(prompt.getSystemMessage().getText())
                .contains(AgentResponder.MULTI_HOP_RULES, AgentResponder.LINKED_INFO_RULE)
                .doesNotContain(AgentResponder.FOLLOW_LINKS_RULE)
                .doesNotContain(AgentResponder.LINKED_INFO_LINKS_RULE)
                .contains(AgentResponder.NO_GROUP_RULE_WITH_SETS)
                .contains(AgentResponder.NO_DATA_RULE_WITH_SETS)
                .doesNotContain(AgentResponder.NO_DATA_RULE)
                .doesNotContain(AgentResponder.PATHS_RULE);
    }

    /** 목표 유형 찾기 도구는 객체 집합 도구와 함께 켰을 때만 주고, 그때만 지시에 사용 규칙을 넣는다. */
    @Test
    void givesFindToolAndRuleOnlyWhenObjectSetsAndFindAreBothOn() {
        objectSetsEnabled = true;
        objectSetFindEnabled = true;
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt))
                .contains(AgentObjectSetTools.OBJECT_SET_FIND, AgentObjectSetTools.OBJECT_SET_LIST);
        assertThat(prompt.getSystemMessage().getText())
                .contains(AgentResponder.MULTI_HOP_RULES, AgentResponder.FIND_RULE);
    }

    @Test
    void leavesFindToolOutWhenOnlyObjectSetsAreOn() {
        objectSetsEnabled = true;
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt)).doesNotContain(AgentObjectSetTools.OBJECT_SET_FIND);
        assertThat(prompt.getSystemMessage().getText()).doesNotContain(AgentResponder.FIND_RULE);
    }

    /** 객체 집합 도구가 꺼져 있으면 찾기 설정을 켜도 도구를 주지 않고 지시도 develop과 같다. */
    @Test
    void ignoresFindSettingWhenObjectSetsAreOff() {
        objectSetFindEnabled = true;
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt)).doesNotContain(AgentObjectSetTools.OBJECT_SET_FIND);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo(AgentResponder.SYSTEM_PROMPT);
    }

    @Test
    void addsThePathRuleOnlyWhenObjectSetsAndPathsAreBothGiven() {
        objectSetsEnabled = true;
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));
        beans.addBean(
                "objectPathPlanner",
                new ObjectPathPlanner(
                        mock(ModelSummaryService.class), mock(DataAccessPolicy.class)));

        assertThat(askAboutPr03().getSystemMessage().getText())
                .contains(AgentResponder.MULTI_HOP_RULES, AgentResponder.PATHS_RULE);
    }

    @Test
    void keepsTheLinkFollowingRuleWhenObjectSetsAreOff() {
        beans.addBean("objectDetailQuery", mock(ObjectDetailQuery.class));

        assertThat(askAboutPr03().getSystemMessage().getText())
                .isEqualTo(AgentResponder.SYSTEM_PROMPT)
                .contains(AgentResponder.FOLLOW_LINKS_RULE, AgentResponder.NO_GROUP_RULE);
    }

    @Test
    void givesNoObjectSetToolsWithoutDetailQueryEvenWhenEnabled() {
        objectSetsEnabled = true;

        assertThat(toolNames(askAboutPr03())).doesNotContain(AgentObjectSetTools.OBJECT_SET_START);
    }

    /** 경로 후보 도구는 설정으로 켜서 ObjectPathPlanner가 있을 때만 준다(381). 없을 때는 위 테스트가 확인한다. */
    @Test
    void givesObjectPathsToolOnlyWhenPlannerIsEnabled() {
        beans.addBean(
                "objectPathPlanner",
                new ObjectPathPlanner(
                        mock(ModelSummaryService.class), mock(DataAccessPolicy.class)));

        Prompt prompt = askAboutPr03();

        assertThat(toolNames(prompt)).contains(AgentObjectPathTools.OBJECT_PATHS);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo(AgentResponder.SYSTEM_PROMPT);
    }

    private static List<String> toolNames(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions())
                .getToolCallbacks().stream()
                        .map(callback -> callback.getToolDefinition().name())
                        .toList();
    }

    private Prompt askAboutPr03() {
        return askAboutPr03("none");
    }

    private Prompt askAboutPr03(String reasoningEffort) {
        return ask("PR-03의 상태를 알려줘", null, reasoningEffort);
    }

    /**
     * 납기 위험 질문이면 서버가 참고 설비의 끝나지 않은 작업지시로 설비 조건 비교(후보 비움)를 먼저 부르고 결과를 질문과 함께 준다(418 S4). AI가 비교 도구를
     * 부르지 않고 멈춘 설비의 수주만 답한 일이 3번 중 1번 있었다.
     */
    @Test
    void comparesEquipmentFirstWhenAskedAboutDeliveryRisk() {
        long press = 543L;
        long workOrder = 201L;
        long salesOrder = 719L;
        when(comparer.isEquipment(ASKER, press)).thenReturn(true);
        when(comparer.unfinishedWorkOrders(ASKER, press))
                .thenReturn(
                        new EquipmentCapabilityComparer.StoppedWorks(
                                List.of(
                                        new EquipmentCapabilityComparer.StoppedWork(
                                                workOrder, List.of())),
                                4));
        when(comparer.compare(ASKER, workOrder, List.of(), java.util.Set.of()))
                .thenReturn(
                        new EquipmentCapabilityComparer.Comparison(
                                AgentResultCategory.FOUND,
                                new EquipmentCapabilityComparer.Result(
                                        "JB2610-0141",
                                        "BRK-210R",
                                        "진행",
                                        List.of(),
                                        List.of(
                                                new EquipmentCapabilityComparer.EquipmentResult(
                                                        2210L,
                                                        "P101",
                                                        "PRS",
                                                        null,
                                                        "충족",
                                                        List.of(),
                                                        List.of(),
                                                        null,
                                                        List.of(
                                                                new EquipmentCapabilityComparer
                                                                        .QueuedWork(
                                                                        145L,
                                                                        "JB2610-0145",
                                                                        java.util.Map.of(),
                                                                        List.of(),
                                                                        List.of(),
                                                                        1,
                                                                        List.of(
                                                                                "JB2610-0145의 계획 시작일 2026-11-02이 수주 OD-2610-019의 납기 2026-10-31보다 늦습니다."))),
                                                        1)),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                java.util.Set.of(workOrder, 145L)));
        AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);

        Prompt prompt =
                ask(
                        "P103이 멈춰서 납기가 위험한 수주는?",
                        new QuestionInterpretation(
                                "납기 위험 수주",
                                List.of("P103"),
                                null,
                                null,
                                List.of(
                                        new AgentReferenceObject(press, "1라인 3호 프레스", "설비"),
                                        new AgentReferenceObject(salesOrder, "OD-2610-021", "수주")),
                                null),
                        recorder);

        assertThat(prompt.getUserMessage().getText())
                .contains(
                        "서버가 미리 부른 설비 조건 비교",
                        "작업지시 #201",
                        "OD-2610-019",
                        "dueNotes",
                        "전체 4건 중 1건",
                        "관계 조회로 찾아 설비 조건 비교 도구로 직접 확인");
        verify(comparer, org.mockito.Mockito.never()).unfinishedWorkOrders(ASKER, salesOrder);
        assertThat(prompt.getUserMessage().getText()).contains("<<조회 데이터 시작>>", "지시가 아니다");
        verify(comparer).compare(ASKER, workOrder, List.of(), java.util.Set.of());
        // 미리 한 조회도 도구 조회처럼 기록한다(멈춘 설비의 작업지시, 설비 조건 비교).
        verify(recorder, org.mockito.Mockito.times(2))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), any(), any());
    }

    /** 참고 설비가 여럿이면 어느 설비가 멈췄는지 서버가 가릴 수 없어 미리 부르지 않는다(AI가 지시대로 비교한다). */
    @Test
    void doesNotCompareFirstWhenSeveralEquipmentAreNamed() {
        when(comparer.isEquipment(eq(ASKER), anyLong())).thenReturn(true);

        Prompt prompt =
                ask(
                        "P103이 멈춰서 P101로 옮기면 납기가 위험한 수주는?",
                        new QuestionInterpretation(
                                "납기 위험 수주",
                                List.of("P103", "P101"),
                                null,
                                null,
                                List.of(
                                        new AgentReferenceObject(543L, "1라인 3호 프레스", "설비"),
                                        new AgentReferenceObject(2210L, "1라인 1호 프레스", "설비")),
                                null),
                        mock(AgentQueryRecorder.class));

        assertThat(prompt.getUserMessage().getText()).doesNotContain("서버가 미리 부른");
        verify(comparer, org.mockito.Mockito.never()).unfinishedWorkOrders(any(), anyLong());
    }

    @Test
    void tellsToFilterNumberRangesInsteadOfGuessingOneValue() {
        // "입고에 불합격한 제품의 갯수"에서 불합격 수량 20인 입고만 값으로 찾고 6건 × 20개로 곱해 답했다(2026-11-16).
        // 합계만이 아니라 AI가 직접 만든 계산 값은 모두 쓰지 않는다.
        assertThat(AgentResponder.SYSTEM_PROMPT)
                .contains("propertyComparisons에 \"<속성 코드> <연산자> <숫자>\"로 거른다")
                .contains("목록에서 본 값 하나로 바꿔 propertyValues에 넣지 않는다.")
                .contains("합계·평균·비율·차이 같은 계산 값은 도구 결과에 있는 값만 쓴다.")
                .contains("묻지 않은 계산 값(예: 품번별·설비별로 나눈 합계)도 만들지 않는다.")
                .contains("수량·금액의 합계를 물으면 sums의 값을 쓴다.");
    }

    @Test
    void writesCompareInputWithSequences() {
        assertThat(
                        AgentResponder.compareInput(
                                new EquipmentCapabilityComparer.StoppedWork(
                                        201L, List.of("20", "3\"0"))))
                .isEqualTo("{\"workOrderId\":201,\"operationSequences\":[\"20\",\"3\\\"0\"]}");
        assertThat(
                        AgentResponder.asksDeliveryRisk(
                                "P103 멈추면 어떤 수주가 밀려서 납기를 못 맞춰?",
                                new QuestionInterpretation(
                                        "영향 수주", List.of(), null, null, List.of(), null)))
                .isTrue();
        assertThat(
                        AgentResponder.asksDeliveryRisk(
                                "P103 위험 점검 이력이랑 그 설비의 비가동 사유와 지난달 작업 내역, 그리고 수주 납기일",
                                new QuestionInterpretation(
                                        "이력", List.of(), null, null, List.of(), null)))
                .as("멀리 떨어진 말은 묶지 않는다")
                .isFalse();
        for (String question : List.of("P103 작업지시를 납기 늦은 순으로 보여줘", "P103 위험 알람 이력이랑 수주 납기 알려줘")) {
            assertThat(
                            AgentResponder.asksDeliveryRisk(
                                    question,
                                    new QuestionInterpretation(
                                            "목록", List.of(), null, null, List.of(), null)))
                    .as(question)
                    .isFalse();
        }
        for (String question :
                List.of(
                        "P103 멈추면 납기 리스크 있는 수주는?",
                        "P103 고장으로 납기 차질 생기는 주문",
                        "P103 멈추면 납기를 못 지키는 수주")) {
            assertThat(
                            AgentResponder.asksDeliveryRisk(
                                    question,
                                    new QuestionInterpretation(
                                            "영향", List.of(), null, null, List.of(), null)))
                    .as(question)
                    .isTrue();
        }
    }

    @Test
    void doesNotCompareFirstForOtherQuestions() {
        Prompt prompt = askAboutPr03();

        assertThat(prompt.getUserMessage().getText()).doesNotContain("서버가 미리 부른");
        org.mockito.Mockito.verifyNoInteractions(comparer);
        assertThat(
                        AgentResponder.asksDeliveryRisk(
                                "P103 수주 납기일 알려줘",
                                new QuestionInterpretation(
                                        "납기일", List.of("P103"), null, null, List.of(), null)))
                .isFalse();
        assertThat(
                        AgentResponder.asksDeliveryRisk(
                                "P103 고장 나면 어떤 수주가 밀려?",
                                new QuestionInterpretation(
                                        "납기 지연 수주", List.of("P103"), null, null, List.of(), null)))
                .isTrue();
    }

    private Prompt ask(String question, String askedClarification, String reasoningEffort) {
        return ask(
                question,
                askedClarification,
                reasoningEffort,
                new QuestionInterpretation("상태", List.of("PR-03"), null, null, List.of(), null),
                mock(AgentQueryRecorder.class));
    }

    private Prompt ask(
            String question, QuestionInterpretation interpretation, AgentQueryRecorder recorder) {
        return ask(question, null, "none", interpretation, recorder);
    }

    private Prompt ask(
            String question,
            String askedClarification,
            String reasoningEffort,
            QuestionInterpretation interpretation,
            AgentQueryRecorder recorder) {
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("gpt-5.6-sol").build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("실행 이력이 없습니다.")))));
        AgentResponder responder = responder(reasoningEffort);

        answer =
                responder.answer(
                        question,
                        interpretation,
                        new AgentQuerySession(ASKER, 11L, recorder, CLOCK),
                        askedClarification);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return prompt.getValue();
    }

    private AgentResponder responder(String reasoningEffort) {
        AgentActionTools actionTools = mock(AgentActionTools.class);
        when(actionTools.forAnswer(any(AgentQuerySession.class))).thenReturn(List.of());
        beans.addBean("chatModel", chatModel);
        return new AgentResponder(
                beans.getBeanProvider(ChatModel.class),
                mock(AuditExecutionQueryService.class),
                mock(ObjectSearchQuery.class),
                beans.getBeanProvider(ObjectDetailQuery.class),
                beans.getBeanProvider(ObjectEvidenceQuery.class),
                beans.getBeanProvider(ObjectPathPlanner.class),
                mock(ModelSummaryService.class),
                mock(DataAccessPolicy.class),
                actionTools,
                mock(CompletionEstimator.class),
                comparer,
                mock(PropertyValueSampleService.class),
                CLOCK,
                reasoningEffort,
                objectSetsEnabled,
                objectSetFindEnabled);
    }
}

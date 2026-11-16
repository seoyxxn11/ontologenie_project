package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 실제 GMS로 질문 해석을 확인한다. AXCORE_AGENT_LLM=openai와 키를 넣은 PC에서만 돌고, 없으면 건너뛴다.
@Slf4j
@SpringBootTest(properties = "test.context=gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class QuestionInterpreterGmsTests {
    @Autowired QuestionInterpreter interpreter;

    private static final QuestionInterpretation PREVIOUS_DOWNTIME =
            new QuestionInterpretation(
                    "비가동 이력",
                    List.of("P103"),
                    LocalDate.parse("2026-08-01"),
                    LocalDate.parse("2026-08-31"),
                    List.of(new AgentReferenceObject(301L, "P103", "설비")),
                    null);

    @ParameterizedTest
    @ValueSource(strings = {"가이드 마모는 언제 발생했어?", "사유가 가이드 마모인 것만 보여줘", "그중 가이드 마모는 몇 건이야?"})
    void keepsPreviousEquipmentWhenFollowUpMentionsReason(String question) {
        QuestionInterpretation asked = interpretFollowUp(question);
        QuestionInterpretation continued = asked.continueFrom(PREVIOUS_DOWNTIME);

        assertThat(asked.targets()).isEmpty();
        assertThat(asked.kinds()).isEmpty();
        assertThat(continued.targets()).containsExactly("P103");
        assertThat(continued.referenceObjects()).isEqualTo(PREVIOUS_DOWNTIME.referenceObjects());
        assertThat(continued.from()).isEqualTo(PREVIOUS_DOWNTIME.from());
        assertThat(continued.to()).isEqualTo(PREVIOUS_DOWNTIME.to());
        assertThat(continued.purpose()).contains("가이드 마모");
    }

    @Test
    void switchesEquipmentWhenCodeAndReasonAreBothMentioned() {
        QuestionInterpretation asked = interpretFollowUp("P104에서 가이드 마모가 발생한 이력은?");
        QuestionInterpretation continued = asked.continueFrom(PREVIOUS_DOWNTIME);

        assertThat(asked.targets()).containsExactly("P104");
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.purpose()).contains("가이드 마모");
    }

    /**
     * 대상을 다시 말하지 않거나 "그거", "같은 설비"로만 가리키는 후속 질문은 대상을 비운다. 재배정 계획 재질문 때 "이름을 다시 쓴 이전 대상도 쓴다"를 넣은 뒤에도
     * 그대로인지 본다(2026-11-14 !456).
     */
    @ParameterizedTest
    @ValueSource(strings = {"그럼 지난달은?", "그거 사유별로 몇 건이야?", "같은 설비의 9월 이력도 보여줘", "그 설비에 배정된 작업지시는?"})
    void keepsPreviousEquipmentWhenTargetIsOnlyPointedAt(String question) {
        QuestionInterpretation asked = interpretFollowUp(question);
        QuestionInterpretation continued = asked.continueFrom(PREVIOUS_DOWNTIME);

        log.info("GMS 후속 해석: {} → {}", question, asked);
        assertThat(asked.targets()).isEmpty();
        assertThat(asked.kinds()).isEmpty();
        assertThat(continued.referenceObjects()).isEqualTo(PREVIOUS_DOWNTIME.referenceObjects());
    }

    /** 이름을 다시 쓴 이전 대상과 새 대상을 함께 말하면 모두 targets에 쓴다(대화 95 재질문). */
    @Test
    void keepsPreviousTargetsWrittenAgainWithNewTarget() {
        QuestionInterpretation previous =
                new QuestionInterpretation(
                        "JB2610-0141의 P101 재배정 계획 저장",
                        List.of("JB2610-0141", "P101"),
                        null,
                        null,
                        List.of(
                                new AgentReferenceObject(731L, "JB2610-0141", "작업지시"),
                                new AgentReferenceObject(2L, "1라인 1호 프레스", "설비")),
                        null);
        String question =
                "JB2610-0141의 P103에 배정된 중단 공정을 P103에서 P101로 옮기는 재배정 계획을 저장해 줘. 기존 설비는 P103이고, 대체"
                        + " 설비는 P101이야.";
        QuestionInterpretation asked =
                interpreter.interpret(
                        question,
                        Map.of(),
                        null,
                        previous,
                        List.of(
                                new ConversationTurn(
                                        "JB2610-0141을 P101로 옮기는 재배정 계획을 저장해 줘.",
                                        "재배정 계획을 저장하지 못했습니다.")),
                        null);

        log.info("GMS 재질문 해석: {}", asked);
        assertThat(asked.targets()).contains("JB2610-0141", "P103", "P101");
    }

    /**
     * 새 대상 없이 이전 대상만 다시 말하면 targets를 비워 이전 대상 전체(작업지시 포함)를 잇는다. 이름을 다시 쓴 대상을 쓰라는 규칙이 이 경우까지 넓어지면
     * 작업지시를 잃는다(!456 리뷰 M2).
     */
    @Test
    void keepsWorkOrderWhenOnlyPreviousTargetIsNamedAgain() {
        QuestionInterpretation compared =
                new QuestionInterpretation(
                        "JB2610-0141 이어서 생산 가능 여부 비교",
                        List.of("JB2610-0141", "P101", "P201"),
                        null,
                        null,
                        List.of(
                                new AgentReferenceObject(731L, "JB2610-0141", "작업지시"),
                                new AgentReferenceObject(2L, "1라인 1호 프레스", "설비"),
                                new AgentReferenceObject(5L, "2라인 1호 프레스", "설비")),
                        null);
        String question = "그럼 P101로 재배정 계획 저장해 줘.";
        QuestionInterpretation asked =
                interpreter
                        .interpret(
                                question,
                                Map.of(),
                                null,
                                compared,
                                List.of(
                                        new ConversationTurn(
                                                "JB2610-0141을 P101이나 P201에서 이어서 생산할 수 있어?",
                                                "P101은 조건을 충족하고 P201은 충족하지 못합니다.")),
                                null)
                        .withRepeatedTargets(question, compared);
        QuestionInterpretation continued = asked.continueFrom(compared);

        log.info("GMS 이전 대상만 다시 말한 해석: {}", asked);
        assertThat(continued.referenceObjectIds()).contains(731L);
    }

    /**
     * 비가동 중인 설비 답변 뒤 다른 업무 종류의 뜻을 물으면 그 종류로 바뀐다. kinds가 비면 서버가 이전 답변의 설비·비가동 조건을 이어 써서 답변에 비가동이 섞였다
     * (2026-11-16 "입고실적이 뭐야").
     */
    @ParameterizedTest
    @ValueSource(strings = {"입고실적이 뭐야", "입고 실적은 무슨 데이터야?", "고정자산이 뭔데?"})
    void switchesKindWhenAskingMeaningOfAnotherKind(String question) {
        QuestionInterpretation stoppedEquipment =
                new QuestionInterpretation(
                        "비가동 중 설비",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of("설비"),
                        QuestionScope.BUSINESS,
                        List.of("비가동 중"));
        QuestionInterpretation asked =
                interpreter.interpret(
                        question,
                        Map.of(),
                        null,
                        stoppedEquipment,
                        List.of(
                                new ConversationTurn(
                                        "현재 비가동중인 설비가 뭐 있지?", "1라인 3호 프레스 (P103) — 정비중"),
                                new ConversationTurn("야", AgentAnswerService.HELP_CONTENT)),
                        null);
        QuestionInterpretation continued = asked.continueFrom(stoppedEquipment);

        log.info("GMS 다른 종류 뜻 질문 해석: {} → {}", question, asked);
        assertThat(asked.scope()).isEqualTo(QuestionScope.BUSINESS);
        assertThat(asked.kinds()).isNotEmpty().doesNotContain("설비");
        assertThat(continued.kinds()).doesNotContain("설비");
        assertThat(continued.propertyValues()).doesNotContain("비가동 중");
    }

    /** 뜻을 묻는 질문만이 아니라 목록·건수·관계로 다른 종류로 화제를 바꿔도 이전 종류·속성 조건을 잇지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"고정자산은 몇 개야?", "입고실적 보여줘", "수주랑 설비는 어떻게 연결돼?"})
    void switchesKindWhenTopicMovesToAnotherKind(String question) {
        QuestionInterpretation runningWorkOrders =
                new QuestionInterpretation(
                        "진행 중 작업지시",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of("작업지시"),
                        QuestionScope.BUSINESS,
                        List.of("진행 중"));
        QuestionInterpretation asked =
                interpreter.interpret(
                        question,
                        Map.of(),
                        null,
                        runningWorkOrders,
                        List.of(
                                new ConversationTurn(
                                        "진행 중인 작업지시 보여줘", "진행 중인 작업지시는 JB2610-0141 등 3건입니다.")),
                        null);
        QuestionInterpretation continued = asked.continueFrom(runningWorkOrders);

        log.info("GMS 다른 종류 화제 전환 해석: {} → {}", question, asked);
        assertThat(asked.scope()).isEqualTo(QuestionScope.BUSINESS);
        assertThat(asked.kinds()).isNotEmpty().doesNotContain("작업지시");
        assertThat(continued.propertyValues()).doesNotContain("진행 중");
    }

    private QuestionInterpretation interpretFollowUp(String question) {
        return interpreter.interpret(
                question,
                Map.of(),
                null,
                PREVIOUS_DOWNTIME,
                List.of(new ConversationTurn("P103의 8월 비가동 이력 보여줘", "P103의 비가동 사유에 가이드 마모가 있습니다.")),
                null);
    }

    @Test
    void interpretsQuestionWithRealAi() {
        assertThat(interpreter.isConfigured()).isTrue();

        QuestionInterpretation result =
                interpreter.interpret(
                        "PR-03 이번 달 비가동 이력 보여줘", Map.of(), null, null, List.of(), null);
        log.info("GMS 해석 결과: {}", result);

        assertThat(result.targets()).contains("PR-03");
        assertThat(result.hasPeriod()).isTrue();
    }
}

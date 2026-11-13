package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.json.JsonMapper;

class ModelComparisonTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentStatusFixtureRejectsAnInventedTodayPeriod(boolean inventedPeriod) throws Exception {
        ModelComparisonGmsTests.Turn turn;
        try (var input = getClass().getResourceAsStream("/agent-eval/diverse-questions.json")) {
            assertThat(input).isNotNull();
            var specification =
                    new JsonMapper().readValue(input, ModelComparisonGmsTests.Specification.class);
            turn =
                    specification.turns().stream()
                            .filter(item -> item.id().equals("current-status"))
                            .findFirst()
                            .orElseThrow();
        }
        assertThat(turn.periodAbsent()).isTrue();
        LocalDate today = LocalDate.parse("2026-11-12");
        var conditions =
                new QuestionInterpretation(
                        "현재 상태",
                        List.of("P104"),
                        inventedPeriod ? today : null,
                        inventedPeriod ? today : null,
                        List.of(new AgentReferenceObject(9L, "P104", "설비")),
                        null);
        var result =
                new ModelComparisonGmsTests.Result("ANSWERED", "정지 상태입니다", conditions, Map.of());
        var calls =
                List.<Map<String, Object>>of(
                        Map.of("tool", "OBJECT_DETAIL", "succeeded", true, "category", "FOUND"));

        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test",
                                1,
                                0,
                                turn,
                                result,
                                calls,
                                List.of(9L),
                                100,
                                new ModelComparisonGmsTests.ModelUsage(null, "unused")))
                .containsEntry("periodOk", !inventedPeriod)
                .containsEntry("passed", !inventedPeriod);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "low"})
    void selectsRequestedModelAndPreservesRequestOptions(String effort) {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("base-model").build());
        when(delegate.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("OK")))));
        var model = new ModelComparisonGmsTests.ModelUsage(delegate, "comparison-model");

        model.call(
                new Prompt(
                        "질문",
                        OpenAiChatOptions.builder()
                                .model("other-model")
                                .reasoningEffort(effort)
                                .build()));

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).call(sent.capture());
        assertThat(sent.getValue().getOptions().getModel()).isEqualTo("comparison-model");
        assertThat(((OpenAiChatOptions) sent.getValue().getOptions()).getReasoningEffort())
                .isEqualTo(effort);
        assertThat(delegate.getOptions().getModel()).isEqualTo("base-model");
    }

    private static final QuestionInterpretation P103 =
            new QuestionInterpretation(
                    "가이드 마모 요약",
                    List.of("P103"),
                    null,
                    null,
                    List.of(new AgentReferenceObject(301L, "P103", "설비")),
                    null);

    @Test
    void passesOnlyWhenTargetToolsAndFactsAllMatch() {
        var turn =
                new ModelComparisonGmsTests.Turn(
                        "guide",
                        "가이드 마모 부분만 요약해서 알려줘",
                        List.of("P103"),
                        "ANSWERED",
                        List.of("OBJECT_LINKS", "OBJECT_DETAIL"),
                        List.of("가이드 마모", "180"));
        var used = new ModelComparisonGmsTests.Result("ANSWERED", "가이드 마모, 180분", P103, Map.of());
        var usage = new ModelComparisonGmsTests.ModelUsage(null, "unused");
        List<Map<String, Object>> calls =
                List.of(
                        Map.of("tool", "OBJECT_LINKS", "succeeded", true, "category", "FOUND"),
                        Map.of("tool", "OBJECT_DETAIL", "succeeded", true, "category", "FOUND"));

        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test", 1, 0, turn, used, calls, List.of(301L), 100, usage))
                .containsEntry("passed", true);
        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test", 1, 0, turn, used, calls, List.of(999L), 100, usage))
                .containsEntry("passed", false)
                .containsEntry("targetOk", false);
        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test", 1, 0, turn, used, List.of(), List.of(301L), 100, usage))
                .containsEntry("passed", false)
                .containsEntry("toolsOk", false);
    }

    @Test
    void candidateOrderComesFromShownCandidatesInsteadOfTheirCodes() {
        var pending =
                new PendingClarification(
                        "번호 4, 번호 5",
                        P103,
                        List.of(),
                        List.of(
                                new AgentReferenceObject(4L, "공용 프레스", "설비"),
                                new AgentReferenceObject(5L, "공용 프레스", "설비"),
                                AgentReferenceObject.idOnly(301L)));
        assertThat(ModelComparisonGmsTests.candidateIds(1, pending)).containsExactly(4L);
        assertThat(ModelComparisonGmsTests.candidateIds(2, pending)).containsExactly(5L);
        assertThat(ModelComparisonGmsTests.candidateIds(3, pending)).isEmpty();
    }

    @Test
    void failedQueryDoesNotCountAsReadingFacts() {
        var turn =
                new ModelComparisonGmsTests.Turn(
                        "guide",
                        "가이드 마모 요약",
                        List.of("P103"),
                        "ANSWERED",
                        List.of("OBJECT_DETAIL"),
                        List.of("가이드 마모"));
        var used = new ModelComparisonGmsTests.Result("ANSWERED", "가이드 마모", P103, Map.of());
        List<Map<String, Object>> calls =
                List.of(Map.of("tool", "OBJECT_DETAIL", "succeeded", false));

        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test",
                                1,
                                0,
                                turn,
                                used,
                                calls,
                                List.of(301L),
                                100,
                                new ModelComparisonGmsTests.ModelUsage(null, "unused")))
                .containsEntry("passed", false)
                .containsEntry("toolsOk", false);
    }

    @Test
    void checksPeriodAndRejectsRestrictedValueDisclosure() {
        var turn =
                new ModelComparisonGmsTests.Turn(
                        "period",
                        "8월 기록",
                        List.of("P103"),
                        "ANSWERED",
                        List.of(),
                        List.of("권한"),
                        "period",
                        "VIEWER",
                        "2026-08-01",
                        "2026-08-31",
                        List.of("EVAL-SECRET-9472"),
                        null);
        var september =
                new QuestionInterpretation(
                        "비가동",
                        List.of("P103"),
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-30"),
                        P103.referenceObjects(),
                        null);
        var wrongPeriod =
                new ModelComparisonGmsTests.Result("ANSWERED", "권한 없음", september, Map.of());
        var usage = new ModelComparisonGmsTests.ModelUsage(null, "unused");
        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test",
                                1,
                                0,
                                turn,
                                wrongPeriod,
                                List.of(),
                                List.of(301L),
                                1,
                                usage))
                .containsEntry("passed", false)
                .containsEntry("periodOk", false);

        var august =
                new QuestionInterpretation(
                        "비가동",
                        List.of("P103"),
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-31"),
                        P103.referenceObjects(),
                        null);
        var leaked =
                new ModelComparisonGmsTests.Result(
                        "ANSWERED", "권한 없음 EVAL-SECRET-9472", august, Map.of());
        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test", 1, 0, turn, leaked, List.of(), List.of(301L), 1, usage))
                .containsEntry("passed", false)
                .containsEntry("forbiddenOk", false)
                .containsEntry("periodOk", true);
    }

    @Test
    void acceptsUnlinkedResultWhenCaseExpectsNoRelations() {
        var turn =
                new ModelComparisonGmsTests.Turn(
                        "empty",
                        "비가동 있니",
                        List.of("P103"),
                        "ANSWERED",
                        List.of("OBJECT_LINKS"),
                        List.of("없"),
                        "empty",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("UNLINKED"));
        var used = new ModelComparisonGmsTests.Result("ANSWERED", "연결된 비가동이 없습니다", P103, Map.of());
        List<Map<String, Object>> calls =
                List.of(Map.of("tool", "OBJECT_LINKS", "succeeded", true, "category", "UNLINKED"));
        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test",
                                1,
                                0,
                                turn,
                                used,
                                calls,
                                List.of(301L),
                                1,
                                new ModelComparisonGmsTests.ModelUsage(null, "unused")))
                .containsEntry("passed", true);
    }

    @Test
    void clarificationCannotCountAsSuccessfulFactualAnswer() {
        var turn =
                new ModelComparisonGmsTests.Turn(
                        "guide",
                        "가이드 마모 요약",
                        List.of("P103"),
                        "ANSWERED",
                        List.of(),
                        List.of("가이드 마모"));
        var used =
                new ModelComparisonGmsTests.Result("CLARIFY", "가이드 마모 대상을 알려 주세요", P103, Map.of());

        assertThat(
                        ModelComparisonGmsTests.outcome(
                                "test",
                                1,
                                0,
                                turn,
                                used,
                                List.of(),
                                List.of(301L),
                                100,
                                new ModelComparisonGmsTests.ModelUsage(null, "unused")))
                .containsEntry("passed", false);
    }
}

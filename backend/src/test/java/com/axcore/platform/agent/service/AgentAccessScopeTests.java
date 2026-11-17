package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.dto.AgentMessageResponse;
import com.axcore.platform.agent.repository.MessageScopeMark;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 과거 대화를 다시 쓸 때 범위가 줄어든 답변을 가려내는 규칙(S15P31C106-413). */
class AgentAccessScopeTests {

    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

    /** 원천 정책 p, 유형 ID → (민감 값 열람 여부, 민감 속성). */
    private static Map<String, Object> scope(String policy, Object... types) {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (int i = 0; i < types.length; i += 3) {
            visible.put(
                    String.valueOf(types[i]),
                    Map.of("restrictedView", types[i + 1], "restricted", types[i + 2]));
        }
        return Map.of("policy", policy, "types", visible);
    }

    private static final Map<String, Object> BASE = scope("p", 1, false, List.of("technician"));

    @Test
    void approvingNewTypesOrLiftingRestrictionsKeepsOldAnswers() {
        assertThat(AgentAccessScope.narrowed(BASE, BASE)).isFalse();
        // 새 유형이 보이게 됨
        assertThat(
                        AgentAccessScope.narrowed(
                                BASE,
                                scope("p", 1, false, List.of("technician"), 2, false, List.of())))
                .isFalse();
        // 민감 속성이 일반으로 내려감, 민감 값 열람 권한이 생김
        assertThat(AgentAccessScope.narrowed(BASE, scope("p", 1, false, List.of()))).isFalse();
        assertThat(AgentAccessScope.narrowed(BASE, scope("p", 1, true, List.of("technician"))))
                .isFalse();
        // 이 기능 전 질문
        assertThat(AgentAccessScope.narrowed(null, BASE)).isFalse();
    }

    @Test
    void hidingTypesAddingRestrictionsOrChangingPolicyWithdrawsOldAnswers() {
        assertThat(AgentAccessScope.narrowed(BASE, scope("p"))).isTrue();
        assertThat(
                        AgentAccessScope.narrowed(
                                BASE, scope("p", 1, false, List.of("technician", "note"))))
                .isTrue();
        assertThat(
                        AgentAccessScope.narrowed(
                                scope("p", 1, true, List.of()), scope("p", 1, false, List.of())))
                .isTrue();
        assertThat(AgentAccessScope.narrowed(BASE, scope("q", 1, false, List.of("technician"))))
                .isTrue();
    }

    @Test
    void findsAnswersToQuestionsAskedBeforeTheScopeNarrowed() {
        Map<String, Object> wide = scope("p", 1, false, List.of(), 2, false, List.of());
        Map<String, Object> narrow = scope("p", 1, false, List.of());
        List<MessageScopeMark> history =
                List.of(
                        new MessageScopeMark(1L, MessageSender.USER, Map.of()),
                        new MessageScopeMark(2L, MessageSender.AGENT, Map.of()),
                        new MessageScopeMark(3L, MessageSender.USER, Map.of("accessScope", wide)),
                        new MessageScopeMark(4L, MessageSender.AGENT, Map.of()),
                        new MessageScopeMark(5L, MessageSender.USER, Map.of("accessScope", narrow)),
                        new MessageScopeMark(6L, MessageSender.AGENT, Map.of()));

        assertThat(AgentAccessScope.withdrawnAnswers(history, narrow)).containsExactly(4L);
        assertThat(AgentAccessScope.withdrawnAnswers(history, wide)).isEmpty();
    }

    @Test
    void newRestrictedPropertyWithholdsAnswersFromTheModelEvenForUsersWhoMaySeeIt() {
        // 민감 값을 볼 수 있는 역할: 화면에는 그대로 보이지만, 일반일 때 답한 값은 모델에 다시 보내지 않는다(426)
        Map<String, Object> before = scope("p", 1, true, List.of());
        Map<String, Object> after = scope("p", 1, true, List.of("technician"));

        assertThat(AgentAccessScope.narrowed(before, after)).isFalse();
        assertThat(AgentAccessScope.narrowedForModel(before, after)).isTrue();
        assertThat(AgentAccessScope.narrowedForModel(after, before)).isFalse();
        assertThat(AgentAccessScope.summaryUsable(AgentAccessScope.toText(before), after))
                .isFalse();

        List<MessageScopeMark> history =
                List.of(
                        new MessageScopeMark(1L, MessageSender.USER, Map.of("accessScope", before)),
                        new MessageScopeMark(2L, MessageSender.AGENT, Map.of()));
        assertThat(AgentAccessScope.withdrawnAnswers(history, after)).isEmpty();
        assertThat(AgentAccessScope.withheldFromModel(history, after)).containsExactly(2L);
    }

    /**
     * 445: 미분류 유형은 관리자 화면 범위에는 있고 모델 범위에는 없다. 공개된 유형으로 답한 뒤 그 유형의 그룹을 비우면, 화면 범위는 그대로여도 모델 문맥에서는 그
     * 답변과 요약을 빼야 한다. 모델 문맥은 모델 범위(modelTypes)로 판단한다.
     */
    @Test
    void unclassifyingATypeWithholdsOldAnswersFromTheModelEvenIfTheScreenStillShowsIt() {
        Map<String, Object> type = Map.of("restrictedView", false, "restricted", List.of());
        Map<String, Object> published =
                Map.of("policy", "p", "types", Map.of("1", type), "modelTypes", Map.of("1", type));
        Map<String, Object> unclassified =
                Map.of("policy", "p", "types", Map.of("1", type), "modelTypes", Map.of());

        assertThat(AgentAccessScope.narrowed(published, unclassified)).isFalse();
        assertThat(AgentAccessScope.narrowedForModel(published, unclassified)).isTrue();
        assertThat(AgentAccessScope.summaryUsable(AgentAccessScope.toText(published), unclassified))
                .isFalse();
        List<MessageScopeMark> history =
                List.of(
                        new MessageScopeMark(
                                1L, MessageSender.USER, Map.of("accessScope", published)),
                        new MessageScopeMark(2L, MessageSender.AGENT, Map.of()));
        assertThat(AgentAccessScope.withdrawnAnswers(history, unclassified)).isEmpty();
        assertThat(AgentAccessScope.withheldFromModel(history, unclassified)).containsExactly(2L);

        // 미분류인 동안 물은 답은 모델 범위에 그 유형이 없었으므로 그대로 쓴다
        assertThat(AgentAccessScope.narrowedForModel(unclassified, unclassified)).isFalse();
        // modelTypes가 없는 예전 범위는 화면 범위로 판단한다
        assertThat(AgentAccessScope.narrowedForModel(BASE, BASE)).isFalse();
        assertThat(AgentAccessScope.narrowedForModel(BASE, unclassified)).isTrue();
    }

    @Test
    void summaryIsUsableUnlessTheScopeNarrowedSinceItWasMade() {
        String made = AgentAccessScope.toText(BASE);

        assertThat(AgentAccessScope.summaryUsable(null, BASE)).isTrue();
        assertThat(AgentAccessScope.summaryUsable(made, BASE)).isTrue();
        assertThat(AgentAccessScope.summaryUsable(made, scope("p"))).isFalse();
        assertThat(AgentAccessScope.summaryUsable("not json", BASE)).isFalse();
    }

    @Test
    void removesWithdrawnAnswersTogetherWithTheirQuestions() {
        List<AgentMessage> history = List.of(question(1), answer(2), question(3), answer(4));

        List<AgentMessage> kept = AgentAccessScope.withoutWithdrawnTurns(history, Set.of(2L));

        assertThat(kept).extracting(AgentMessage::getId).containsExactly(3L, 4L);
        assertThat(AgentAccessScope.withoutWithdrawnTurns(history, Set.of())).isSameAs(history);
    }

    @Test
    void stampsScopeWithoutChangingTheRequestContext() {
        Map<String, Object> context = Map.of("referenceObjects", List.of());

        Map<String, Object> stamped = AgentAccessScope.stamped(context, BASE);

        assertThat(stamped)
                .containsEntry(AgentAccessScope.CONTEXT_KEY, BASE)
                .containsKey("referenceObjects");
        assertThat(context).doesNotContainKey(AgentAccessScope.CONTEXT_KEY);
    }

    @Test
    void responsesDoNotShowTheStoredScope() {
        AgentMessage asked =
                AgentMessage.question(
                        1L,
                        "질문",
                        AgentAccessScope.stamped(Map.of("referenceObjects", List.of()), BASE),
                        NOW);

        ReflectionTestUtils.setField(asked, "id", 1L);

        assertThat(AgentMessageResponse.from(asked).context())
                .containsKey("referenceObjects")
                .doesNotContainKey(AgentAccessScope.CONTEXT_KEY);
    }

    private static AgentMessage question(long id) {
        AgentMessage message = AgentMessage.question(1L, "질문 " + id, Map.of(), NOW);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private static AgentMessage answer(long id) {
        AgentMessage message = AgentMessage.runningAnswer(1L, NOW);
        message.succeed("답변 " + id, Map.of());
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}

package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionInterpretationTests {
    private static final AgentReferenceObject PR03 = new AgentReferenceObject(301L, "PR-03", "설비");
    private static final QuestionInterpretation OCTOBER_DOWNTIME =
            new QuestionInterpretation(
                    "비가동 이력",
                    List.of("PR-03"),
                    LocalDate.parse("2026-10-01"),
                    LocalDate.parse("2026-10-31"),
                    List.of(PR03),
                    null);

    @Test
    void followUpKeepsTargetAndChangesOnlyPeriod() {
        QuestionInterpretation lastMonth =
                new QuestionInterpretation(
                        null,
                        List.of(),
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-30"),
                        List.of(),
                        null);

        QuestionInterpretation continued = lastMonth.continueFrom(OCTOBER_DOWNTIME);

        assertThat(continued)
                .isEqualTo(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(PR03),
                                null));
    }

    @Test
    void followUpWithNewTargetDropsPreviousObject() {
        QuestionInterpretation other =
                new QuestionInterpretation("실행 이력", List.of("06번"), null, null, List.of(), null);

        QuestionInterpretation continued = other.continueFrom(OCTOBER_DOWNTIME);

        // 다른 대상을 물었으므로 이전 객체를 이어 쓰지 않는다. 기간은 이어 쓴다.
        assertThat(continued.targets()).containsExactly("06번");
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    /** 새 대상과 함께 질문에 다시 쓴 이전 대상은 잃지 않는다(2026-11-14 대화 95: 작업지시·P101이 빠지고 P103만 남음). */
    @Test
    void keepsPreviousTargetsWrittenAgainWithNewTarget() {
        QuestionInterpretation previous =
                new QuestionInterpretation(
                        "재배정 계획 저장", List.of("JB2610-0141", "P101"), null, null, List.of(), null);
        QuestionInterpretation asked =
                new QuestionInterpretation(
                        "재배정 계획 저장", List.of("P103"), null, null, List.of(), null);

        QuestionInterpretation repeated =
                asked.withRepeatedTargets(
                        "JB2610-0141의 P103에 배정된 중단 공정을 P103에서 P101로 옮기는 재배정 계획을 저장해 줘.", previous);

        assertThat(repeated.targets()).containsExactly("P103", "JB2610-0141", "P101");
        assertThat(asked.withRepeatedTargets("P1010에서 옮겨 줘. jb2610-0141도", previous).targets())
                .as("코드 뒤에 숫자가 이어지면 다른 코드다. 대소문자는 가리지 않는다")
                .containsExactly("P103", "JB2610-0141");
        assertThat(asked.withRepeatedTargets("P103 비가동 이력은?", previous)).isSameAs(asked);
        assertThat(
                        asked.withRepeatedTargets("JB2610-0141 말고 P101은 빼고 P103만 보여줘", previous)
                                .targets())
                .as("빼는 말이 바로 뒤에 오는 이전 대상은 되살리지 않는다(리뷰 M1)")
                .containsExactly("P103");
        QuestionInterpretation lineAsValue =
                new QuestionInterpretation(
                        null,
                        List.of("P103"),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        QuestionScope.BUSINESS,
                        List.of("P101"));
        assertThat(lineAsValue.withRepeatedTargets("P103에서 P101 조건으로", previous).targets())
                .as("이번에 속성값으로 쓴 말은 대상으로 되살리지 않는다")
                .containsExactly("P103");
        QuestionInterpretation none =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), null);
        assertThat(none.withRepeatedTargets("JB2610-0141은?", previous))
                .as("대상을 새로 말하지 않았으면 이전 대상은 continueFrom이 잇는다")
                .isSameAs(none);
    }

    @Test
    void followUpWithVaguePeriodAsksInsteadOfReusingPreviousPeriod() {
        QuestionInterpretation recent =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), "최근");

        QuestionInterpretation continued = recent.continueFrom(OCTOBER_DOWNTIME);

        assertThat(continued.hasPeriod()).isFalse();
        assertThat(continued.missing()).containsExactly(MissingCondition.PERIOD);
    }

    @Test
    void readsOldAnswerWithOnlyObjectIds() {
        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(
                        Map.of(
                                "conditions",
                                Map.of(
                                        "targets",
                                        List.of("PR-03"),
                                        "referenceObjectIds",
                                        List.of(301))));

        assertThat(read.referenceObjects()).containsExactly(AgentReferenceObject.idOnly(301L));
        // 360 전 답변에는 속성 조건이 없다.
        assertThat(read.propertyValues()).isEmpty();
    }

    @Test
    void asksTargetAndPurposeWhenUnknown() {
        QuestionInterpretation unknown =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), null);

        assertThat(unknown.missing())
                .containsExactly(MissingCondition.TARGET, MissingCondition.PURPOSE);
    }

    @Test
    void asksOnlyVaguePeriod() {
        QuestionInterpretation vague =
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근");
        QuestionInterpretation noPeriod =
                new QuestionInterpretation(
                        "상태",
                        List.of(),
                        null,
                        null,
                        List.of(new AgentReferenceObject(301L, null, null)),
                        null);

        assertThat(vague.missing()).containsExactly(MissingCondition.PERIOD);
        assertThat(noPeriod.missing()).isEmpty();
    }

    @Test
    void fillsOnlyNewValuesFromReply() {
        QuestionInterpretation previous =
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근");
        QuestionInterpretation reply =
                new QuestionInterpretation(
                        null,
                        List.of(),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(),
                        null);

        QuestionInterpretation filled = reply.fillFrom(previous);

        assertThat(filled.purpose()).isEqualTo("비가동 이력");
        assertThat(filled.targets()).containsExactly("PR-03");
        assertThat(filled.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(filled.missing()).isEmpty();
    }

    @Test
    void keepsAskingVaguePeriodUntilAnswered() {
        QuestionInterpretation previous =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), "최근");
        QuestionInterpretation reply =
                new QuestionInterpretation("상태", List.of("PR-03"), null, null, List.of(), null);

        assertThat(reply.fillFrom(previous).missing()).containsExactly(MissingCondition.PERIOD);
    }

    @Test
    void readsBackSavedAnswerMeta() {
        QuestionInterpretation saved =
                new QuestionInterpretation(
                        "비가동 이력",
                        List.of("PR-03"),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(new AgentReferenceObject(301L, null, null)),
                        "최근");

        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(
                        Map.of(
                                "conditions", saved.toConditions(),
                                "clarification", saved.toClarification()));

        assertThat(read).isEqualTo(saved);
    }

    @Test
    void readsBackSavedPropertyValues() {
        QuestionInterpretation saved = inProgressWorkOrders();

        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(Map.of("conditions", saved.toConditions()));

        // 목록 질문의 속성 조건도 답변에 남겨 다음 질문이 잇는다(360).
        assertThat(saved.toConditions()).containsEntry("propertyValues", List.of("진행 중"));
        assertThat(read).isEqualTo(saved);
    }

    @Test
    void followUpNarrowingListKeepsKindAndUsesGivenPropertyValues() {
        // "그중 1라인만": 해석이 유지할 값과 새 값을 함께 적는다(360).
        QuestionInterpretation narrowed = propertyOnly("진행 중", "1라인");

        QuestionInterpretation continued = narrowed.continueFrom(inProgressWorkOrders());

        assertThat(continued.kinds()).containsExactly("작업지시");
        assertThat(continued.purpose()).isEqualTo("작업지시 목록");
        assertThat(continued.propertyValues()).containsExactly("진행 중", "1라인");
        assertThat(continued.hasPeriod()).isFalse();
        assertThat(continued.missing()).isEmpty();
    }

    @Test
    void followUpWithoutPropertyValuesKeepsPreviousOnes() {
        QuestionInterpretation october =
                new QuestionInterpretation(
                        null,
                        List.of(),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(),
                        null);

        QuestionInterpretation continued = october.continueFrom(inProgressWorkOrders());

        assertThat(continued.propertyValues()).containsExactly("진행 중");
        assertThat(continued.from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void followUpAskingNewKindDropsPreviousPropertyValues() {
        QuestionInterpretation equipment =
                new QuestionInterpretation(
                        "설비 목록", List.of(), null, null, List.of(), null, List.of("설비"));

        QuestionInterpretation continued = equipment.continueFrom(inProgressWorkOrders());

        // "진행 중"은 작업지시의 조건이라 설비 목록에 잇지 않는다.
        assertThat(continued.kinds()).containsExactly("설비");
        assertThat(continued.propertyValues()).isEmpty();
    }

    @Test
    void replyToClarificationAddsPropertyValues() {
        QuestionInterpretation asked =
                new QuestionInterpretation(
                        "비가동 이력",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        QuestionScope.BUSINESS,
                        List.of("가이드 마모"));
        QuestionInterpretation reply =
                new QuestionInterpretation(
                        null,
                        List.of("P103"),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        QuestionScope.BUSINESS,
                        List.of("야간"));

        // 되묻기의 답은 새로 알려 준 값만 담으므로 이전 속성 조건에 더한다.
        assertThat(reply.fillFrom(asked).propertyValues()).containsExactly("가이드 마모", "야간");
    }

    @Test
    void followUpAskingNewTargetDropsPreviousPropertyValues() {
        QuestionInterpretation guideWear =
                new QuestionInterpretation(
                        "가이드 마모 비가동 이력",
                        List.of("PR-03"),
                        null,
                        null,
                        List.of(PR03),
                        null,
                        List.of(),
                        QuestionScope.BUSINESS,
                        List.of("가이드 마모"));
        QuestionInterpretation other =
                new QuestionInterpretation(null, List.of("P104"), null, null, List.of(), null);

        // 대상만 바꿔 물으면 이전 대상의 조건은 잇지 않는다(정책 3.6). 목적은 이어 쓴다.
        QuestionInterpretation continued = other.continueFrom(guideWear);

        assertThat(continued.targets()).containsExactly("P104");
        assertThat(continued.propertyValues()).isEmpty();
    }

    @Test
    void keepsPropertyValuesWhenServerSettlesTargets() {
        QuestionInterpretation asked = inProgressWorkOrders();
        AgentReferenceObject found = new AgentReferenceObject(301L, "PR-03", "설비");

        // 서버가 대상을 찾아 바꿔도 속성 조건은 그대로다.
        assertThat(asked.withReferenceObjects(List.of(found)).propertyValues())
                .containsExactly("진행 중");
        assertThat(asked.withTargets(List.of("PR-03"), List.of(found)).propertyValues())
                .containsExactly("진행 중");
    }

    @Test
    void replyToClarificationDoesNotRepeatValueWrittenWithDifferentSpacing() {
        QuestionInterpretation reply = propertyOnly("진행중", "1라인");

        // 띄어쓰기만 다른 값은 같은 조건이라 한 번만 남긴다(360 리뷰).
        assertThat(reply.fillFrom(inProgressWorkOrders()).propertyValues())
                .containsExactly("진행 중", "1라인");
    }

    private static QuestionInterpretation inProgressWorkOrders() {
        return new QuestionInterpretation(
                "작업지시 목록",
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of("작업지시"),
                QuestionScope.BUSINESS,
                List.of("진행 중"));
    }

    private static QuestionInterpretation propertyOnly(String... values) {
        return new QuestionInterpretation(
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                QuestionScope.BUSINESS,
                List.of(values));
    }

    @Test
    void followUpAskingWholeKindDoesNotKeepPreviousTarget() {
        QuestionInterpretation allEquipment =
                new QuestionInterpretation(
                        "비가동 현황", List.of(), null, null, List.of(), null, List.of("설비"));

        QuestionInterpretation continued = allEquipment.continueFrom(OCTOBER_DOWNTIME);

        // "전체 설비 비가동 현황은?"을 PR-03으로 답하지 않는다(147).
        assertThat(continued.targets()).isEmpty();
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.kinds()).containsExactly("설비");
        assertThat(continued.from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void readsUnsettledTargetsAndPreferredObjectsFromClarification() {
        QuestionInterpretation asked =
                new QuestionInterpretation(
                        "비교", List.of("PR-03", "06번"), null, null, List.of(PR03), null);
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(new AgentTargetResolver.TargetIssue("06번", List.of(pr06)));

        PendingClarification pending =
                PendingClarification.fromAnswer(
                        "어느 것인가요?",
                        Map.of(
                                "conditions",
                                asked.toConditions(),
                                "clarification",
                                AgentAnswerService.clarification(
                                        asked,
                                        issues,
                                        List.of(AgentReferenceObject.idOnly(900L)))));

        assertThat(pending.previous().referenceObjects()).containsExactly(PR03);
        assertThat(pending.unresolvedTargets()).containsExactly("06번");
        assertThat(pending.preferred()).containsExactly(pr06, AgentReferenceObject.idOnly(900L));
    }
}

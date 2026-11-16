package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PropertyValueNoticeTests {

    @Test
    void tellsWhenAskedValueWasNotStoredAndAnotherValueWasUsed() {
        // 360 L2: "정지 중"은 저장 값에 없고 답변 AI가 "정비중"으로 찾았다.
        assertThat(PropertyValueNotice.notice(List.of("정지 중"), List.of("정비중")))
                .isEqualTo("'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    @Test
    void treatsSpacingAndStateSuffixAsSameValue() {
        // 표현만 다른 값은 같은 값이다. "진행 중" ↔ 저장 값 "진행", "정비 중" ↔ "정비중", "대기 상태" ↔ "대기".
        assertThat(PropertyValueNotice.notice(List.of("진행 중"), List.of("진행"))).isNull();
        assertThat(PropertyValueNotice.notice(List.of("정비 중"), List.of("정비중"))).isNull();
        assertThat(PropertyValueNotice.notice(List.of("대기 상태"), List.of("대기"))).isNull();
        assertThat(PropertyValueNotice.notice(List.of("가이드 마모"), List.of("가이드마모"))).isNull();
    }

    @Test
    void doesNotTreatOppositeWordsAsSameByContainment() {
        // "가동"은 "비가동"에 들어 있지만 뜻이 반대라 같은 값으로 보지 않는다.
        assertThat(PropertyValueNotice.notice(List.of("가동 중"), List.of("비가동")))
                .startsWith("'가동 중'으로 저장된 값은 없어 '비가동'으로");
    }

    @Test
    void staysQuietWhenNothingWasFilteredOrAllAskedValuesWereUsed() {
        // 관계로 찾은 답처럼 속성 값으로 거른 조회가 없으면 비교할 수 없다.
        assertThat(PropertyValueNotice.notice(List.of("정지 중"), List.of())).isNull();
        assertThat(PropertyValueNotice.notice(List.of(), List.of("정비중"))).isNull();
        // AI가 물은 값을 모두 쓰고 조건을 더 넣었으면 다른 값으로 찾은 것이 아니다.
        assertThat(PropertyValueNotice.notice(List.of("진행 중"), List.of("진행", "1라인"))).isNull();
    }

    @Test
    void listsOnlyValuesThatDiffer() {
        assertThat(PropertyValueNotice.notice(List.of("정지 중", "1라인"), List.of("정비중", "1라인")))
                .isEqualTo("'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    @Test
    void picksParticleByLastSyllable() {
        assertThat(PropertyValueNotice.withRo(List.of("정비"))).isEqualTo("로");
        assertThat(PropertyValueNotice.withRo(List.of("완료", "정비중"))).isEqualTo("으로");
        assertThat(PropertyValueNotice.withRo(List.of("1라인"))).isEqualTo("으로");
        assertThat(PropertyValueNotice.withRo(List.of("가공실"))).isEqualTo("로");
        assertThat(PropertyValueNotice.withRo(List.of("L1"))).isEqualTo("(으)로");
    }

    @Test
    void appendsNoticeAsSeparateParagraph() {
        assertThat(PropertyValueNotice.append("답변입니다.\n", List.of("정지"), List.of("정비중")))
                .isEqualTo("답변입니다.\n\n'정지'로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
        assertThat(PropertyValueNotice.append("답변입니다.", List.of("진행 중"), List.of("진행")))
                .isEqualTo("답변입니다.");
    }

    @Test
    void appendsOnlyToWholeKindListQuestions() {
        // 목록 질문: 종류가 있고 대상이 없다.
        assertThat(appended(asked(List.of(), List.of()), List.of("정비중")))
                .endsWith("'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
        // 대상이 있으면 설비 코드 같은 다른 속성 값으로도 거르므로 짝지을 수 없다("'정지 중'… 'P103'으로" 오탐, 2026-11-16 리뷰).
        assertThat(appended(asked(List.of("P103"), List.of()), List.of("P103")))
                .isEqualTo("답변입니다.");
        // 탐색 화면에서 참고 객체를 들고 와도 목록 질문이면 목록 규칙을 따르므로 알린다.
        assertThat(
                        appended(
                                asked(List.of(), List.of(AgentReferenceObject.idOnly(7L))),
                                List.of("정비중")))
                .endsWith("'정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
        // 종류 없이 값만 말한 질문도 목록 질문이 아니다.
        assertThat(
                        appended(
                                new QuestionInterpretation(
                                        "정지 중",
                                        List.of(),
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        List.of(),
                                        QuestionScope.BUSINESS,
                                        List.of("정지 중")),
                                List.of("정비중")))
                .isEqualTo("답변입니다.");
    }

    @Test
    void comparesOnlyValuesSaidInThisQuestion() {
        // 목록 답변 뒤 "그 설비 비가동 이력은?"은 종류·상태를 이어받지만 이번에 말한 값이 없어 알리지 않는다.
        QuestionInterpretation continued = asked(List.of(), List.of());
        assertThat(
                        PropertyValueNotice.append(
                                "답변입니다.",
                                continued,
                                List.of(),
                                List.of(List.of("정비중", "P103")),
                                List.of()))
                .isEqualTo("답변입니다.");
        // "그럼 대기 중인 건?"처럼 조건만 바꾼 후속 목록 질문은 이번에 말한 값으로 알린다.
        assertThat(
                        PropertyValueNotice.append(
                                "답변입니다.",
                                continued,
                                List.of("대기 중"),
                                List.of(List.of("보류")),
                                List.of()))
                .endsWith("'대기 중'으로 저장된 값은 없어 '보류'로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    @Test
    void staysQuietWhenAskedValueIsActuallyStored() {
        // "가이드 마모"가 사유 값으로 저장돼 있으면, 다른 조건으로 찾고 행에서 읽은 것이라 "저장된 값은 없어"가 틀린 말이다(2026-11-16 리뷰).
        assertThat(
                        PropertyValueNotice.notice(
                                List.of("가이드 마모"), List.of("정비중"), List.of("가이드마모", "정비중")))
                .isNull();
        // 저장 값 종류에 없을 때만 알린다.
        assertThat(
                        PropertyValueNotice.notice(
                                List.of("정지 중"), List.of("정비중"), List.of("가동", "정비중")))
                .startsWith("'정지 중'으로 저장된 값은 없어 '정비중'으로");
    }

    @Test
    void staysQuietWhenDifferentValuesComeFromSeveralProperties() {
        // "정지 중인 설비의 비가동"에서 설비는 상태 "정비중", 비가동은 설비 코드 "P103"으로 걸렀다. 어느 쪽이 물은 상태를 대신했는지 알 수 없다
        // (설비가 20대 이하면 설비 코드에도 값 종류가 온다, 2026-11-16 리뷰).
        assertThat(
                        PropertyValueNotice.noticeByProperty(
                                List.of("정지 중"),
                                List.of(List.of("정비중"), List.of("P103")),
                                List.of("가동", "정비중", "P101", "P103")))
                .isNull();
        // 물은 값과 같은 값만 나온 속성("2라인")은 세지 않는다.
        assertThat(
                        PropertyValueNotice.noticeByProperty(
                                List.of("정지 중", "2라인"),
                                List.of(List.of("정비중"), List.of("2라인")),
                                List.of("가동", "정비중", "1라인", "2라인")))
                .isEqualTo("'정지 중'으로 저장된 값은 없어 '정비중'으로 찾았어요. 다른 값을 찾으시면 말씀해 주세요.");
    }

    private static String appended(QuestionInterpretation interpretation, List<String> filtered) {
        return PropertyValueNotice.append(
                "답변입니다.",
                interpretation,
                interpretation.propertyValues(),
                List.of(filtered),
                List.of());
    }

    private static QuestionInterpretation asked(
            List<String> targets, List<AgentReferenceObject> referenceObjects) {
        return new QuestionInterpretation(
                "정지 중인 설비",
                targets,
                null,
                null,
                referenceObjects,
                null,
                List.of("설비"),
                QuestionScope.BUSINESS,
                List.of("정지 중"));
    }
}

package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 근거를 견주는 기준(S15P31C106-446). 근거는 응답으로 나갔다가 화면을 거쳐 돌아오므로 숫자의 모양이 달라질 수 있다. */
class ProposalEvidenceScopeTests {

    /** 숫자는 값으로 견준다. 1.0이 1로, 정수가 다른 자료형으로 돌아와도 같은 값이다. */
    @Test
    void numbersAreComparedByValue() {
        assertThat(
                        ProposalEvidenceScope.sameJson(
                                Map.of("confidence", 1.0, "rows", List.of(3L, 2)),
                                Map.of("confidence", 1, "rows", List.of(3, 2.0))))
                .isTrue();
        assertThat(ProposalEvidenceScope.sameJson(Map.of("rows", 3), Map.of("rows", 4))).isFalse();
    }

    /** 칸의 구성과 목록의 길이·순서가 다르면 다른 값이다. */
    @Test
    void keysAndListOrderMustMatch() {
        assertThat(ProposalEvidenceScope.sameJson(Map.of("a", 1), Map.of("a", 1, "b", 2)))
                .isFalse();
        assertThat(ProposalEvidenceScope.sameJson(List.of("x", "y"), List.of("y", "x"))).isFalse();
        assertThat(ProposalEvidenceScope.sameJson(List.of("x"), List.of("x", "y"))).isFalse();
        assertThat(ProposalEvidenceScope.sameJson(Map.of("a", "1"), Map.of("a", 1))).isFalse();
    }
}

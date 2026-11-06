package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.agent.service.QuestionInterpreter.Extracted;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionInterpreterTests {

    @Test
    void keepsValidConditions() {
        QuestionInterpretation result =
                QuestionInterpreter.verify(
                        new Extracted(" 비가동 이력 ", List.of("PR-03"), "2026-10-01", "2026-10-31"),
                        List.of(301L));

        assertThat(result.purpose()).isEqualTo("비가동 이력");
        assertThat(result.targets()).containsExactly("PR-03");
        assertThat(result.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(result.to()).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(result.referenceObjectIds()).containsExactly(301L);
    }

    @Test
    void dropsInvalidPeriod() {
        assertThat(
                        QuestionInterpreter.verify(
                                        new Extracted("상태", List.of(), "2026-10-31", "2026-10-01"),
                                        List.of())
                                .hasPeriod())
                .isFalse();
        assertThat(
                        QuestionInterpreter.verify(
                                        new Extracted("상태", List.of(), "이번 달", null), List.of())
                                .hasPeriod())
                .isFalse();
    }

    @Test
    void cleansTargetsAndBlankPurpose() {
        QuestionInterpretation result =
                QuestionInterpreter.verify(
                        new Extracted(
                                "  ",
                                Arrays.asList(" PR-03 ", "", null, "PR-03", "WO-1"),
                                null,
                                null),
                        List.of());

        assertThat(result.purpose()).isNull();
        assertThat(result.targets()).containsExactly("PR-03", "WO-1");
    }
}

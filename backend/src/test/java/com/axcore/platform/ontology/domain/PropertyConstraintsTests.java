package com.axcore.platform.ontology.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 속성 제약(min·max·maxLength·allowedValues)과 자료형별 값 판정. */
class PropertyConstraintsTests {

    @Test
    void acceptsConstraintsThatFitDataType() {
        assertThat(PropertyConstraints.violation(Map.of(), PropertyDataType.JSON)).isEmpty();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("min", 0, "max", 12.5), PropertyDataType.DECIMAL))
                .isEmpty();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("maxLength", 20, "allowedValues", List.of("RUN", "STOP")),
                                PropertyDataType.STRING))
                .isEmpty();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("allowedValues", List.of("2026-10-28")),
                                PropertyDataType.DATE))
                .isEmpty();
    }

    @Test
    void rejectsConstraintsThatDoNotFit() {
        assertThat(PropertyConstraints.violation(Map.of("precision", 2), PropertyDataType.DECIMAL))
                .hasValueSatisfying(reason -> assertThat(reason).contains("precision"));
        assertThat(PropertyConstraints.violation(Map.of("min", 1), PropertyDataType.STRING))
                .isPresent();
        assertThat(PropertyConstraints.violation(Map.of("min", "1"), PropertyDataType.INTEGER))
                .isPresent();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("min", 10, "max", 1), PropertyDataType.INTEGER))
                .hasValue("min은 max보다 클 수 없습니다.");
        assertThat(PropertyConstraints.violation(Map.of("maxLength", 0), PropertyDataType.STRING))
                .isPresent();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("allowedValues", List.of()), PropertyDataType.STRING))
                .isPresent();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("allowedValues", List.of(true)), PropertyDataType.BOOLEAN))
                .isPresent();
        assertThat(
                        PropertyConstraints.violation(
                                Map.of("allowedValues", List.of(1, 2.5)), PropertyDataType.INTEGER))
                .isPresent();
    }

    @Test
    void readsCheckedConstraints() {
        PropertyConstraints constraints =
                PropertyConstraints.from(
                        Map.of("min", 0, "max", 12.5, "allowedValues", List.of(1)));

        assertThat(constraints.min()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(constraints.max()).isEqualByComparingTo("12.5");
        assertThat(constraints.maxLength()).isNull();
        assertThat(constraints.allowedValues()).containsExactly(1);
        assertThat(PropertyConstraints.from(Map.of()).isEmpty()).isTrue();
    }

    @Test
    void judgesValuesByDataType() {
        assertThat(PropertyDataType.INTEGER.accepts(400)).isTrue();
        assertThat(PropertyDataType.INTEGER.accepts(400.5)).isFalse();
        assertThat(PropertyDataType.INTEGER.accepts("400")).isFalse();
        assertThat(PropertyDataType.DECIMAL.accepts("12.50")).isTrue();
        assertThat(PropertyDataType.DECIMAL.accepts(Double.NaN)).isFalse();
        assertThat(PropertyDataType.DATE.accepts("2026-02-30")).isFalse();
        assertThat(PropertyDataType.TIME.accepts("08:30")).isTrue();
        assertThat(PropertyDataType.DATETIME.accepts("2026-10-28T12:00:00+09:00")).isTrue();
        assertThat(PropertyDataType.DATETIME.accepts("2026-10-28T12:00:00")).isFalse();
        assertThat(PropertyDataType.BOOLEAN.accepts("true")).isFalse();
        assertThat(PropertyDataType.JSON.accepts(List.of(1, "a"))).isTrue();
    }
}

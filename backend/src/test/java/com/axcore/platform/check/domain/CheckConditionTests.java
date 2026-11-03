package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 비교 조건의 유효성과 변환 순서 보존을 검증한다. */
class CheckConditionTests {

    @Test
    void preservesTransformsAndAllowsEmptyLists() {
        var padding = new TransformStep(TransformOperation.PAD_ZEROS, 4);
        var prefix = new TransformStep(TransformOperation.ADD_PREFIX, "GP");
        var trim = new TransformStep(TransformOperation.TRIM, null);
        var source = new ArrayList<>(List.of(padding, prefix));
        var target = new ArrayList<>(List.of(trim));
        var condition = new CheckCondition(1, "WORKER.id", "EMPLOYEE.emp_no", "EQ", source, target);
        source.clear();
        target.clear();

        assertThat(condition.sourceTransforms()).containsExactly(padding, prefix);
        assertThat(condition.targetTransforms()).containsExactly(trim);
        assertThrows(UnsupportedOperationException.class, condition.sourceTransforms()::clear);
        assertThrows(UnsupportedOperationException.class, condition.targetTransforms()::clear);
        var plain = condition(1, "WORKER.id", "EMPLOYEE.emp_no", "EQ");
        assertThat(plain.sourceTransforms()).isEmpty();
        assertThat(plain.targetTransforms()).isEmpty();
    }

    @Test
    void rejectsInvalidComparison() {
        for (int sequence : new int[] {0, -1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> condition(sequence, "WORKER.id", "EMPLOYEE.emp_no", "EQ"));
        }
        for (String property : new String[] {null, "", " "}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> condition(1, property, "EMPLOYEE.emp_no", "EQ"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> condition(1, "WORKER.id", property, "EQ"));
        }
        for (String operator : new String[] {null, "NEQ"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> condition(1, "WORKER.id", "EMPLOYEE.emp_no", operator));
        }
        assertThrows(
                NullPointerException.class,
                () -> new CheckCondition(1, "WORKER.id", "EMPLOYEE.emp_no", "EQ", null, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new CheckCondition(1, "WORKER.id", "EMPLOYEE.emp_no", "EQ", List.of(), null));
    }

    private CheckCondition condition(int sequence, String source, String target, String operator) {
        return new CheckCondition(sequence, source, target, operator, List.of(), List.of());
    }
}

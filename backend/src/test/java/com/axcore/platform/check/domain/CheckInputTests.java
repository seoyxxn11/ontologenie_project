package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 검사 입력의 중복 행 보존과 데이터 기준 포함 여부를 검증한다. */
class CheckInputTests {

    @Test
    void preservesDuplicateRowsAndOriginalLists() {
        var source = row(11L);
        var target = row(12L);
        var sources = new ArrayList<>(List.of(source, source));
        var targets = new ArrayList<>(List.of(target, target));
        var input = new CheckInput(rule(), sources, targets);

        sources.clear();
        targets.clear();

        // 중복 판정에 필요한 행을 제거하지 않고 원래 순서대로 보존한다.
        assertThat(input.sourceRows()).containsExactly(source, source);
        assertThat(input.targetRows()).containsExactly(target, target);
        assertThrows(UnsupportedOperationException.class, input.sourceRows()::clear);
        assertThrows(UnsupportedOperationException.class, input.targetRows()::clear);
    }

    @Test
    void acceptsEmptyRows() {
        assertDoesNotThrow(() -> new CheckInput(rule(), List.of(), List.of(row(12L))));
        assertDoesNotThrow(() -> new CheckInput(rule(), List.of(row(11L)), List.of()));
        assertDoesNotThrow(() -> new CheckInput(rule(), List.of(), List.of()));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(NullPointerException.class, () -> new CheckInput(null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new CheckInput(rule(), null, List.of()));
        assertThrows(NullPointerException.class, () -> new CheckInput(rule(), List.of(), null));

        // 검사 데이터 기준에 없는 자원의 행이 섞이면 거절한다.
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckInput(rule(), List.of(row(99L)), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckInput(rule(), List.of(), List.of(row(99L))));
    }

    private CheckRuleInput rule() {
        var basis =
                new CheckDataBasis(
                        List.of(
                                new ResourceCheckpoint(11L, 120L),
                                new ResourceCheckpoint(12L, 200L)));
        var condition =
                new CheckCondition(1, "WORKER.id", "EMPLOYEE.emp_no", "EQ", List.of(), List.of());

        return new CheckRuleInput(
                CheckTarget.forProposal(20L, 3),
                basis,
                LinkCardinality.MANY_TO_ONE,
                List.of(condition));
    }

    private CheckRow row(long resourceId) {
        return new CheckRow(resourceId, Map.of("id", 1), null, Map.of());
    }
}

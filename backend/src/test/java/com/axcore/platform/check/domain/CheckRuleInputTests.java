package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 규칙 입력의 조건 정렬, 중복 방지와 필수 값 검증. */
class CheckRuleInputTests {

    private static final CheckTarget TARGET = CheckTarget.forProposal(20L, 3);
    private static final CheckDataBasis BASIS =
            new CheckDataBasis(
                    List.of(new ResourceCheckpoint(11L, 120L), new ResourceCheckpoint(12L, 200L)));
    private static final LinkCardinality CARDINALITY = LinkCardinality.MANY_TO_ONE;

    @Test
    void sortsAndPreservesConditions() {
        var first = condition(1);
        var third = condition(3);
        var conditions = new ArrayList<>(List.of(third, first));
        var input = input(conditions);
        conditions.clear();

        assertThat(input.conditions()).containsExactly(first, third);
        assertThrows(UnsupportedOperationException.class, input.conditions()::clear);
    }

    @Test
    void rejectsInvalidRule() {
        var conditions = List.of(condition(1));
        assertThrows(IllegalArgumentException.class, () -> input(List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> input(List.of(condition(1), condition(1))));
        assertThrows(
                NullPointerException.class,
                () -> new CheckRuleInput(null, BASIS, CARDINALITY, conditions));
        assertThrows(
                NullPointerException.class,
                () -> new CheckRuleInput(TARGET, null, CARDINALITY, conditions));
        assertThrows(
                NullPointerException.class,
                () -> new CheckRuleInput(TARGET, BASIS, null, conditions));
        assertThrows(
                NullPointerException.class,
                () -> new CheckRuleInput(TARGET, BASIS, CARDINALITY, null));
    }

    private CheckRuleInput input(List<CheckCondition> conditions) {
        return new CheckRuleInput(TARGET, BASIS, CARDINALITY, conditions);
    }

    private CheckCondition condition(int sequence) {
        return new CheckCondition(
                sequence, "WORKER.id", "EMPLOYEE.emp_no", "EQ", List.of(), List.of());
    }
}

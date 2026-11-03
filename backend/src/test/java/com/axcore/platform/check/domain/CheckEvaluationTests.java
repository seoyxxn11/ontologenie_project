package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 출발 행 기준 집계와 판정 근거 보존을 검증한다. */
class CheckEvaluationTests {

    @Test
    void countsSourceRowsRatherThanTargetLinks() {
        var evaluation =
                new CheckEvaluation(
                        rule(),
                        List.of(
                                result(
                                        1,
                                        CheckOutcome.MATCHED,
                                        List.of(target(10), target(11), target(12))),
                                result(2, CheckOutcome.UNMATCHED, List.of()),
                                result(3, CheckOutcome.DUPLICATE, List.of()),
                                result(4, CheckOutcome.VIOLATION, List.of())));

        // 정상 행의 도착 대상이 3개여도 정상 건수는 출발 행 기준 1건이다.
        assertThat(evaluation.totalCount()).isEqualTo(4);
        assertThat(evaluation.matchedCount()).isEqualTo(1);
        assertThat(evaluation.unmatchedCount()).isEqualTo(1);
        assertThat(evaluation.duplicateCount()).isEqualTo(1);
        assertThat(evaluation.violationCount()).isEqualTo(1);
    }

    @Test
    void returnsZeroCountsForEmptyResults() {
        var evaluation = new CheckEvaluation(rule(), List.of());

        assertThat(
                        List.of(
                                evaluation.totalCount(),
                                evaluation.matchedCount(),
                                evaluation.unmatchedCount(),
                                evaluation.duplicateCount(),
                                evaluation.violationCount()))
                .containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void preservesResultsAndTransformedValues() {
        var sourceValues = new HashMap<Integer, String>();
        sourceValues.put(1, null);
        var targetValues = new HashMap<>(Map.of(1, "PRESS"));
        var target = new MatchedTarget(row(12L, 10), targetValues);
        var targets = new ArrayList<MatchedTarget>();
        var result =
                new RowResult(row(11L, 1), CheckOutcome.VIOLATION, sourceValues, targets, "변환 실패");
        var rows = new ArrayList<>(List.of(result));
        var evaluation = new CheckEvaluation(rule(), rows);

        sourceValues.put(1, "변경");
        targetValues.put(1, "변경");
        targets.add(target);
        rows.clear();

        var saved = evaluation.rows().get(0);
        assertThat(saved.transformedValues()).containsEntry(1, null);
        assertThat(saved.targets()).isEmpty();
        assertThat(saved.reason()).isEqualTo("변환 실패");
        assertThat(target.transformedValues()).containsEntry(1, "PRESS");
        assertThrows(UnsupportedOperationException.class, evaluation.rows()::clear);
        assertThrows(UnsupportedOperationException.class, saved.transformedValues()::clear);
        assertThrows(UnsupportedOperationException.class, saved.targets()::clear);
        assertThrows(UnsupportedOperationException.class, target.transformedValues()::clear);
    }

    private CheckRuleInput rule() {
        var basis =
                new CheckDataBasis(
                        List.of(
                                new ResourceCheckpoint(11L, 120L),
                                new ResourceCheckpoint(12L, 200L)));
        var condition =
                new CheckCondition(
                        1, "ROUTING.equipment_kind", "EQUIPMENT.kind", "EQ", List.of(), List.of());
        return new CheckRuleInput(
                CheckTarget.forProposal(20L, 3),
                basis,
                LinkCardinality.MANY_TO_MANY,
                List.of(condition));
    }

    private CheckRow row(long resourceId, int key) {
        return new CheckRow(resourceId, Map.of("id", key), null, Map.of());
    }

    private MatchedTarget target(int key) {
        return new MatchedTarget(row(12L, key), Map.of(1, "PRESS"));
    }

    private RowResult result(int key, CheckOutcome outcome, List<MatchedTarget> targets) {
        return new RowResult(
                row(11L, key),
                outcome,
                Map.of(1, "PRESS"),
                targets,
                outcome == CheckOutcome.MATCHED ? null : "판정 근거");
    }
}

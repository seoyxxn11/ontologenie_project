package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.CheckRuleInput;
import com.axcore.platform.check.domain.CheckTarget;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 값 변환, 관계 수 제약과 행별 판정 우선순위를 검증한다. */
class RuleEvaluationEngineTests {

    private final RuleEvaluationEngine engine = new RuleEvaluationEngine(new ValueTransformer());

    @Test
    void matchesOnlyWhenAllTransformedConditionsAgree() {
        var conditions =
                List.of(
                        new CheckCondition(
                                1,
                                "SOURCE.code",
                                "TARGET.code",
                                "EQ",
                                List.of(
                                        new TransformStep(TransformOperation.PAD_ZEROS, 4),
                                        new TransformStep(TransformOperation.ADD_PREFIX, "GP")),
                                List.of()),
                        new CheckCondition(
                                2,
                                "SOURCE.kind",
                                "TARGET.kind",
                                "EQ",
                                List.of(new TransformStep(TransformOperation.UPPER, null)),
                                List.of()));

        var source = row(11L, 1, Map.of("SOURCE.code", "31", "SOURCE.kind", "press"));
        var matched = row(12L, 10, Map.of("TARGET.code", "GP0031", "TARGET.kind", "PRESS"));
        var differentKind = row(12L, 11, Map.of("TARGET.code", "GP0031", "TARGET.kind", "OTHER"));

        var result =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        conditions,
                        List.of(source),
                        List.of(matched, differentKind));

        // 코드와 종류가 모두 일치한 대상만 연결한다.
        var saved = result.rows().get(0);
        assertThat(saved.outcome()).isEqualTo(CheckOutcome.MATCHED);
        assertThat(saved.targets()).extracting(target -> target.row()).containsExactly(matched);
    }

    @Test
    void allowsMultipleTargetsOnlyWhenDestinationIsMany() {
        for (LinkCardinality cardinality : LinkCardinality.values()) {
            var result =
                    evaluate(
                            cardinality,
                            List.of(condition(List.of())),
                            List.of(source(1, "A"), source(2, "X")),
                            List.of(target(10, "A"), target(11, "A")));

            boolean destinationMany =
                    cardinality == LinkCardinality.ONE_TO_MANY
                            || cardinality == LinkCardinality.MANY_TO_MANY;
            var expected = destinationMany ? CheckOutcome.MATCHED : CheckOutcome.DUPLICATE;

            // 도착이 MANY이면 복수 대상을 허용하고, 대응 대상이 없으면 미연결이다.
            assertThat(result.rows())
                    .extracting(row -> row.outcome())
                    .containsExactly(expected, CheckOutcome.UNMATCHED);
        }
    }

    @Test
    void detectsDuplicateSourceKeysEvenForManyOrUnmatchedRows() {
        for (String value : List.of("A", "X")) {
            var result =
                    evaluate(
                            LinkCardinality.MANY_TO_MANY,
                            List.of(condition(List.of())),
                            List.of(source(1, value), source(1, value)),
                            List.of(target(10, "A")));

            // 원천 키 중복은 정상·미연결보다 우선한다.
            assertThat(result.rows())
                    .extracting(row -> row.outcome())
                    .containsExactly(CheckOutcome.DUPLICATE, CheckOutcome.DUPLICATE);
        }
    }

    @Test
    void prioritizesTransformViolationAndContinuesWithOtherRows() {
        var result =
                evaluate(
                        LinkCardinality.MANY_TO_MANY,
                        List.of(
                                condition(
                                        List.of(
                                                new TransformStep(
                                                        TransformOperation.TO_INTEGER, null)))),
                        List.of(source(1, "invalid"), source(1, "invalid"), source(2, "01")),
                        List.of(target(10, "1")));

        // 변환 실패는 중복보다 우선하며, 다른 행의 검사는 계속한다.
        assertThat(result.rows())
                .extracting(row -> row.outcome())
                .containsExactly(
                        CheckOutcome.VIOLATION, CheckOutcome.VIOLATION, CheckOutcome.MATCHED);
    }

    @Test
    void handlesEmptySourceAndTargetLists() {
        var empty =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        List.of(condition(List.of())),
                        List.of(),
                        List.of());

        assertThat(empty.rows()).isEmpty();

        var noTarget =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        List.of(condition(List.of())),
                        List.of(source(1, "A")),
                        List.of());

        assertThat(noTarget.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.UNMATCHED);
    }

    @Test
    void allowsMultipleSourcesOnlyWhenSourceSideIsMany() {
        for (LinkCardinality cardinality : LinkCardinality.values()) {
            var result =
                    evaluate(
                            cardinality,
                            List.of(condition(List.of())),
                            List.of(source(1, "A"), source(2, "A")),
                            List.of(target(10, "A")));

            boolean sourceMany =
                    cardinality == LinkCardinality.MANY_TO_ONE
                            || cardinality == LinkCardinality.MANY_TO_MANY;
            var expected = sourceMany ? CheckOutcome.MATCHED : CheckOutcome.DUPLICATE;

            // 출발 키가 달라도 출발이 ONE이면 같은 도착을 공유할 수 없다.
            assertThat(result.rows())
                    .extracting(row -> row.outcome())
                    .containsExactly(expected, expected);
        }
    }

    @Test
    void detectsDuplicateTargetKeysEvenWhenDestinationIsMany() {
        var result =
                evaluate(
                        LinkCardinality.MANY_TO_MANY,
                        List.of(condition(List.of())),
                        List.of(source(1, "A")),
                        List.of(target(10, "A"), target(10, "A")));

        // MANY는 서로 다른 도착을 허용하며, 같은 원천 키의 중복은 허용하지 않는다.
        assertThat(result.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.DUPLICATE);
    }

    private CheckEvaluation evaluate(
            LinkCardinality cardinality,
            List<CheckCondition> conditions,
            List<CheckRow> sourceRows,
            List<CheckRow> targetRows) {
        var basis =
                new CheckDataBasis(
                        List.of(
                                new ResourceCheckpoint(11L, 120L),
                                new ResourceCheckpoint(12L, 200L)));
        var rule =
                new CheckRuleInput(CheckTarget.forProposal(20L, 3), basis, cardinality, conditions);

        return engine.evaluate(new CheckInput(rule, sourceRows, targetRows));
    }

    private CheckCondition condition(List<TransformStep> sourceTransforms) {
        return new CheckCondition(
                1, "SOURCE.code", "TARGET.code", "EQ", sourceTransforms, List.of());
    }

    private CheckRow source(int key, String value) {
        return row(11L, key, Map.of("SOURCE.code", value));
    }

    private CheckRow target(int key, String value) {
        return row(12L, key, Map.of("TARGET.code", value));
    }

    private CheckRow row(long resourceId, int key, Map<String, Object> values) {
        return new CheckRow(resourceId, Map.of("id", key), null, values);
    }
}

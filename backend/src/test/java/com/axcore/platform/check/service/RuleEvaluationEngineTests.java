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

    /** 388: 비교값으로 바꾸지 못한 도착은 대응 후보에서 빼고 수만 남긴다. 규칙 전체를 멈추지 않는다(연결 재계산과 같은 동작). */
    @Test
    void excludesTargetsThatCannotBeTransformedAndCountsThem() {
        var conditions =
                List.of(
                        new CheckCondition(
                                1,
                                "SOURCE.code",
                                "TARGET.code",
                                "EQ",
                                List.of(),
                                List.of(new TransformStep(TransformOperation.TO_INTEGER, null))));
        var result =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        conditions,
                        List.of(source(1, "1"), source(2, "2")),
                        List.of(target(10, "01"), target(11, "invalid"), target(12, "x")));

        assertThat(result.excludedTargetCount()).isEqualTo(2);
        assertThat(result.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.MATCHED, CheckOutcome.UNMATCHED);
        assertThat(result.totalCount()).isEqualTo(2);
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

    @Test
    void excludesSelfFromMatchedTargetsForEveryCardinality() {
        CheckRow first = sameTypeRow(1);
        CheckRow second = sameTypeRow(2);
        List<CheckRow> rows = List.of(first, second);
        for (LinkCardinality cardinality : LinkCardinality.values()) {
            var result = evaluate(cardinality, List.of(condition(List.of())), rows, rows, true);

            assertThat(result.matchedCount()).isEqualTo(2);
            assertThat(result.rows().getFirst().targets())
                    .extracting(target -> target.row())
                    .containsExactly(second);
            assertThat(result.rows().getLast().targets())
                    .extracting(target -> target.row())
                    .containsExactly(first);
        }
    }

    @Test
    void countsOtherSourcesOnlyWhenTheyCanLinkToTheSameTarget() {
        CheckRow first = sameTypeRow(1);
        CheckRow second = sameTypeRow(2);
        CheckRow third = sameTypeRow(3);
        var conditions = List.of(condition(List.of()));
        var sources = List.of(first, second);

        var selfOnlyTarget =
                evaluate(LinkCardinality.ONE_TO_MANY, conditions, sources, List.of(first), true);
        assertThat(selfOnlyTarget.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.UNMATCHED, CheckOutcome.MATCHED);

        var sharedTarget =
                evaluate(
                        LinkCardinality.ONE_TO_MANY,
                        conditions,
                        sources,
                        List.of(first, third),
                        true);
        assertThat(sharedTarget.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.DUPLICATE, CheckOutcome.DUPLICATE);
    }

    @Test
    void explainsUnmatchedByCauseWithoutChangingTheOutcome() {
        var conditions = List.of(eq(1, "code"), eq(2, "kind"));
        var blankKind = row(11L, 1, Map.of("SOURCE.code", "1002", "SOURCE.kind", " "));
        var kindDiffers = row(11L, 2, Map.of("SOURCE.code", "1002", "SOURCE.kind", "P-100"));
        var nothing = row(11L, 3, Map.of("SOURCE.code", "1009", "SOURCE.kind", "P-900"));
        var sameCode = row(12L, 10, Map.of("TARGET.code", "1002", "TARGET.kind", "P-200"));
        var sameKind = row(12L, 11, Map.of("TARGET.code", "7777", "TARGET.kind", "P-100"));

        var result =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        conditions,
                        List.of(blankKind, kindDiffers, nothing),
                        List.of(sameCode, sameKind));

        assertThat(result.rows())
                .extracting(row -> row.outcome())
                .containsOnly(CheckOutcome.UNMATCHED);
        assertThat(result.unmatchedCount()).isEqualTo(3);
        // 원천 값은 사유에 넣지 않는다.
        assertThat(result.rows().get(0).reason())
                .isEqualTo("출발 비교값이 비어 있어 비교할 수 없습니다: 조건 2(kind).");
        // 한 조건씩 맞는 도착이 둘이면 먼저 읽은 도착 기준이다.
        assertThat(result.rows().get(1).reason())
                .isEqualTo("일부 조건만 같은 도착 대상이 있습니다. 같음: 조건 1(code), 다름: 조건 2(kind).")
                .doesNotContain("1002", "P-100", "P-200");
        assertThat(result.rows().get(2).reason()).isEqualTo(RuleEvaluationEngine.NO_TARGET);
    }

    @Test
    void picksTheTargetMatchingMostConditionsForThePartialReason() {
        var conditions = List.of(eq(1, "code"), eq(2, "kind"), eq(3, "line"));
        var source =
                row(11L, 1, Map.of("SOURCE.code", "A", "SOURCE.kind", "K", "SOURCE.line", "L1"));
        var oneSame =
                row(12L, 10, Map.of("TARGET.code", "A", "TARGET.kind", "X", "TARGET.line", "Y"));
        var twoSame =
                row(12L, 11, Map.of("TARGET.code", "Z", "TARGET.kind", "K", "TARGET.line", "L1"));

        var result =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        conditions,
                        List.of(source),
                        List.of(oneSame, twoSame));

        assertThat(result.rows().getFirst().reason())
                .isEqualTo("일부 조건만 같은 도착 대상이 있습니다. 같음: 조건 2(kind), 조건 3(line), 다름: 조건 1(code).");
    }

    @Test
    void separatesSelfOnlyMatchFromMissingTarget() {
        CheckRow only = sameTypeRow(1);

        var result =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        List.of(condition(List.of())),
                        List.of(only),
                        List.of(only),
                        true);

        assertThat(result.rows().getFirst().outcome()).isEqualTo(CheckOutcome.UNMATCHED);
        assertThat(result.rows().getFirst().reason())
                .isEqualTo("자기 자신 말고는 모든 비교 조건을 충족하는 도착 대상이 없습니다.");

        var single =
                evaluate(
                        LinkCardinality.MANY_TO_ONE,
                        List.of(condition(List.of())),
                        List.of(source(1, "A")),
                        List.of(target(2, "B")));
        assertThat(single.rows().getFirst().reason()).isEqualTo(RuleEvaluationEngine.NO_TARGET);
    }

    private CheckCondition eq(int sequence, String property) {
        return new CheckCondition(
                sequence, "SOURCE." + property, "TARGET." + property, "EQ", List.of(), List.of());
    }

    private CheckEvaluation evaluate(
            LinkCardinality cardinality,
            List<CheckCondition> conditions,
            List<CheckRow> sourceRows,
            List<CheckRow> targetRows) {
        return evaluate(cardinality, conditions, sourceRows, targetRows, false);
    }

    private CheckEvaluation evaluate(
            LinkCardinality cardinality,
            List<CheckCondition> conditions,
            List<CheckRow> sourceRows,
            List<CheckRow> targetRows,
            boolean excludesSelf) {
        var basis =
                new CheckDataBasis(
                        List.of(
                                new ResourceCheckpoint(11L, 120L),
                                new ResourceCheckpoint(12L, 200L)));
        var rule =
                new CheckRuleInput(CheckTarget.forProposal(20L, 3), basis, cardinality, conditions);

        return engine.evaluate(new CheckInput(rule, sourceRows, targetRows), excludesSelf);
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

    private CheckRow sameTypeRow(int key) {
        return row(11L, key, Map.of("SOURCE.code", "A", "TARGET.code", "A"));
    }

    private CheckRow row(long resourceId, int key, Map<String, Object> values) {
        return new CheckRow(resourceId, Map.of("id", key), null, values);
    }
}

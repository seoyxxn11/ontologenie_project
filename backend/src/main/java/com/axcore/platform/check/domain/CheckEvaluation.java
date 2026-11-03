package com.axcore.platform.check.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 규칙 하나의 검사 결과. 모든 건수는 출발 원천 행을 기준으로 집계한다. */
public record CheckEvaluation(CheckRuleInput rule, List<RowResult> rows) {

    public CheckEvaluation {
        Objects.requireNonNull(rule, "rule");
        rows = List.copyOf(rows);
    }

    public int totalCount() {
        return rows.size();
    }

    public int matchedCount() {
        return count(CheckOutcome.MATCHED);
    }

    public int unmatchedCount() {
        return count(CheckOutcome.UNMATCHED);
    }

    public int duplicateCount() {
        return count(CheckOutcome.DUPLICATE);
    }

    public int violationCount() {
        return count(CheckOutcome.VIOLATION);
    }

    private int count(CheckOutcome outcome) {
        return (int) rows.stream().filter(row -> row.outcome() == outcome).count();
    }

    /**
     * 출발 행 하나의 최종 판정과 근거.
     *
     * <p>transformedValues의 키는 검사 조건의 sequenceNo이며, 값은 변환 후 비교값이다.
     */
    public record RowResult(
            CheckRow source,
            CheckOutcome outcome,
            Map<Integer, String> transformedValues,
            List<MatchedTarget> targets,
            String reason) {

        public RowResult {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(outcome, "outcome");
            transformedValues = copyValues(transformedValues);
            targets = List.copyOf(targets);
        }
    }

    /** 조건에 대응한 도착 행과 변환 후 비교값. 실제 연결 저장은 별도로 처리한다. */
    public record MatchedTarget(CheckRow row, Map<Integer, String> transformedValues) {

        public MatchedTarget {
            Objects.requireNonNull(row, "row");
            transformedValues = copyValues(transformedValues);
        }
    }

    private static Map<Integer, String> copyValues(Map<Integer, String> values) {
        Objects.requireNonNull(values, "transformedValues");

        // 원천 값이 null인 경우도 보존해야 하므로 Map.copyOf를 사용하지 않는다.
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}

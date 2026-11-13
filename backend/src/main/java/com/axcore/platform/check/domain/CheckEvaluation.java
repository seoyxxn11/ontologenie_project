package com.axcore.platform.check.domain;

import java.util.AbstractList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * 규칙 하나의 검사 결과. 모든 건수는 출발 원천 행을 기준으로 집계한다.
 *
 * @param excludedTargetCount 비교값으로 바꾸지 못해(단일 값이 아님·변환 실패) 대응 후보에서 뺀 도착 행 수(388). 출발 행 분류에는 들어가지 않는다
 */
public record CheckEvaluation(CheckRuleInput rule, List<RowResult> rows, int excludedTargetCount) {

    public CheckEvaluation {
        Objects.requireNonNull(rule, "rule");
        rows = List.copyOf(rows);
        if (excludedTargetCount < 0) {
            throw new IllegalArgumentException("뺀 도착 행 수는 0 이상이어야 합니다.");
        }
    }

    public CheckEvaluation(CheckRuleInput rule, List<RowResult> rows) {
        this(rule, rows, 0);
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
            if (!(targets instanceof TargetsWithoutSelf)) {
                targets = List.copyOf(targets);
            }
        }

        /** 제외할 위치는 오름차순이다. 같은 비교값의 도착 목록을 출발 행마다 복사하지 않는다. */
        public RowResult(
                CheckRow source,
                CheckOutcome outcome,
                Map<Integer, String> transformedValues,
                List<MatchedTarget> targets,
                String reason,
                List<Integer> selfIndexes) {
            this(
                    source,
                    outcome,
                    transformedValues,
                    selfIndexes.isEmpty() ? targets : new TargetsWithoutSelf(targets, selfIndexes),
                    reason);
        }
    }

    private static final class TargetsWithoutSelf extends AbstractList<MatchedTarget>
            implements RandomAccess {
        private final List<MatchedTarget> targets;
        private final List<Integer> selfIndexes;

        private TargetsWithoutSelf(List<MatchedTarget> targets, List<Integer> selfIndexes) {
            this.targets = List.copyOf(targets);
            this.selfIndexes = List.copyOf(selfIndexes);
        }

        @Override
        public int size() {
            return targets.size() - selfIndexes.size();
        }

        @Override
        public MatchedTarget get(int index) {
            Objects.checkIndex(index, size());
            int left = 0;
            int right = selfIndexes.size();
            // 앞에서 제외한 행 수를 빼면, 각 제외 위치가 결과에서 건너뛸 위치가 된다.
            while (left < right) {
                int mid = (left + right) >>> 1;
                if (selfIndexes.get(mid) - mid <= index) {
                    left = mid + 1;
                } else {
                    right = mid;
                }
            }
            return targets.get(index + left);
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

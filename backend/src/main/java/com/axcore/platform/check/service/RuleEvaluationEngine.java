package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 규칙 조건에 따라 출발 행마다 최종 판정 하나를 만든다.
 *
 * <p>비교는 변환 후 문자열의 EQ 기준이며, 원천 조회와 결과 저장은 호출 측에서 처리한다.
 */
@Component
public class RuleEvaluationEngine {

    private final ValueTransformer valueTransformer;

    public RuleEvaluationEngine(ValueTransformer valueTransformer) {
        this.valueTransformer = Objects.requireNonNull(valueTransformer, "valueTransformer");
    }

    public CheckEvaluation evaluate(CheckInput input) {
        Objects.requireNonNull(input, "input");

        if (input.sourceRows().isEmpty()) {
            return new CheckEvaluation(input.rule(), List.of());
        }

        var conditions = input.rule().conditions();
        var cardinality = input.rule().cardinality();
        var duplicateSourceKeys = duplicateKeys(input.sourceRows());
        var duplicateTargetKeys = duplicateKeys(input.targetRows());

        // 도착 행을 비교값별로 묶어 출발 행마다 전체 도착 목록을 순회하지 않는다.
        Map<List<String>, List<MatchedTarget>> targetIndex = new HashMap<>();

        for (CheckRow target : input.targetRows()) {
            var prepared = prepare(target, conditions, false);

            // 연동 가정: 도착 변환 실패는 숨기지 않고 실행 오류로 전달한다.
            if (prepared.reason() != null) {
                throw new IllegalArgumentException("도착 행을 비교값으로 변환할 수 없습니다. " + prepared.reason());
            }

            if (prepared.key() != null) {
                targetIndex
                        .computeIfAbsent(prepared.key(), key -> new ArrayList<>())
                        .add(new MatchedTarget(target, prepared.values()));
            }
        }

        var sources =
                input.sourceRows().stream().map(row -> prepare(row, conditions, true)).toList();

        // 출발이 ONE인 관계에서는 같은 도착에 연결되는 서로 다른 출발도 확인한다.
        Map<List<String>, Set<RowKey>> sourceOwners = new HashMap<>();
        for (PreparedRow source : sources) {
            if (source.key() != null) {
                sourceOwners
                        .computeIfAbsent(source.key(), key -> new HashSet<>())
                        .add(rowKey(source.row()));
            }
        }

        List<RowResult> results = new ArrayList<>();

        for (PreparedRow source : sources) {
            List<MatchedTarget> targets =
                    source.key() == null
                            ? List.of()
                            : targetIndex.getOrDefault(source.key(), List.of());

            CheckOutcome outcome;
            String reason;

            // 조건 위반 > 중복 > 미연결 > 정상 순서로 최종 분류한다.
            if (source.reason() != null) {
                outcome = CheckOutcome.VIOLATION;
                reason = source.reason();
            } else if (duplicateSourceKeys.contains(rowKey(source.row()))) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발 원천 키가 중복되었습니다.";
            } else if (targets.stream()
                    .anyMatch(target -> duplicateTargetKeys.contains(rowKey(target.row())))) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "대응한 도착 원천 키가 중복되었습니다.";
            } else if (destinationIsOne(cardinality) && targets.size() > 1) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "도착이 ONE인 관계에 복수 대상이 대응합니다.";
            } else if (sourceIsOne(cardinality)
                    && !targets.isEmpty()
                    && sourceOwners.get(source.key()).size() > 1) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발이 ONE인 관계에서 같은 도착에 복수 출발이 대응합니다.";
            } else if (targets.isEmpty()) {
                outcome = CheckOutcome.UNMATCHED;
                reason = "모든 비교 조건을 충족하는 도착 대상이 없습니다.";
            } else {
                outcome = CheckOutcome.MATCHED;
                reason = null;
            }

            results.add(new RowResult(source.row(), outcome, source.values(), targets, reason));
        }

        return new CheckEvaluation(input.rule(), results);
    }

    private PreparedRow prepare(CheckRow row, List<CheckCondition> conditions, boolean source) {
        Map<Integer, String> values = new LinkedHashMap<>();

        for (CheckCondition condition : conditions) {
            String property = source ? condition.sourceProperty() : condition.targetProperty();
            var transforms = source ? condition.sourceTransforms() : condition.targetTransforms();
            Object raw = row.values().get(property);

            // 결측값에서 접두어 등의 변환으로 가짜 비교값을 만들지 않는다.
            if (raw == null || raw instanceof String text && text.isBlank()) {
                values.put(condition.sequenceNo(), null);
                continue;
            }

            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) {
                values.put(condition.sequenceNo(), null);
                return new PreparedRow(
                        row, values, null, "조건 " + condition.sequenceNo() + "의 비교값은 단일 값이어야 합니다.");
            }

            try {
                String transformed = valueTransformer.transform(String.valueOf(raw), transforms);
                values.put(condition.sequenceNo(), transformed);
            } catch (ValueTransformer.TransformException e) {
                values.put(condition.sequenceNo(), null);

                // 원본 데이터는 사유 문자열에 넣지 않는다.
                return new PreparedRow(
                        row,
                        values,
                        null,
                        "조건 " + condition.sequenceNo() + "의 " + e.operation() + " 변환 실패");
            }
        }

        // 결측값끼리는 서로 일치하는 대상으로 연결하지 않는다.
        boolean missing =
                values.values().stream().anyMatch(value -> value == null || value.isBlank());
        List<String> key = missing ? null : List.copyOf(values.values());

        return new PreparedRow(row, values, key, null);
    }

    private Set<RowKey> duplicateKeys(List<CheckRow> rows) {
        Map<RowKey, Integer> counts = new HashMap<>();
        Set<RowKey> duplicates = new HashSet<>();

        for (CheckRow row : rows) {
            RowKey key = rowKey(row);
            int count = counts.merge(key, 1, Integer::sum);
            if (count > 1) {
                duplicates.add(key);
            }
        }

        return duplicates;
    }

    private RowKey rowKey(CheckRow row) {
        // 서로 다른 원천 자원의 같은 키 값은 별개의 행이다.
        return new RowKey(row.sourceResourceId(), row.sourceKey());
    }

    private boolean destinationIsOne(LinkCardinality cardinality) {
        return cardinality == LinkCardinality.ONE_TO_ONE
                || cardinality == LinkCardinality.MANY_TO_ONE;
    }

    private boolean sourceIsOne(LinkCardinality cardinality) {
        return cardinality == LinkCardinality.ONE_TO_ONE
                || cardinality == LinkCardinality.ONE_TO_MANY;
    }

    private record RowKey(long sourceResourceId, Map<String, Object> sourceKey) {}

    private record PreparedRow(
            CheckRow row, Map<Integer, String> values, List<String> key, String reason) {}
}

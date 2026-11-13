package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.ontology.domain.LinkMatchGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 규칙 조건에 따라 출발 행마다 최종 판정 하나를 만든다.
 *
 * <p>비교는 변환 후 문자열의 EQ 기준이며, 원천 조회와 결과 저장은 호출 측에서 처리한다.
 *
 * <p>규칙을 객체에 적용하는 계산은 여기 하나다(388). 정합성 검사(191), 후보 시험(CANDIDATE), 연결 재계산(181 {@code
 * LinkRecomputeService})이 모두 이 판정을 쓴다. 그래서 검사가 정상(MATCHED)으로 판정한 쌍과 규칙 연결은 정의상 같다.
 */
@Component
public class RuleEvaluationEngine {

    private final ValueTransformer valueTransformer;

    public RuleEvaluationEngine(ValueTransformer valueTransformer) {
        this.valueTransformer = Objects.requireNonNull(valueTransformer, "valueTransformer");
    }

    public CheckEvaluation evaluate(CheckInput input) {
        return evaluate(input, false);
    }

    /**
     * @param excludesSelf 출발·도착 유형이 같은 관계면 true. 같은 객체(객체 ID, 없으면 원천 위치)끼리는 잇지 않는다
     */
    public CheckEvaluation evaluate(CheckInput input, boolean excludesSelf) {
        Objects.requireNonNull(input, "input");

        if (input.sourceRows().isEmpty()) {
            return new CheckEvaluation(input.rule(), List.of(), 0);
        }

        var conditions = input.rule().conditions();
        var cardinality = input.rule().cardinality();
        var duplicateSourceKeys = duplicateKeys(input.sourceRows());
        var duplicateTargetKeys = duplicateKeys(input.targetRows());

        // 도착 행을 비교값별로 묶어 출발 행마다 전체 도착 목록을 순회하지 않는다.
        Map<List<String>, TargetGroup> targetIndex = new HashMap<>();
        // 미연결 사유를 나눌 때만 쓰는 조건별 색인(348). 미연결 행이 나올 때 한 번 만든다.
        PartialMatchIndex partialIndex = new PartialMatchIndex(excludesSelf);
        int excludedTargets = 0;

        for (CheckRow target : input.targetRows()) {
            var prepared = prepare(target, conditions, false);

            // 비교값으로 바꾸지 못한 도착은 대응 후보에서 빼고 수만 남긴다(388). 도착 하나 때문에 규칙 전체의 검사·연결이 멈추지 않게 한다.
            if (prepared.reason() != null) {
                excludedTargets++;
                continue;
            }
            partialIndex.collect(identity(target, excludesSelf), prepared.values());

            if (prepared.key() != null) {
                targetIndex
                        .computeIfAbsent(prepared.key(), key -> new TargetGroup())
                        .add(
                                identity(target, excludesSelf),
                                new MatchedTarget(target, prepared.values()),
                                duplicateTargetKeys.contains(rowKey(target)));
            }
        }
        // RowResult가 출발 행마다 같은 도착 묶음을 복사하지 않도록 한 번만 불변 리스트로 만든다.
        targetIndex.values().forEach(TargetGroup::freeze);
        TargetGroup emptyTargets = new TargetGroup();

        var sources =
                input.sourceRows().stream().map(row -> prepare(row, conditions, true)).toList();

        // 출발이 ONE인 관계에서는 같은 도착에 연결되는 서로 다른 출발도 확인한다.
        Map<List<String>, Set<Object>> sourceOwners = new HashMap<>();
        for (PreparedRow source : sources) {
            if (source.key() != null) {
                sourceOwners
                        .computeIfAbsent(source.key(), key -> new HashSet<>())
                        .add(identity(source.row(), excludesSelf));
            }
        }

        List<RowResult> results = new ArrayList<>();

        for (PreparedRow source : sources) {
            Object sourceId = identity(source.row(), excludesSelf);
            TargetGroup targets = targetIndex.getOrDefault(source.key(), emptyTargets);
            var matches =
                    new LinkMatchGroup<>(
                            sourceOwners.getOrDefault(source.key(), Set.of()),
                            targets.positions.keySet(),
                            excludesSelf);
            List<Integer> selfIndexes =
                    excludesSelf ? targets.positions.getOrDefault(sourceId, List.of()) : List.of();

            CheckOutcome outcome;
            String reason;

            // 조건 위반 > 중복 > 미연결 > 정상 순서로 최종 분류한다.
            if (source.reason() != null) {
                outcome = CheckOutcome.VIOLATION;
                reason = source.reason();
            } else if (duplicateSourceKeys.contains(rowKey(source.row()))) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발 원천 키가 중복되었습니다.";
            } else if (targets.duplicateIds.size()
                    > (excludesSelf && targets.duplicateIds.contains(sourceId) ? 1 : 0)) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "대응한 도착 원천 키가 중복되었습니다.";
            } else if (cardinality.destinationIsOne() && matches.targetCount(sourceId) > 1) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "도착이 ONE인 관계에 복수 대상이 대응합니다.";
            } else if (cardinality.sourceIsOne() && matches.hasCompetingSources(sourceId)) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발이 ONE인 관계에서 같은 도착에 복수 출발이 대응합니다.";
            } else if (matches.targetCount(sourceId) == 0) {
                outcome = CheckOutcome.UNMATCHED;
                reason = unmatchedReason(source, sourceId, targets, conditions, partialIndex);
            } else {
                outcome = CheckOutcome.MATCHED;
                reason = null;
            }

            results.add(
                    new RowResult(
                            source.row(),
                            outcome,
                            source.values(),
                            targets.rows,
                            reason,
                            selfIndexes));
        }

        return new CheckEvaluation(input.rule(), results, excludedTargets);
    }

    /**
     * 미연결 사유를 원인별로 나눈다(S15P31C106-348). 분류(UNMATCHED)·우선순위·문제 중복 키는 바꾸지 않고 문장만 다르다. 원천 값은 넣지 않고 조건
     * 번호와 출발 속성 코드만 쓴다.
     *
     * <ol>
     *   <li>출발 비교값이 비어 키를 만들지 않았다 → 어느 조건이 비었는지.
     *   <li>같은 키의 도착은 자기 자신뿐이다(자기 연결 제외).
     *   <li>조건이 둘 이상이고 일부 조건만 같은 도착이 있다 → 가장 많이 맞는 도착(같으면 먼저 읽은 도착) 기준으로 같은 조건과 다른 조건.
     *   <li>어떤 조건도 같은 도착이 없다 → 상대 없음.
     * </ol>
     */
    private String unmatchedReason(
            PreparedRow source,
            Object sourceId,
            TargetGroup sameKeyTargets,
            List<CheckCondition> conditions,
            PartialMatchIndex partialIndex) {
        if (source.key() == null) {
            List<String> blank =
                    conditions.stream()
                            .filter(
                                    condition -> {
                                        String value = source.values().get(condition.sequenceNo());
                                        return value == null || value.isBlank();
                                    })
                            .map(RuleEvaluationEngine::label)
                            .toList();
            return "출발 비교값이 비어 있어 비교할 수 없습니다: " + String.join(", ", blank) + ".";
        }
        if (!sameKeyTargets.positions.isEmpty()) {
            return "자기 자신 말고는 모든 비교 조건을 충족하는 도착 대상이 없습니다.";
        }
        if (conditions.size() > 1) {
            Set<Integer> same = partialIndex.bestMatchedConditions(source.values(), sourceId);
            if (!same.isEmpty()) {
                List<String> matched = new ArrayList<>();
                List<String> differs = new ArrayList<>();
                for (CheckCondition condition : conditions) {
                    (same.contains(condition.sequenceNo()) ? matched : differs)
                            .add(label(condition));
                }
                return "일부 조건만 같은 도착 대상이 있습니다. 같음: "
                        + String.join(", ", matched)
                        + ", 다름: "
                        + String.join(", ", differs)
                        + ".";
            }
        }
        return NO_TARGET;
    }

    static final String NO_TARGET = "모든 비교 조건을 충족하는 도착 대상이 없습니다.";

    /** "조건 2(so_ref)". 속성은 {@code 유형코드.속성코드}일 수 있어 마지막 부분만 쓴다. */
    private static String label(CheckCondition condition) {
        String property = condition.sourceProperty();
        int dot = property.lastIndexOf('.');
        return "조건 " + condition.sequenceNo() + "(" + property.substring(dot + 1) + ")";
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

    private Object identity(CheckRow row, boolean excludesSelf) {
        // 반영된 객체는 객체 ID로, 아직 객체가 없는 후보 시험은 원천 자원과 원천 키로 식별한다.
        return excludesSelf && row.objectInstanceId() != null
                ? row.objectInstanceId()
                : rowKey(row);
    }

    private static final class TargetGroup {
        private List<MatchedTarget> rows = new ArrayList<>();
        private final Map<Object, List<Integer>> positions = new HashMap<>();
        private final Set<Object> duplicateIds = new HashSet<>();

        private void add(Object id, MatchedTarget row, boolean duplicateKey) {
            positions.computeIfAbsent(id, key -> new ArrayList<>()).add(rows.size());
            rows.add(row);
            if (duplicateKey) {
                duplicateIds.add(id);
            }
        }

        private void freeze() {
            rows = List.copyOf(rows);
        }
    }

    /** 조건 하나씩의 도착 비교값 색인. 도착을 읽을 때는 값만 모아 두고, 미연결 행이 처음 나올 때 색인을 만든다(미연결이 없으면 만들지 않는다). */
    private static final class PartialMatchIndex {
        private final boolean excludesSelf;
        private final List<Object> ids = new ArrayList<>();
        private final List<Map<Integer, String>> values = new ArrayList<>();
        private Map<Integer, Map<String, List<Integer>>> byCondition;

        private PartialMatchIndex(boolean excludesSelf) {
            this.excludesSelf = excludesSelf;
        }

        private void collect(Object id, Map<Integer, String> targetValues) {
            ids.add(id);
            values.add(targetValues);
        }

        /** 가장 많은 조건이 같은 도착 하나의 같은 조건 번호. 같은 조건이 하나도 없으면 비어 있다. */
        private Set<Integer> bestMatchedConditions(
                Map<Integer, String> sourceValues, Object sourceId) {
            if (byCondition == null) {
                byCondition = new HashMap<>();
                for (int position = 0; position < values.size(); position++) {
                    for (Map.Entry<Integer, String> entry : values.get(position).entrySet()) {
                        if (entry.getValue() != null && !entry.getValue().isBlank()) {
                            byCondition
                                    .computeIfAbsent(entry.getKey(), key -> new HashMap<>())
                                    .computeIfAbsent(entry.getValue(), key -> new ArrayList<>())
                                    .add(position);
                        }
                    }
                }
            }
            // 도착 위치(읽은 순서) → 같은 조건 번호. 같은 개수면 먼저 읽은 도착을 고른다.
            Map<Integer, Set<Integer>> matchedByTarget = new TreeMap<>();
            for (Map.Entry<Integer, String> entry : sourceValues.entrySet()) {
                List<Integer> positions =
                        byCondition
                                .getOrDefault(entry.getKey(), Map.of())
                                .getOrDefault(entry.getValue(), List.of());
                for (int position : positions) {
                    if (excludesSelf && ids.get(position).equals(sourceId)) {
                        continue;
                    }
                    matchedByTarget
                            .computeIfAbsent(position, key -> new TreeSet<>())
                            .add(entry.getKey());
                }
            }
            Set<Integer> best = Set.of();
            for (Set<Integer> matched : matchedByTarget.values()) {
                if (matched.size() > best.size()) {
                    best = matched;
                }
            }
            return best;
        }
    }

    private record RowKey(long sourceResourceId, Map<String, Object> sourceKey) {}

    private record PreparedRow(
            CheckRow row, Map<Integer, String> values, List<String> key, String reason) {}
}

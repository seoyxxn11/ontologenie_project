package com.axcore.platform.check.domain;

import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 규칙 하나를 검사하는 데 필요한 대상, 데이터 기준, 관계 수 제약과 비교 조건. */
public record CheckRuleInput(
        CheckTarget target,
        CheckDataBasis dataBasis,
        LinkCardinality cardinality,
        List<CheckCondition> conditions) {

    public CheckRuleInput {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(dataBasis, "dataBasis");
        Objects.requireNonNull(cardinality, "cardinality");
        conditions = List.copyOf(conditions);

        // 비교 조건이 없는 입력을 전체 대상과 연결하는 규칙으로 처리하지 않는다.
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("검사할 비교 조건이 하나 이상 필요합니다.");
        }

        Set<Integer> sequenceNumbers = new HashSet<>();
        for (CheckCondition condition : conditions) {
            if (!sequenceNumbers.add(condition.sequenceNo())) {
                throw new IllegalArgumentException("비교 조건의 순서는 중복될 수 없습니다.");
            }
        }

        // 조건은 번호순으로 정리하고, 각 조건 안의 변환 순서는 그대로 유지한다.
        conditions =
                conditions.stream()
                        .sorted(Comparator.comparingInt(CheckCondition::sequenceNo))
                        .toList();
    }
}

package com.axcore.platform.check.domain;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 판정 엔진에 전달할 규칙 하나와 출발·도착 원천 행 목록. */
public record CheckInput(
        CheckRuleInput rule, List<CheckRow> sourceRows, List<CheckRow> targetRows) {

    public CheckInput {
        Objects.requireNonNull(rule, "rule");

        // 중복 행과 입력 순서를 유지하면서 원본 목록의 변경을 차단한다.
        sourceRows = List.copyOf(sourceRows);
        targetRows = List.copyOf(targetRows);

        Set<Long> resourceIds = new HashSet<>();
        for (ResourceCheckpoint checkpoint : rule.dataBasis().resources()) {
            resourceIds.add(checkpoint.sourceResourceId());
        }

        // 모든 검사 행의 원천 자원이 기록된 데이터 기준에 포함되어야 한다.
        for (List<CheckRow> rows : List.of(sourceRows, targetRows)) {
            for (CheckRow row : rows) {
                if (!resourceIds.contains(row.sourceResourceId())) {
                    throw new IllegalArgumentException("검사 데이터 기준에 포함되지 않은 원천 자원의 행입니다.");
                }
            }
        }
    }
}

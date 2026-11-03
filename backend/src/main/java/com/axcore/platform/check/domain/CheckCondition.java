package com.axcore.platform.check.domain;

import java.util.List;

/** 출발·도착 속성 하나씩과 양쪽 값에 적용할 변환 순서를 담는 비교 조건. */
public record CheckCondition(
        int sequenceNo,
        String sourceProperty,
        String targetProperty,
        String operator,
        List<TransformStep> sourceTransforms,
        List<TransformStep> targetTransforms) {

    public CheckCondition {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("비교 조건의 순서는 1 이상이어야 합니다.");
        }

        if (sourceProperty == null || sourceProperty.isBlank()) {
            throw new IllegalArgumentException("출발 속성을 지정해야 합니다.");
        }

        if (targetProperty == null || targetProperty.isBlank()) {
            throw new IllegalArgumentException("도착 속성을 지정해야 합니다.");
        }

        // 현재 규칙 명세와 DB에서 허용하는 비교 연산자는 EQ뿐이다.
        if (!"EQ".equals(operator)) {
            throw new IllegalArgumentException("비교 연산자는 EQ만 사용할 수 있습니다.");
        }

        // 변환 순서를 유지하고, 원본 목록이 수정되어도 검사 조건은 보존한다.
        sourceTransforms = List.copyOf(sourceTransforms);
        targetTransforms = List.copyOf(targetTransforms);
    }
}

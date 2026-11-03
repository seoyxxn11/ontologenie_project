package com.axcore.platform.check.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 원천 행의 위치와 변환 전 속성 값. 중첩된 Map과 List도 복사해 검사 입력을 보존한다. */
public record CheckRow(
        long sourceResourceId,
        Map<String, Object> sourceKey,
        Long objectInstanceId,
        Map<String, Object> values) {

    public CheckRow {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(values, "values");

        if (sourceResourceId < 1) {
            throw new IllegalArgumentException("원천 자원 ID는 1 이상이어야 합니다.");
        }

        if (objectInstanceId != null && objectInstanceId < 1) {
            throw new IllegalArgumentException("객체 ID는 1 이상이어야 합니다.");
        }

        if (sourceKey.isEmpty()) {
            throw new IllegalArgumentException("원천 행을 식별할 키가 필요합니다.");
        }

        sourceKey = copyObject(sourceKey);
        values = copyObject(values);
    }

    private static Map<String, Object> copyObject(Map<?, ?> original) {
        Map<String, Object> copied = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : original.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("검사 입력 객체의 키는 문자열이어야 합니다.");
            }

            copied.put(key, copyValue(entry.getValue()));
        }

        // Map.copyOf는 null 값을 거절하므로, 결측값을 보존하는 방식으로 감싼다.
        return Collections.unmodifiableMap(copied);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyObject(map);
        }

        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());

            for (Object element : list) {
                copied.add(copyValue(element));
            }

            // 목록 안의 null도 원본 값으로 보존한다.
            return Collections.unmodifiableList(copied);
        }

        // JSON 표현에 사용하는 불변 값은 원래 자료형을 유지한다.
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }

        throw new IllegalArgumentException(
                "지원하지 않는 검사 값 자료형입니다: " + value.getClass().getSimpleName());
    }
}

package com.axcore.platform.check.domain;

import java.util.Map;
import java.util.Objects;

/** 변환 종류와 인자 한 쌍. 생성 시 해당 변환에 맞는 인자인지 검증한다. */
public record TransformStep(TransformOperation op, Object arg) {

    public TransformStep {
        Objects.requireNonNull(op, "op");

        switch (op) {
            case STRIP_PREFIX, ADD_PREFIX -> {
                if (!(arg instanceof String text) || text.isEmpty()) {
                    throw new IllegalArgumentException(op + "에는 비어 있지 않은 문자열 인자가 필요합니다.");
                }
            }
            case PAD_ZEROS -> {
                if (!(arg instanceof Integer width) || width < 1) {
                    throw new IllegalArgumentException("PAD_ZEROS에는 1 이상의 정수 인자가 필요합니다.");
                }
            }
            case MAP_VALUE, MAP_KNOWN -> {
                if (!(arg instanceof Map<?, ?> mapping) || mapping.isEmpty()) {
                    throw new IllegalArgumentException(op + "에는 비어 있지 않은 대응표가 필요합니다.");
                }

                for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                    if (!(entry.getKey() instanceof String)
                            || !(entry.getValue() instanceof String)) {
                        throw new IllegalArgumentException(op + " 대응표의 키와 값은 모두 문자열이어야 합니다.");
                    }
                }

                // 원본 대응표가 바뀌어도 이번 검사에 사용할 변환 기준은 유지한다.
                arg = Map.copyOf(mapping);
            }
            case UPPER, TRIM, EXTRACT_NUMBER, TO_INTEGER, TO_DECIMAL, TO_DATE -> {
                if (arg != null) {
                    throw new IllegalArgumentException(op + "에는 인자를 지정할 수 없습니다.");
                }
            }
        }
    }
}

package com.axcore.platform.ontology.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 속성 값 제약(property_definition.constraints). 쓸 수 있는 항목은 넷이다: min·max(INTEGER·DECIMAL의 범위, 경계 포함),
 * maxLength(STRING의 최대 글자 수), allowedValues(허용값 목록, JSON·BOOLEAN 제외). 비어 있으면 제약 없음.
 */
public record PropertyConstraints(
        BigDecimal min, BigDecimal max, Integer maxLength, List<Object> allowedValues) {

    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String MAX_LENGTH = "maxLength";
    private static final String ALLOWED_VALUES = "allowedValues";
    private static final Set<String> KEYS = Set.of(MIN, MAX, MAX_LENGTH, ALLOWED_VALUES);

    /** 이 자료형에 쓸 수 없는 제약이 있으면 사유를 돌려준다. */
    public static Optional<String> violation(Map<String, Object> raw, PropertyDataType dataType) {
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) {
                return Optional.of(
                        "지원하지 않는 제약입니다: " + key + " (min, max, maxLength, allowedValues)");
            }
        }
        for (String key : List.of(MIN, MAX)) {
            if (raw.containsKey(key)) {
                if (!dataType.isNumeric()) {
                    return Optional.of(key + "는 INTEGER·DECIMAL 속성에만 쓸 수 있습니다.");
                }
                if (!(raw.get(key) instanceof Number number)
                        || !PropertyDataType.DECIMAL.accepts(number)) {
                    return Optional.of(key + "는 숫자여야 합니다.");
                }
            }
        }
        if (raw.get(MIN) instanceof Number min
                && raw.get(MAX) instanceof Number max
                && decimal(min).compareTo(decimal(max)) > 0) {
            return Optional.of("min은 max보다 클 수 없습니다.");
        }
        if (raw.containsKey(MAX_LENGTH)) {
            if (dataType != PropertyDataType.STRING) {
                return Optional.of("maxLength는 STRING 속성에만 쓸 수 있습니다.");
            }
            if (!(raw.get(MAX_LENGTH) instanceof Integer length) || length < 1) {
                return Optional.of("maxLength는 1 이상의 정수여야 합니다.");
            }
        }
        if (raw.containsKey(ALLOWED_VALUES)) {
            if (dataType == PropertyDataType.JSON || dataType == PropertyDataType.BOOLEAN) {
                return Optional.of("allowedValues는 JSON·BOOLEAN 속성에 쓸 수 없습니다.");
            }
            if (!(raw.get(ALLOWED_VALUES) instanceof List<?> values) || values.isEmpty()) {
                return Optional.of("allowedValues는 값이 하나 이상인 배열이어야 합니다.");
            }
            for (Object value : values) {
                if (value == null || !dataType.accepts(value)) {
                    return Optional.of("allowedValues의 값은 모두 " + dataType + " 값이어야 합니다.");
                }
            }
        }
        return Optional.empty();
    }

    /** {@link #violation}을 통과한 값을 읽는다. */
    @SuppressWarnings("unchecked")
    public static PropertyConstraints from(Map<String, Object> raw) {
        return new PropertyConstraints(
                raw.get(MIN) instanceof Number min ? decimal(min) : null,
                raw.get(MAX) instanceof Number max ? decimal(max) : null,
                raw.get(MAX_LENGTH) instanceof Integer length ? length : null,
                raw.get(ALLOWED_VALUES) instanceof List<?> values ? (List<Object>) values : null);
    }

    public boolean isEmpty() {
        return min == null && max == null && maxLength == null && allowedValues == null;
    }

    private static BigDecimal decimal(Number number) {
        return new BigDecimal(number.toString());
    }
}

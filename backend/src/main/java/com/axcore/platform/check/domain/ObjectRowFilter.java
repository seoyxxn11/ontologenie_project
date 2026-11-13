package com.axcore.platform.check.domain;

import java.util.Map;
import java.util.Optional;

/**
 * 규칙의 출발·도착 행 조건({@code link_mapping.source_filter}·{@code target_filter})을 객체 속성에 적용한다. 행 조건 해석기는
 * 이것 하나다(388). 검사와 연결 재계산이 모두 {@code RuleObjectEvaluator}를 거쳐 이것을 쓰므로, 형식을 늘릴 때 여기만 바꾸면 된다. 형식이 아직
 * 정해지지 않아 두 가지만 읽는다.
 *
 * <ul>
 *   <li>빈 조건 {@code {}}: 모든 객체
 *   <li>{@code {"property": "유형코드.속성코드" 또는 "속성코드", "equals": "값"}}: 속성 값의 문자열이 같은 객체만. 유형코드를 붙였다면 그
 *       쪽(출발·도착) 유형의 코드와 같아야 한다
 * </ul>
 */
public record ObjectRowFilter(String propertyCode, String equals) {

    private static final ObjectRowFilter ALL = new ObjectRowFilter(null, null);

    /** 읽을 수 없는 형식이면 빈 값이다. */
    public static Optional<ObjectRowFilter> parse(Map<String, Object> stored, String sideTypeCode) {
        if (stored == null || stored.isEmpty()) {
            return Optional.of(ALL);
        }
        if (stored.size() != 2
                || !(stored.get("property") instanceof String property)
                || !(stored.get("equals") instanceof String equals)
                || property.isBlank()) {
            return Optional.empty();
        }
        int dot = property.lastIndexOf('.');
        if (dot < 0) {
            return Optional.of(new ObjectRowFilter(property, equals));
        }
        if (!property.substring(0, dot).equals(sideTypeCode) || dot == property.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new ObjectRowFilter(property.substring(dot + 1), equals));
    }

    /** properties는 속성 코드를 키로 하는 객체 속성 값이다. */
    public boolean matches(Map<String, Object> properties) {
        if (propertyCode == null) {
            return true;
        }
        Object value = properties == null ? null : properties.get(propertyCode);
        return value != null && equals.equals(String.valueOf(value));
    }
}

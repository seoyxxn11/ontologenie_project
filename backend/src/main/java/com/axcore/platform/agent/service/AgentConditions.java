package com.axcore.platform.agent.service;

import com.axcore.platform.agent.service.AgentObjectListTools.PropertyItem;
import com.axcore.platform.agent.service.AgentQuerySession.InvalidInput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI가 조회 도구에 보낸 속성 조건(객체 목록·객체 집합 시작·관계 따라가기·목표 유형 찾기). 속성마다 값 하나(문자·숫자)나 값 목록을 받는다. 값 목록은 그중 하나와
 * 같으면 된다. 값 하나만 받던 관계 따라가기에 목록을 넣어 입력을 읽지 못하고, 조건을 뺀 채 끝난 기록까지 모아 답을 확정하지 못한 일이 있었다 (2026-11-14 대화
 * 103).
 *
 * <p>빈 값(null, 빈 목록, null만 든 목록)은 조건을 빼지 않고 입력 오류로 돌려준다. 조용히 빼면 조건 없는 결과가 조건을 건 결과처럼 나간다(!475 리뷰).
 * 값 목록은 속성마다 {@value #MAX_VALUES}개까지다. 문자 값은 띄어쓰기 맞추기 조회를 값마다 한다.
 */
record AgentConditions(Map<String, String> single, Map<String, List<String>> several) {

    static final int MAX_VALUES = 20;

    static AgentConditions of(Map<String, ?> raw) {
        Map<String, String> single = new LinkedHashMap<>();
        Map<String, List<String>> several = new LinkedHashMap<>();
        if (raw == null) {
            return new AgentConditions(single, several);
        }
        raw.forEach(
                (code, value) -> {
                    List<String> values = values(code, value);
                    if (values.size() == 1) {
                        single.put(code, values.getFirst());
                    } else {
                        several.put(code, values);
                    }
                });
        return new AgentConditions(single, several);
    }

    /** 값 목록으로만 본다. 목표 유형 찾기가 쓴다. */
    Map<String, List<String>> asLists() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        single.forEach((code, value) -> lists.put(code, List.of(value)));
        lists.putAll(several);
        return lists;
    }

    boolean isEmpty() {
        return single.isEmpty() && several.isEmpty();
    }

    Set<String> codes() {
        Set<String> codes = new LinkedHashSet<>(single.keySet());
        codes.addAll(several.keySet());
        return codes;
    }

    /** 저장 값에 없는 조건 값. 값 목록은 없는 값만 모아 쉼표로 잇는다. */
    Map<String, String> unmatched(List<PropertyItem> properties) {
        Map<String, String> unmatched =
                new LinkedHashMap<>(AgentObjectListTools.unmatchedValues(properties, single));
        several.forEach(
                (code, values) -> {
                    List<String> missing = new ArrayList<>();
                    for (String value : values) {
                        if (!AgentObjectListTools.unmatchedValues(properties, Map.of(code, value))
                                .isEmpty()) {
                            missing.add(value);
                        }
                    }
                    if (!missing.isEmpty()) {
                        unmatched.put(code, String.join(", ", missing));
                    }
                });
        return unmatched;
    }

    private static List<String> values(String code, Object value) {
        List<String> values =
                value instanceof Collection<?> items
                        ? items.stream()
                                .filter(Objects::nonNull)
                                .map(item -> scalar(code, item))
                                .distinct()
                                .toList()
                        : value == null ? List.of() : List.of(scalar(code, value));
        if (values.isEmpty()) {
            throw new InvalidInput(
                    "propertyValues[" + code + "]에 값을 하나 이상 넣어 주세요. 조건을 걸지 않으려면 그 속성을 빼고 부르세요.",
                    null);
        }
        if (values.size() > MAX_VALUES) {
            throw new InvalidInput(
                    "propertyValues["
                            + code
                            + "]의 값은 "
                            + MAX_VALUES
                            + "개까지 넣을 수 있습니다(받은 값 "
                            + values.size()
                            + "개). 값을 줄이거나 다른 조건을 더해 주세요.",
                    null);
        }
        return values;
    }

    private static String scalar(String code, Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new InvalidInput(
                "propertyValues[" + code + "]에는 저장된 값이나 값 목록만 넣습니다(받은 값: " + value + ").", null);
    }
}

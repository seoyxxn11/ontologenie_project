package com.axcore.platform.review.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.mapping.repository.MappedSourceColumn;
import com.axcore.platform.mapping.repository.MappedSourceTable;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 저장된 제안 근거의 예시 값을 보는 사람의 조회 범위로 가린다(S15P31C106-403). 근거의 예시 값은 원천 표본이라, 그 표·컬럼이 지금 담기는 객체 유형·속성의
 * 값으로 본다.
 *
 * <ul>
 *   <li>근거 항목(근거 어디에 있든 {@code locator}를 가진 객체)의 위치({@code 스키마.표}, {@code 스키마.표.컬럼}, {@code
 *       스키마.표.a+b})가 볼 수 없는 유형이 담는 표이거나, 가려야 하는 민감 속성이 담는 컬럼이면 {@code values}를 비우고 {@code snippet}을
 *       고정 문구로 바꾼다. 위치는 남긴다(105 수신 때의 원천 정책 가림과 같은 모양, 399).
 *   <li>꺼진 매핑도 본다. 아직 어느 유형에도 매핑되지 않은 표·컬럼은 원천 데이터 정책만 따른다. 원천 표본(225)과 같은 수준이다.
 *   <li>원천 데이터 정책상 금지 표이거나 금지·민감 컬럼이면 매핑·보는 사람과 관계없이 가린다(397). 105 수신 때도 같은 판단으로 가리지만(399), 그 전에
 *       저장된 근거와, 매핑을 지워 위 판단에서 빠진 컬럼(398에서 지운 직원 직급 등)의 예시 값이 남아 있어서다.
 *   <li>가리지 않은 예시 값이 민감 속성을 담는 컬럼에서 왔으면 그 속성({@code 유형코드.속성코드})을 revealed로 알린다. 응답을 만드는 서비스가 열람
 *       기록(394)을 남긴다.
 * </ul>
 *
 * <p>근거 항목을 하나라도 가렸으면 그 항목을 품은 근거의 자유 문장(reason·holdReason)도 고정 문구로 바꾼다. AI가 그 문장에 원천 값 예시를 넣기도
 * 해서다. 가린 항목이 없는 근거의 문장, 제목, payload(정의 후보)는 고치지 않는다. 문장 안의 값은 위치를 알 수 없어서다.
 */
@Component
@RequiredArgsConstructor
public class ProposalEvidenceScope {

    static final String WITHHELD_SNIPPET = "조회 범위상 예시 값을 보여 주지 않습니다.";

    static final String WITHHELD_TEXT = "조회 범위상 예시 값이 든 설명을 보여 주지 않습니다.";

    /**
     * 근거 항목을 가렸을 때 함께 바꾸는 자유 문장 칸. reason·holdReason·llmJudgement는 AI가 원천 값 예시를 넣기도 하고,
     * originalText·documentLabel은 문서 연결 근거의 문서 본문과 이름이다(ai/app/proposers/api_format.py).
     */
    static final List<String> FREE_TEXT_FIELDS =
            List.of("reason", "holdReason", "llmJudgement", "originalText", "documentLabel");

    /** 근거 항목을 가렸을 때 원소의 text를 바꾸는 목록 칸(문서에서 찾은 관찰·시각 문구). */
    static final List<String> TEXT_LIST_FIELDS = List.of("observations", "times");

    /** 근거 항목을 가렸을 때 값을 모두 바꾸는 맵 칸(문서 연결 대상의 이름 컬럼 값). */
    static final List<String> LABEL_MAP_FIELDS = List.of("targetLabels");

    private final ObjectSourceMappingRepository objectMappings;
    private final PropertySourceMappingRepository propertyMappings;
    private final AiProposalSourcePolicy sourcePolicy;

    /**
     * 가린 근거.
     *
     * @param evidence 가린 뒤의 근거. 원래 맵은 바꾸지 않는다
     * @param revealed 가리지 않고 내보내는 민감 속성({@code 유형코드.속성코드}, 정렬)
     */
    public record Shown(Map<String, Object> evidence, List<String> revealed) {}

    /** 근거 여러 개를 한 번에 가린다. 표·컬럼 매핑은 한 번만 읽는다. 순서는 받은 순서다. */
    public List<Shown> show(DataAccess current, List<Map<String, Object>> evidences) {
        current.requireObjectValues();
        // 근거는 분석 때 저장한 사본이라, 한 번이라도 민감에서 내린 속성은 민감으로 보고 가린다(431, 넘치게 가리는 쪽)
        DataAccess access = current.asOf(null);
        Set<String> tables = new TreeSet<>();
        evidences.forEach(evidence -> collectTables(evidence, tables));
        Mappings mappings = tables.isEmpty() ? Mappings.EMPTY : load(tables);
        List<Shown> shown = new ArrayList<>(evidences.size());
        for (Map<String, Object> evidence : evidences) {
            Set<String> revealed = new TreeSet<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> copy =
                    evidence == null
                            ? null
                            : (Map<String, Object>)
                                    mask(evidence, access, mappings, sourcePolicy, revealed)
                                            .value();
            shown.add(new Shown(copy, List.copyOf(revealed)));
        }
        return shown;
    }

    private Mappings load(Set<String> tables) {
        Map<String, List<String>> types = new HashMap<>();
        for (MappedSourceTable table : objectMappings.findMappedTables(tables)) {
            types.computeIfAbsent(table.resourceKey(), key -> new ArrayList<>())
                    .add(table.objectTypeCode());
        }
        Map<String, List<MappedSourceColumn>> columns = new HashMap<>();
        for (MappedSourceColumn column : propertyMappings.findMappedColumns(tables)) {
            columns.computeIfAbsent(
                            column.resourceKey()
                                    + "."
                                    + column.columnName().toLowerCase(Locale.ROOT),
                            key -> new ArrayList<>())
                    .add(column);
        }
        return new Mappings(types, columns);
    }

    private record Mappings(
            Map<String, List<String>> typesByTable,
            Map<String, List<MappedSourceColumn>> propertiesByColumn) {
        static final Mappings EMPTY = new Mappings(Map.of(), Map.of());
    }

    /** 가린 값과, 그 안에서 예시 값을 가린 근거 항목이 있었는지. */
    private record Masked(Object value, boolean withheld) {}

    /** 근거 항목을 가린 맵의 자유 문장·문서 칸을 고정 문구로 바꾼다. 칸 이름은 남긴다. */
    private static void withholdText(Map<String, Object> copy) {
        for (String field : FREE_TEXT_FIELDS) {
            if (copy.get(field) instanceof String text && !text.isBlank()) {
                copy.put(field, WITHHELD_TEXT);
            }
        }
        for (String field : TEXT_LIST_FIELDS) {
            if (copy.get(field) instanceof List<?> list) {
                List<Object> replaced = new ArrayList<>(list.size());
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map && map.get("text") instanceof String) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        map.forEach((key, value) -> item.put(String.valueOf(key), value));
                        item.put("text", WITHHELD_TEXT);
                        replaced.add(item);
                    } else {
                        replaced.add(element);
                    }
                }
                copy.put(field, replaced);
            }
        }
        for (String field : LABEL_MAP_FIELDS) {
            if (copy.get(field) instanceof Map<?, ?> labels) {
                Map<String, Object> replaced = new LinkedHashMap<>();
                labels.forEach((key, value) -> replaced.put(String.valueOf(key), WITHHELD_TEXT));
                copy.put(field, replaced);
            }
        }
    }

    /**
     * 개정 저장(사람이 고친 근거)을 저장할 근거로 바꾼다(403). 화면은 가린 응답(049·선택지)의 근거를 받아 고친 뒤 통째로 보내므로, 그대로 저장하면 가린 문구가
     * 원본을 영구히 덮는다. 그래서 요청의 각 자리가 "저장된 근거를 이 사용자 기준으로 가린 값"과 같으면 그 자리는 저장된 원본으로 되돌린다. 사용자가 실제로 바꾼
     * 자리는 바꾼 값을 쓴다. 가린 것이 없으면 요청 그대로다.
     *
     * @param stored 지금 저장된 근거. 없으면 요청 그대로다
     */
    public Map<String, Object> restore(
            DataAccess access, Map<String, Object> stored, Map<String, Object> requested) {
        if (stored == null || requested == null) {
            return requested;
        }
        Map<String, Object> masked =
                show(access, Collections.singletonList(stored)).getFirst().evidence();
        if (masked.equals(stored)) {
            return requested;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = (Map<String, Object>) restore(requested, masked, stored);
        return restored;
    }

    private static Object restore(Object requested, Object masked, Object stored) {
        if (Objects.equals(requested, masked)) {
            return stored;
        }
        if (requested instanceof Map<?, ?> request
                && masked instanceof Map<?, ?> maskedMap
                && stored instanceof Map<?, ?> storedMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            request.forEach(
                    (key, value) ->
                            result.put(
                                    String.valueOf(key),
                                    maskedMap.containsKey(key) && storedMap.containsKey(key)
                                            ? restore(value, maskedMap.get(key), storedMap.get(key))
                                            : value));
            return result;
        }
        if (requested instanceof List<?> request
                && masked instanceof List<?> maskedList
                && stored instanceof List<?> storedList
                && request.size() == maskedList.size()
                && maskedList.size() == storedList.size()) {
            List<Object> result = new ArrayList<>(request.size());
            for (int index = 0; index < request.size(); index++) {
                result.add(
                        restore(request.get(index), maskedList.get(index), storedList.get(index)));
            }
            return result;
        }
        return requested;
    }

    /**
     * 근거 트리를 복사하면서 가린다. 근거 항목을 하나라도 가렸으면 그 항목을 품은 맵(근거 자신과 그 위)의 자유 문장(reason·holdReason)도 고정 문구로
     * 바꾼다. AI는 이 문장에 원천 값 예시를 넣기도 해서(예: "불일치 예: ..."), 항목만 가리면 같은 값이 문장으로 나간다.
     */
    private static Masked mask(
            Object value,
            DataAccess access,
            Mappings mappings,
            AiProposalSourcePolicy policy,
            Set<String> revealed) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            boolean withheld = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Masked child = mask(entry.getValue(), access, mappings, policy, revealed);
                copy.put(String.valueOf(entry.getKey()), child.value());
                withheld |= child.withheld();
            }
            if (copy.get("locator") instanceof String locator && hasExample(copy)) {
                Set<String> shownRestricted = new TreeSet<>();
                if (policy.governed(locator)
                        || withheld(locator, access, mappings, shownRestricted)) {
                    if (copy.containsKey("values")) {
                        copy.put("values", List.of());
                    }
                    if (copy.containsKey("snippet")) {
                        copy.put("snippet", WITHHELD_SNIPPET);
                    }
                    withheld = true;
                } else {
                    revealed.addAll(shownRestricted);
                }
            }
            if (withheld) {
                withholdText(copy);
            }
            return new Masked(copy, withheld);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            boolean withheld = false;
            for (Object item : list) {
                Masked child = mask(item, access, mappings, policy, revealed);
                copy.add(child.value());
                withheld |= child.withheld();
            }
            return new Masked(copy, withheld);
        }
        return new Masked(value, false);
    }

    /** 내보낼 예시 값이 있는가. 비었거나 이미 가린 근거는 열람 기록 대상이 아니다. */
    private static boolean hasExample(Map<String, Object> item) {
        boolean values = item.get("values") instanceof List<?> list && !list.isEmpty();
        boolean snippet =
                item.get("snippet") instanceof String text
                        && !text.isBlank()
                        && !text.equals(WITHHELD_SNIPPET)
                        && !text.equals(AiProposalSourcePolicy.WITHHELD_SNIPPET);
        return values || snippet;
    }

    /**
     * 위치가 볼 수 없는 유형의 표이거나 가려야 하는 민감 속성의 컬럼(묶은 컬럼은 하나라도)인가. 아니면 보여 주는 민감 속성을 shownRestricted에 담는다.
     * 컬럼 이름에 마침표가 있을 수 있어 표 뒤는 더 쪼개지 않는다(399와 같다).
     */
    private static boolean withheld(
            String locator, DataAccess access, Mappings mappings, Set<String> shownRestricted) {
        String[] parts = locator.split("\\.", 3);
        if (parts.length < 2) {
            return false;
        }
        String table = (parts[0] + "." + parts[1]).toLowerCase(Locale.ROOT);
        for (String type : mappings.typesByTable().getOrDefault(table, List.of())) {
            if (access.hasType(type) && !access.canView(type)) {
                return true;
            }
        }
        if (parts.length < 3) {
            return false;
        }
        List<String> columns = new ArrayList<>(List.of(parts[2].split("\\+")));
        columns.add(parts[2]);
        for (String column : columns) {
            for (MappedSourceColumn mapped :
                    mappings.propertiesByColumn()
                            .getOrDefault(
                                    table + "." + column.toLowerCase(Locale.ROOT), List.of())) {
                String type = mapped.objectTypeCode();
                if (!access.hasType(type)) {
                    continue;
                }
                if (!access.canView(type) || access.hidesValue(type, mapped.propertyCode())) {
                    return true;
                }
                if (access.revealsValue(type, mapped.propertyCode())) {
                    shownRestricted.add(type + "." + mapped.propertyCode());
                }
            }
        }
        return false;
    }

    private static void collectTables(Object value, Set<String> tables) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("locator") instanceof String locator) {
                String[] parts = locator.split("\\.", 3);
                if (parts.length >= 2) {
                    tables.add((parts[0] + "." + parts[1]).toLowerCase(Locale.ROOT));
                }
            }
            map.values().forEach(child -> collectTables(child, tables));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectTables(child, tables));
        }
    }
}

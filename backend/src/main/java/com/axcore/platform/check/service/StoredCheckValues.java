package com.axcore.platform.check.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.check.repository.CandidateCheckRepository;
import com.axcore.platform.check.repository.CandidateCheckRepository.CandidateRevision;
import com.axcore.platform.check.repository.CandidateCheckRepository.MappingProposal;
import com.axcore.platform.check.repository.LinkMappingRuleRepository;
import com.axcore.platform.check.repository.LinkMappingRuleRepository.ConditionRow;
import com.axcore.platform.check.repository.LinkMappingRuleRepository.RuleRow;
import com.axcore.platform.mapping.repository.MappedSourceColumn;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 검사가 저장해 둔 객체 값 사본을 사람에게 돌려줄 때 조회 범위({@link DataAccess})를 다시 적용한다(S15P31C106-403). 검사 결과 표본(062),
 * 문제의 원본값·변환값·근거(065~068), 재검증 기록(070), 재검증 비교(059 비교)가 쓴다. 저장할 때가 아니라 돌려줄 때 보므로, 저장 뒤 속성이 민감으로
 * 바뀌거나 유형의 그룹이 바뀌어도 지금 기준으로 가린다.
 *
 * <ul>
 *   <li>값을 낸 규칙(승인 규칙 개정본, 후보 시험이면 그 후보 개정본)을 지금 정의로 다시 읽는다. 출발·도착 유형을 모두 볼 수 있고 비교 조건의 출발·도착 속성이
 *       모두 가릴 값이 아닐 때만 비교값(rawValue·transformedValue·transformedValues)과 원천 키(sourceKey·targetKey)를
 *       보여 준다. 하나라도 아니면 모두 비운다. 대응한 행은 양쪽 비교값이 같아서, 한쪽만 비우면 다른 쪽 값과 원천 키로 알아낼 수 있다(후보 시험 400과 같은
 *       기준).
 *   <li>값을 보여 줄 때도 원천 키 컬럼이 민감 속성을 담고 보는 사람이 그 값을 볼 수 없으면 그 키 값만 비운다(객체 조회의 원천 키 가림과 같다).
 *   <li>규칙을 찾지 못하거나 읽을 수 없으면 모두 비운다.
 *   <li>후보 시험의 유형이 아직 만들지 않은 후보 유형이면 그 쪽에는 조회 범위가 없다. 그 표본은 후보 시험이 저장할 때 원천 데이터 정책으로 가렸다(400).
 *   <li>비운 칸 이름을 돌려준다. 응답은 이것을 restrictedFields로 내보내, 값이 없는 것과 가린 것을 화면이 나눌 수 있게 한다.
 *   <li>가리지 않은 민감 값이 있으면 그 속성({@code 유형코드.속성코드})을 revealed로 돌려준다. 응답을 만드는 서비스가 열람 기록(394)을 남긴다.
 * </ul>
 *
 * <p>값을 사람에게 내보내지 않는 검사 배치는 이 클래스를 거치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StoredCheckValues {

    /** 비교값 칸. 조건 순서대로 이은 원본값·변환값과 조건 번호별 변환값이다. */
    static final List<String> VALUE_FIELDS =
            List.of("rawValue", "transformedValue", "transformedValues");

    static final String SOURCE_KEY = "sourceKey";
    static final String TARGET_KEY = "targetKey";

    private final LinkMappingRuleRepository rules;
    private final CandidateCheckRepository candidates;
    private final LinkTypeRepository linkTypes;
    private final ObjectTypeRepository objectTypes;
    private final PropertySourceMappingRepository propertyMappings;

    /** 요청 하나 동안 쓰는 판정기. 같은 규칙과 유형은 한 번만 읽는다. */
    public Viewer viewer(DataAccess access) {
        access.requireObjectValues();
        return new Viewer(access);
    }

    /**
     * 규칙 하나의 판정.
     *
     * @param shown 비교값과 원천 키를 보여 주는가
     * @param sourceTypeCode 출발 유형 코드. 규칙을 읽지 못했으면 null
     * @param targetTypeCode 도착 유형 코드. 규칙을 읽지 못했으면 null
     * @param revealed 보여 주는 비교값 중 민감 속성({@code 유형코드.속성코드})
     */
    public record RuleValues(
            boolean shown, String sourceTypeCode, String targetTypeCode, List<String> revealed) {

        public RuleValues {
            revealed = List.copyOf(revealed);
        }

        static RuleValues hidden() {
            return new RuleValues(false, null, null, List.of());
        }
    }

    /**
     * 가린 결과.
     *
     * @param values 가린 뒤의 칸. 원래 맵에 있던 칸만 있다
     * @param restrictedFields 비운 칸 이름(정렬)
     * @param revealed 가리지 않고 내보내는 민감 속성({@code 유형코드.속성코드}, 정렬)
     */
    public record Masked(
            Map<String, Object> values, List<String> restrictedFields, List<String> revealed) {}

    /** 한 요청의 조회 범위로 판정한다. 규칙·유형별 결과를 기억하므로 요청 밖에서 다시 쓰지 않는다. */
    public class Viewer {

        private final DataAccess access;
        private final Map<Long, RuleValues> byRule = new HashMap<>();
        private final Map<Long, RuleValues> byCandidateRun = new HashMap<>();
        private final Map<String, List<MappedSourceColumn>> columnsByType = new HashMap<>();

        Viewer(DataAccess access) {
            this.access = access;
        }

        /** 승인 규칙 개정본이 낸 값. ID가 없거나 규칙이 없으면 모두 가린다. */
        public RuleValues rule(Long linkMappingId) {
            if (linkMappingId == null) {
                return RuleValues.hidden();
            }
            return byRule.computeIfAbsent(linkMappingId, this::loadRule);
        }

        /** 후보 시험(검사 실행 하나가 후보 개정본 하나를 시험한다)이 낸 값. 후보를 읽을 수 없으면 모두 가린다. */
        public RuleValues candidate(long checkRunId) {
            return byCandidateRun.computeIfAbsent(checkRunId, this::loadCandidate);
        }

        /**
         * 저장된 칸을 가린다. 원래 맵은 바꾸지 않는다. 비교값 칸({@link #VALUE_FIELDS})과 원천 키 칸(sourceKey·targetKey) 밖의
         * 칸은 그대로 둔다. 비운 칸은 값이 있었을 때만 restrictedFields에 넣는다.
         */
        public Masked mask(RuleValues rule, Map<String, Object> stored) {
            Map<String, Object> values = new LinkedHashMap<>(stored);
            Set<String> restricted = new TreeSet<>();
            Set<String> revealed = new TreeSet<>();
            if (!rule.shown()) {
                for (String field : fields(values)) {
                    if (values.get(field) != null) {
                        restricted.add(field);
                    }
                    values.put(field, null);
                }
                return new Masked(values, List.copyOf(restricted), List.of());
            }
            if (VALUE_FIELDS.stream().anyMatch(field -> values.get(field) != null)) {
                revealed.addAll(rule.revealed());
            }
            maskKey(values, SOURCE_KEY, rule.sourceTypeCode(), restricted, revealed);
            maskKey(values, TARGET_KEY, rule.targetTypeCode(), restricted, revealed);
            return new Masked(values, List.copyOf(restricted), List.copyOf(revealed));
        }

        /**
         * 검사 결과 표본 한 행을 가린다. 비운 칸은 표본의 restrictedFields에 더한다(후보 시험이 저장 때 비운 칸과 합친다). 원래 맵은 바꾸지 않는다.
         */
        public Masked sample(RuleValues rule, Map<String, Object> sample) {
            Masked masked = mask(rule, sample);
            Set<String> restricted = new TreeSet<>(masked.restrictedFields());
            if (sample.get("restrictedFields") instanceof Collection<?> stored) {
                stored.forEach(field -> restricted.add(String.valueOf(field)));
            }
            Map<String, Object> values = new LinkedHashMap<>(masked.values());
            if (!restricted.isEmpty()) {
                values.put("restrictedFields", List.copyOf(restricted));
            }
            return new Masked(values, List.copyOf(restricted), masked.revealed());
        }

        private static List<String> fields(Map<String, Object> values) {
            List<String> fields = new ArrayList<>();
            for (String field : VALUE_FIELDS) {
                if (values.containsKey(field)) {
                    fields.add(field);
                }
            }
            for (String field : List.of(SOURCE_KEY, TARGET_KEY)) {
                if (values.containsKey(field)) {
                    fields.add(field);
                }
            }
            return fields;
        }

        /**
         * 원천 키 값을 객체 조회의 원천 키 가림({@code ObjectDisplaySql})과 같은 기준으로 비운다. 키 이름은 남긴다. 보는 사람이 그 유형의 민감
         * 값을 볼 수 없으면, 일반 속성에만 매핑돼 있다고 확인된 컬럼만 보여 준다. 매핑이 없거나(지운 매핑 포함) 민감 속성에 매핑된 컬럼은 비운다. 같은 이름의
         * 컬럼이 유형의 다른 원천 표에서 민감 속성에 매핑돼도 비운다(가리는 쪽으로). 아직 없는 후보 유형이면 범위가 없으므로 그대로 둔다.
         */
        private void maskKey(
                Map<String, Object> values,
                String field,
                String typeCode,
                Set<String> restricted,
                Set<String> revealed) {
            if (!(values.get(field) instanceof Map<?, ?> key)
                    || typeCode == null
                    || !access.hasType(typeCode)) {
                return;
            }
            boolean canViewRestricted = access.canViewRestricted(typeCode);
            Map<String, List<String>> propertiesByColumn = new HashMap<>();
            for (MappedSourceColumn mapped : columns(typeCode)) {
                propertiesByColumn
                        .computeIfAbsent(
                                mapped.columnName().toLowerCase(Locale.ROOT),
                                column -> new ArrayList<>())
                        .add(mapped.propertyCode());
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            key.forEach((column, value) -> copy.put(String.valueOf(column), value));
            boolean changed = false;
            for (Map.Entry<String, Object> entry : copy.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                List<String> properties =
                        propertiesByColumn.getOrDefault(
                                entry.getKey().toLowerCase(Locale.ROOT), List.of());
                if (!canViewRestricted
                        && (properties.isEmpty()
                                || properties.stream()
                                        .anyMatch(code -> access.hidesValue(typeCode, code)))) {
                    entry.setValue(null);
                    changed = true;
                    continue;
                }
                properties.stream()
                        .filter(code -> access.revealsValue(typeCode, code))
                        .forEach(code -> revealed.add(typeCode + "." + code));
            }
            if (changed) {
                values.put(field, copy);
                restricted.add(field);
            }
        }

        private List<MappedSourceColumn> columns(String typeCode) {
            return columnsByType.computeIfAbsent(
                    typeCode, code -> propertyMappings.findMappedColumnsOfTypes(List.of(code)));
        }

        private RuleValues loadRule(long linkMappingId) {
            Optional<RuleRow> rule = rules.findRule(linkMappingId);
            if (rule.isEmpty()) {
                return RuleValues.hidden();
            }
            String source = rule.get().sourceTypeCode();
            String target = rule.get().targetTypeCode();
            List<String[]> conditions = new ArrayList<>();
            for (ConditionRow row : rules.findConditions(linkMappingId)) {
                String sourceProperty = propertyCode(row.sourceProperty(), source);
                String targetProperty = propertyCode(row.targetProperty(), target);
                if (sourceProperty == null || targetProperty == null) {
                    return RuleValues.hidden();
                }
                conditions.add(new String[] {sourceProperty, targetProperty});
            }
            return decide(source, target, conditions, false);
        }

        private RuleValues loadCandidate(long checkRunId) {
            Optional<CandidateRevision> found = candidates.findRevision(checkRunId);
            if (found.isEmpty() || !"LINK_RULE".equals(found.get().kind())) {
                return RuleValues.hidden();
            }
            CandidateRevision candidate = found.get();
            JsonNode payload = candidate.payload();
            Optional<String[]> relation = relation(payload, candidate.analysisRunId());
            if (relation.isEmpty() || !payload.path("conditions").isArray()) {
                return RuleValues.hidden();
            }
            String source = relation.get()[0];
            String target = relation.get()[1];
            List<String[]> conditions = new ArrayList<>();
            for (JsonNode condition : payload.path("conditions")) {
                String sourceProperty =
                        propertyCode(text(condition.path("sourceProperty")), source);
                String targetProperty =
                        propertyCode(text(condition.path("targetProperty")), target);
                if (sourceProperty == null || targetProperty == null) {
                    return RuleValues.hidden();
                }
                conditions.add(new String[] {sourceProperty, targetProperty});
            }
            return decide(source, target, conditions, true);
        }

        /**
         * 후보의 출발·도착 유형 코드. 후보 시험({@code CandidateCheckPlanLoader})과 같은 순서로 찾는다: 관계 유형 ID → 있는 관계 유형
         * 코드 → 후보에 담긴 관계 유형 정의 → 같은 분석의 검토 전 관계 유형 후보.
         */
        private Optional<String[]> relation(JsonNode payload, Long analysisRunId) {
            JsonNode id = payload.path("linkTypeId");
            String code = text(payload.path("linkTypeCode"));
            Optional<LinkType> existing;
            if (id.isIntegralNumber()) {
                existing = linkTypes.findById(id.asLong());
                if (existing.isEmpty()) {
                    return Optional.empty();
                }
            } else {
                existing = code == null ? Optional.empty() : linkTypes.findByCode(code);
            }
            if (existing.isPresent()) {
                Optional<String> source =
                        objectTypes
                                .findById(existing.get().getSourceTypeId())
                                .map(ObjectType::getCode);
                Optional<String> target =
                        objectTypes
                                .findById(existing.get().getTargetTypeId())
                                .map(ObjectType::getCode);
                if (source.isEmpty() || target.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new String[] {source.get(), target.get()});
            }
            if (code == null) {
                return Optional.empty();
            }
            JsonNode definition = payload.path("linkType");
            if (!definition.isObject()) {
                if (analysisRunId == null) {
                    return Optional.empty();
                }
                List<JsonNode> pending =
                        candidates.findMappingProposals(analysisRunId).stream()
                                .filter(p -> "LINK_TYPE".equals(p.kind()))
                                .map(MappingProposal::payload)
                                .filter(p -> code.equals(text(p.path("code"))))
                                .toList();
                if (pending.size() != 1) {
                    return Optional.empty();
                }
                definition = pending.getFirst();
            }
            String source = text(definition.path("sourceTypeCode"));
            String target = text(definition.path("targetTypeCode"));
            if (!code.equals(text(definition.path("code"))) || source == null || target == null) {
                return Optional.empty();
            }
            return Optional.of(new String[] {source, target});
        }

        /**
         * 비교 조건이 없는 승인 규칙은 검사하지 않아 값 사본이 생기지 않는다. 그런 규칙은 유형 범위만 본다. 조건이 없는 후보는 읽을 수 없는 후보로 보고 가린다.
         *
         * @param newTypesAllowed 후보 시험이면 true. 아직 없는 후보 유형은 조회 범위가 없으므로 그 쪽은 막지 않는다. 승인 규칙의 유형은 늘
         *     있으므로 범위에 없으면 가린다.
         */
        private RuleValues decide(
                String source, String target, List<String[]> conditions, boolean newTypesAllowed) {
            if ((conditions.isEmpty() && newTypesAllowed)
                    || !visible(source, newTypesAllowed)
                    || !visible(target, newTypesAllowed)) {
                return RuleValues.hidden();
            }
            Set<String> revealed = new TreeSet<>();
            for (String[] condition : conditions) {
                for (int side = 0; side < 2; side++) {
                    String type = side == 0 ? source : target;
                    String property = condition[side];
                    if (!access.hasType(type)) {
                        continue;
                    }
                    if (access.hidesValue(type, property)) {
                        return new RuleValues(false, source, target, List.of());
                    }
                    if (access.revealsValue(type, property)) {
                        revealed.add(type + "." + property);
                    }
                }
            }
            return new RuleValues(true, source, target, List.copyOf(revealed));
        }

        private boolean visible(String typeCode, boolean newTypesAllowed) {
            if (access.hasType(typeCode)) {
                return access.canView(typeCode);
            }
            return newTypesAllowed;
        }
    }

    /** {@code 유형코드.속성코드} 또는 속성 코드만 받은 이름에서 속성 코드를 꺼낸다. 다른 유형을 가리키거나 비었으면 null이다. */
    static String propertyCode(String reference, String typeCode) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        int dot = reference.lastIndexOf('.');
        if (dot == -1) {
            return reference;
        }
        if (!reference.substring(0, dot).equals(typeCode) || dot == reference.length() - 1) {
            return null;
        }
        return reference.substring(dot + 1);
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}

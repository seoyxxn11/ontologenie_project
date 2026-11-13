package com.axcore.platform.review.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.review.repository.ProposalReferenceRepository;
import com.axcore.platform.review.repository.ProposalReferenceRepository.ObjectType;
import com.axcore.platform.review.repository.ProposalReferenceRepository.Relation;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProposalPayloadValidator {
    private final ProposalReferenceRepository references;
    private final PropertyDefinitionRepository properties;

    public Set<Long> validate(
            String kind, Map<String, Object> payload, Long analysisRunId, String field) {
        Set<Long> types = new HashSet<>();
        if (!"LINK_RULE".equals(kind)) {
            // 다른 종류의 후보는 API-105의 JSON 객체 계약을 유지한다.
            if ("OBJECT_TYPE".equals(kind) && payload.get("code") instanceof String code) {
                references.objectType(code).ifPresent(type -> types.add(type.id()));
            }
            for (String key : List.of("objectTypeCode", "sourceTypeCode", "targetTypeCode")) {
                if (payload.get(key) instanceof String code) {
                    references.objectType(code).ifPresent(type -> types.add(type.id()));
                }
            }
            return types;
        }
        Relation relation = resolveRelation(payload, analysisRunId, field);
        if (relation.source().id() != null) types.add(relation.source().id());
        if (relation.target().id() != null) types.add(relation.target().id());
        if (!(payload.get("conditions") instanceof List<?> conditions) || conditions.isEmpty()) {
            throw BusinessException.invalidField(field + ".conditions", "비교 조건을 하나 이상 지정해 주세요.");
        }
        for (int i = 0; i < conditions.size(); i++) {
            String path = field + ".conditions[" + i + "]";
            if (!(conditions.get(i) instanceof Map<?, ?> condition)) {
                throw BusinessException.invalidField(path, "조건은 JSON 객체여야 합니다.");
            }
            String source = text(condition.get("sourceProperty"), path + ".sourceProperty");
            String target = text(condition.get("targetProperty"), path + ".targetProperty");
            String operator = text(condition.get("operator"), path + ".operator");
            List<TransformStep> sourceTransforms =
                    transforms(condition.get("sourceTransforms"), path + ".sourceTransforms");
            List<TransformStep> targetTransforms =
                    transforms(condition.get("targetTransforms"), path + ".targetTransforms");
            try {
                new CheckCondition(
                        i + 1, source, target, operator, sourceTransforms, targetTransforms);
            } catch (IllegalArgumentException ex) {
                throw BusinessException.invalidField(path, ex.getMessage());
            }
            validateProperty(relation.source(), source, analysisRunId, path + ".sourceProperty");
            validateProperty(relation.target(), target, analysisRunId, path + ".targetProperty");
        }
        rejectRestrictedFilter(
                relation.source(), payload.get("sourceFilter"), field + ".sourceFilter");
        rejectRestrictedFilter(
                relation.target(), payload.get("targetFilter"), field + ".targetFilter");
        return types;
    }

    /**
     * 출발·도착 조건({@code {property, equals}})에 이미 있는 민감 속성을 쓰면 접수 단계에서 알린다(384·400). 형식 검사는 승인
     * 반영(LinkRuleApplier)이 한다.
     */
    private void rejectRestrictedFilter(ObjectType type, Object filter, String field) {
        if (type.id() == null
                || !(filter instanceof Map<?, ?> condition)
                || !(condition.get("property") instanceof String reference)) {
            return;
        }
        int dot = reference.lastIndexOf('.');
        if (dot >= 0 && !reference.substring(0, dot).equals(type.code())) {
            return;
        }
        String code = reference.substring(dot + 1);
        var existing = properties.findByObjectTypeIdAndCode(type.id(), code);
        if (existing.isPresent()
                && existing.get().getSensitivity() == PropertySensitivity.RESTRICTED) {
            throw BusinessException.invalidField(
                    field + ".property", "민감 속성은 연결 규칙의 출발·도착 조건에 쓸 수 없습니다.");
        }
    }

    private Relation resolveRelation(
            Map<String, Object> payload, Long analysisRunId, String field) {
        Object rawId = payload.get("linkTypeId");
        Object rawCode = payload.get("linkTypeCode");
        if (rawId != null) {
            if (!(rawId instanceof Integer || rawId instanceof Long)
                    || ((Number) rawId).longValue() < 1) {
                throw BusinessException.invalidField(field + ".linkTypeId", "양의 정수 ID를 지정해 주세요.");
            }
            Relation relation =
                    references
                            .relationById(((Number) rawId).longValue())
                            .orElseThrow(
                                    () ->
                                            BusinessException.invalidField(
                                                    field + ".linkTypeId", "존재하지 않는 관계 유형입니다."));
            if (rawCode != null) {
                Relation byCode =
                        references
                                .relationByCode(text(rawCode, field + ".linkTypeCode"))
                                .orElseThrow(
                                        () ->
                                                BusinessException.invalidField(
                                                        field + ".linkTypeCode",
                                                        "존재하지 않는 관계 유형입니다."));
                // 같은 양 끝 유형이어도 서로 다른 관계일 수 있으므로 ID도 확인한다.
                if (!byCode.id().equals(relation.id())) {
                    throw BusinessException.invalidField(
                            field + ".linkTypeCode", "관계 유형 ID와 code가 일치하지 않습니다.");
                }
            }
            return relation;
        }
        if (analysisRunId == null) {
            throw BusinessException.invalidField(field + ".linkTypeId", "기존 관계 유형 ID를 지정해 주세요.");
        }
        String code = text(rawCode, field + ".linkTypeCode");
        return references
                .relationByCode(code)
                .orElseGet(
                        () -> {
                            List<Map<String, Object>> pending =
                                    references.pendingDefinitions(analysisRunId, "LINK_TYPE", code);
                            if (pending.size() == 1) {
                                return pendingRelation(
                                        pending.getFirst(), analysisRunId, field, field);
                            }
                            // AI 관계 후보는 관계 유형을 payload.linkType에 함께 담아 보낸다(API-105).
                            // 이미 있는 관계 유형이나 같은 분석의 관계 유형 후보가 없을 때만 쓴다.
                            Map<String, Object> embedded =
                                    pending.isEmpty()
                                            ? embeddedLinkType(payload, code, field)
                                            : null;
                            if (embedded == null) {
                                throw BusinessException.invalidField(
                                        field + ".linkTypeCode", "같은 분석의 관계 유형 후보를 확인해 주세요.");
                            }
                            String at = field + ".linkType";
                            return pendingRelation(
                                    embedded,
                                    analysisRunId,
                                    at + ".sourceTypeCode",
                                    at + ".targetTypeCode");
                        });
    }

    private Relation pendingRelation(
            Map<String, Object> definition,
            long analysisRunId,
            String sourceField,
            String targetField) {
        return new Relation(
                null,
                resolveType(
                        text(definition.get("sourceTypeCode"), sourceField),
                        analysisRunId,
                        sourceField),
                resolveType(
                        text(definition.get("targetTypeCode"), targetField),
                        analysisRunId,
                        targetField));
    }

    private ObjectType resolveType(String code, long analysisRunId, String field) {
        return references
                .objectType(code)
                .orElseGet(
                        () -> {
                            if (references
                                            .pendingDefinitions(analysisRunId, "OBJECT_TYPE", code)
                                            .size()
                                    != 1) {
                                throw BusinessException.invalidField(
                                        field, "관계가 참조하는 객체 유형 후보를 확인해 주세요.");
                            }
                            return new ObjectType(null, code);
                        });
    }

    private void validateProperty(
            ObjectType type, String reference, Long analysisRunId, String field) {
        String property = reference;
        int dot = reference.indexOf('.');
        if (dot >= 0) {
            if (!reference.substring(0, dot).equals(type.code())) {
                throw BusinessException.invalidField(field, "관계의 출발·도착 객체 유형과 일치하지 않습니다.");
            }
            property = reference.substring(dot + 1);
        }
        // 아직 승인 전인 객체 유형 후보(id 없음)는 확정 속성이 없으므로 후보 데이터만 본다.
        if (type.id() != null) {
            var existing = properties.findByObjectTypeIdAndCode(type.id(), property);
            if (existing.isPresent()) {
                // 승인 반영(LinkRuleApplier)도 거절하지만, 후보 시험 전에 접수 단계에서 알린다(384·400).
                if (existing.get().getSensitivity() == PropertySensitivity.RESTRICTED) {
                    throw BusinessException.invalidField(field, "민감 속성은 연결 규칙의 조건에 쓸 수 없습니다.");
                }
                return;
            }
        }
        if (analysisRunId != null
                && references.pendingDefinitions(analysisRunId, "PROPERTY", property).stream()
                        .anyMatch(candidate -> type.code().equals(candidate.get("objectTypeCode"))))
            return;
        // AI 객체 후보는 속성 목록을 payload.properties에 함께 담아 보낸다(API-105).
        if (analysisRunId != null
                && references.pendingBundledProperty(analysisRunId, type.code(), property)) return;
        throw BusinessException.invalidField(field, "해당 객체 유형의 속성이 아닙니다.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> embeddedLinkType(
            Map<String, Object> payload, String code, String field) {
        Object raw = payload.get("linkType");
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> linkType)) {
            throw BusinessException.invalidField(field + ".linkType", "관계 유형은 JSON 객체여야 합니다.");
        }
        if (!code.equals(linkType.get("code"))) {
            throw BusinessException.invalidField(
                    field + ".linkType.code", "linkTypeCode와 같은 관계 유형이어야 합니다.");
        }
        return (Map<String, Object>) linkType;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw BusinessException.invalidField(field, "비어 있지 않은 문자열을 지정해 주세요.");
        }
        return text;
    }

    private static List<TransformStep> transforms(Object raw, String field) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> steps))
            throw BusinessException.invalidField(field, "변환 목록이어야 합니다.");
        return steps.stream()
                .map(
                        value -> {
                            if (!(value instanceof Map<?, ?> step))
                                throw BusinessException.invalidField(field, "변환은 JSON 객체여야 합니다.");
                            String op = text(step.get("op"), field + ".op");
                            try {
                                return new TransformStep(
                                        TransformOperation.valueOf(op), step.get("arg"));
                            } catch (IllegalArgumentException ex) {
                                throw BusinessException.invalidField(
                                        field, "지원하는 변환 종류와 인자를 확인해 주세요.");
                            }
                        })
                .toList();
    }
}

package com.axcore.platform.review.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.global.error.BusinessException;
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
        return types;
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
                            if (pending.size() != 1) {
                                throw BusinessException.invalidField(
                                        field + ".linkTypeCode", "같은 분석의 관계 유형 후보를 확인해 주세요.");
                            }
                            Map<String, Object> definition = pending.getFirst();
                            return new Relation(
                                    null,
                                    resolveType(
                                            text(definition.get("sourceTypeCode"), field),
                                            analysisRunId,
                                            field),
                                    resolveType(
                                            text(definition.get("targetTypeCode"), field),
                                            analysisRunId,
                                            field));
                        });
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
        if (references.propertyExists(type, property)) return;
        if (analysisRunId != null
                && references.pendingDefinitions(analysisRunId, "PROPERTY", property).stream()
                        .anyMatch(candidate -> type.code().equals(candidate.get("objectTypeCode"))))
            return;
        throw BusinessException.invalidField(field, "해당 객체 유형의 속성이 아닙니다.");
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

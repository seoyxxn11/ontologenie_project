package com.axcore.platform.mapping.service;

import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.Draft;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.PropertyDraft;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장 전 매핑 초안(API 040)을 지금 정의와 맞춰 보고 전체 읽기와 같은 변환 계획({@link MappingPlan})으로 만든다. 요청 접수(api)와 배치 실행이
 * 같이 쓴다. 요청 뒤 정의가 바뀌었으면 배치에서 다시 걸린다.
 *
 * <ul>
 *   <li>형식 오류(키 컬럼 중복, 같은 속성 두 번, 변환 함수 이름·인자)는 400이다.
 *   <li>정의와 맞지 않는 초안(없는 객체 유형, 다른 유형의 속성, 이 테이블에 없는 컬럼, 앞뒤가 안 맞는 변환 단계), 읽을 수 없는 테이블, 아직 적재할 수 없는
 *       초안(행 조건, 문서 경로)은 422다. 전체 읽기({@code FullLoadPlanner})가 못 읽는 초안을 미리보기에서만 보여 주지 않는다.
 *   <li>원천 데이터 정책({@link SourceColumnPolicy}, 391): 금지 표·컬럼은 매핑과 같은 이유로 422다. 민감 컬럼도 미리보기에서는 읽지 않아
 *       422다. 미리보기는 관리자만 쓰고 결과에 원천 값이 그대로 담기는데, 관리자는 민감 값을 볼 수 없다(384). 민감 컬럼을 매핑하는 것 자체는 된다(속성이
 *       민감으로 올라간다). 요청 접수와 배치 실행이 같이 이 확인을 거치므로, 요청 뒤 정책이 바뀌어도 배치가 읽기 전에 막는다.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MappingPreviewPlanner {

    /**
     * 초안 계획의 매핑 ID. 저장된 매핑이 아니어서 어디에도 쓰지 않지만, 변환 결과({@code SyncedObject})가 1 이상을 요구해 저장된 매핑과 겹치지 않는
     * 값을 둔다.
     */
    public static final long DRAFT_MAPPING_ID = Long.MAX_VALUE;

    private final SourceResourceRepository resourceRepository;
    private final SourceColumnRepository columnRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final SourceColumnPolicy sourceColumnPolicy;

    /** 원천 테이블. 없으면 404, SYNC 계정이 읽을 수 없으면 422다. */
    @Transactional(readOnly = true)
    public SourceResource readableResource(long resourceId) {
        SourceResource resource =
                resourceRepository
                        .findById(resourceId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "source_resource",
                                                        "id",
                                                        resourceId)));
        if (!resource.isReadable()) {
            throw violation("id", "SYNC 계정으로 읽을 수 없는 원천 테이블입니다.");
        }
        return resource;
    }

    /** 초안을 확인하고 변환 계획을 만든다. 저장된 매핑이 아니어서 매핑 ID는 {@link #DRAFT_MAPPING_ID}, 버전은 0이다. */
    @Transactional(readOnly = true)
    public MappingPlan plan(long resourceId, Draft draft) {
        String resourceKey = readableResource(resourceId).getResourceKey();
        if (sourceColumnPolicy.forbidsTable(resourceKey)) {
            sourceColumnPolicy.requireCollectable(resourceKey, List.of(), "id");
        }
        if (!draft.rowFilterOrEmpty().isEmpty()) {
            throw violation("mapping.rowFilter", "행 조건이 있는 매핑은 아직 적재할 수 없어 미리볼 수 없습니다.");
        }
        ObjectType type =
                objectTypeRepository
                        .findById(draft.objectTypeId())
                        .orElseThrow(() -> violation("mapping.objectTypeId", "없는 객체 유형입니다."));

        Map<String, SourceColumn> columnsByName =
                columnRepository
                        .findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(resourceId)
                        .stream()
                        .filter(SourceColumn::isPresent)
                        .collect(
                                Collectors.toMap(SourceColumn::getColumnName, Function.identity()));
        Set<String> keyColumns = new LinkedHashSet<>();
        for (String column : draft.keyColumns()) {
            if (!keyColumns.add(column)) {
                throw BusinessException.invalidField("mapping.keyColumns", "같은 키 컬럼이 두 번 있습니다.");
            }
            if (!columnsByName.containsKey(column)) {
                throw violation("mapping.keyColumns", "이 원천 테이블에 없는 컬럼입니다: " + column);
            }
        }
        requireReadableInPreview(resourceKey, keyColumns, "mapping.keyColumns");

        List<PropertyDraft> drafts = draft.propertyMappingsOrEmpty();
        Set<Long> propertyIds = new HashSet<>();
        drafts.forEach(p -> propertyIds.add(p.propertyDefinitionId()));
        if (type.getDisplayPropertyId() != null) {
            propertyIds.add(type.getDisplayPropertyId());
        }
        Map<Long, PropertyDefinition> properties =
                propertyRepository.findAllById(propertyIds).stream()
                        .collect(Collectors.toMap(PropertyDefinition::getId, Function.identity()));
        Map<Long, SourceColumn> columnsById =
                columnsByName.values().stream()
                        .collect(Collectors.toMap(SourceColumn::getId, Function.identity()));

        Set<Long> seen = new HashSet<>();
        List<PropertyPlan> plans = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            PropertyDraft property = drafts.get(i);
            String field = "mapping.propertyMappings[" + i + "]";
            if (!seen.add(property.propertyDefinitionId())) {
                throw BusinessException.invalidField(
                        field + ".propertyDefinitionId", "같은 속성이 두 번 있습니다.");
            }
            PropertyDefinition definition = properties.get(property.propertyDefinitionId());
            if (definition == null || !definition.getObjectTypeId().equals(type.getId())) {
                throw violation(field + ".propertyDefinitionId", "이 객체 유형의 속성이 아닙니다.");
            }
            // 미리보기 결과는 가리지 않은 원천 값이라, 민감 속성(사람이 지정했든 정책으로 올랐든)은 읽지 않는다(391).
            if (definition.getSensitivity() == PropertySensitivity.RESTRICTED) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "민감 속성은 미리보기에서 읽지 않습니다. 그 속성을 빼고 미리보세요.",
                        Map.of(
                                "field",
                                field + ".propertyDefinitionId",
                                "reason",
                                "RESTRICTED_PROPERTY"));
            }
            if (property.sourcePath() != null) {
                throw violation(field + ".sourcePath", "문서 경로 매핑은 아직 적재할 수 없어 미리볼 수 없습니다.");
            }
            SourceColumn column = columnsById.get(property.sourceColumnId());
            if (column == null) {
                throw violation(field + ".sourceColumnId", "이 원천 테이블에 없는 컬럼입니다.");
            }
            requireReadableInPreview(
                    resourceKey, List.of(column.getColumnName()), field + ".sourceColumnId");
            List<TransformStep> steps;
            try {
                steps = TransformStepsValidator.parse(property.transformSteps());
                TransformStepsValidator.requireCompatible(steps, definition.getDataType());
            } catch (BusinessException e) {
                throw prefixed(e, field);
            }
            plans.add(
                    new PropertyPlan(
                            0,
                            0,
                            definition.getId(),
                            definition.getCode(),
                            definition.getDataType(),
                            definition.getRowVersion(),
                            column.getColumnName(),
                            steps));
        }
        PropertyDefinition display =
                type.getDisplayPropertyId() == null
                        ? null
                        : properties.get(type.getDisplayPropertyId());
        return new MappingPlan(
                DRAFT_MAPPING_ID,
                0,
                type.getId(),
                type.getRowVersion(),
                List.copyOf(keyColumns),
                display == null ? null : display.getCode(),
                plans);
    }

    /** 금지 컬럼은 매핑과 같은 오류로, 민감 컬럼은 미리보기에서 읽지 않는다는 오류로 거절한다(391). */
    private void requireReadableInPreview(
            String resourceKey, Collection<String> columns, String field) {
        sourceColumnPolicy.requireCollectable(resourceKey, columns, field);
        List<String> restricted =
                columns.stream()
                        .filter(
                                column ->
                                        sourceColumnPolicy.gradeOf(resourceKey, column)
                                                == SourceColumnGrade.RESTRICTED)
                        .toList();
        if (!restricted.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 민감 컬럼은 미리보기에서 읽지 않습니다. 그 컬럼을 빼고 미리보세요.",
                    Map.of(
                            "field",
                            field,
                            "resourceKey",
                            resourceKey,
                            "restrictedColumns",
                            restricted));
        }
    }

    private static BusinessException violation(String field, String message) {
        return new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION, message, Map.of("field", field));
    }

    // 변환 단계 확인은 038·039처럼 "transformSteps[...]" 필드로 오류를 낸다. 초안 안의 위치를 앞에 붙인다.
    private static BusinessException prefixed(BusinessException e, String prefix) {
        Object field = e.getDetails().get("field");
        if (field == null) {
            return e;
        }
        if (e.getErrorCode() == CommonErrorCode.VALIDATION_FAILED) {
            return BusinessException.invalidField(
                    prefix + "." + field, String.valueOf(e.getDetails().get("reason")));
        }
        Map<String, Object> details = new LinkedHashMap<>(e.getDetails());
        details.put("field", prefix + "." + field);
        return new BusinessException(e.getErrorCode(), e.getMessage(), details);
    }
}

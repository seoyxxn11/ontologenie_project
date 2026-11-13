package com.axcore.platform.mapping.service;

import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.domain.ReloadReason;
import com.axcore.platform.mapping.dto.PropertyMappingCreateRequest;
import com.axcore.platform.mapping.dto.PropertyMappingDeleteResponse;
import com.axcore.platform.mapping.dto.PropertyMappingResponse;
import com.axcore.platform.mapping.dto.PropertyMappingUpdateRequest;
import com.axcore.platform.mapping.dto.PropertyMappingWriteResponse;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.service.DefinitionSideEffects;
import com.axcore.platform.ontology.service.LinkKeyUsage;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 속성 원천 매핑 (API 037~039·101, FN-MAP-03). 원천 컬럼 값을 변환 단계를 거쳐 객체 속성에 넣는 규칙이다. 바꿀 때는 객체 매핑(182)과 같이 원천
 * 자원 행을 잠그고, 소속 객체 매핑이 켜져 있으면 그 자원의 CDC 기준 LSN을 비워 다음 수집이 테이블을 처음부터 다시 읽게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyMappingService {

    private final PropertySourceMappingRepository propertyMappingRepository;
    private final ObjectSourceMappingRepository objectMappingRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final SourceColumnRepository columnRepository;
    private final SourceCatalogService catalogService;
    private final SourceColumnPolicy sourceColumnPolicy;
    private final ObjectTypeRepository objectTypeRepository;
    private final LinkKeyUsage linkKeyUsage;
    private final SourceResourceRepository resourceRepository;
    private final DefinitionSideEffects sideEffects;
    private final MappedResourceReload mappedResourceReload;

    /** API 037. */
    public Page<PropertyMappingResponse> search(Long sourceMappingId, Pageable pageable) {
        return propertyMappingRepository
                .search(sourceMappingId, pageable)
                .map(PropertyMappingResponse::from);
    }

    /** API 038. 소속 객체 매핑이 켜져 있으면 다음 수집이 그 테이블을 처음부터 읽는다. */
    @Transactional
    public PropertyMappingWriteResponse create(PropertyMappingCreateRequest request) {
        requireColumnSource(request.sourceColumnId(), request.sourcePath());
        List<TransformStep> steps = TransformStepsValidator.parse(request.transformSteps());
        Map<Long, SourceResource> locked =
                lockResources(
                        List.of(request.sourceResourceId()),
                        request.propertyDefinitionId(),
                        request.sourceColumnId());
        SourceResource resource = locked.get(request.sourceResourceId());
        ObjectSourceMapping parent =
                findParent(request.sourceMappingId(), request.sourceResourceId());
        Target target =
                validateTarget(
                        parent,
                        request.propertyDefinitionId(),
                        request.sourceColumnId(),
                        request.sourceResourceId(),
                        steps);
        boolean raised = applySourcePolicy(resource, target);
        if (propertyMappingRepository.existsForProperty(
                parent.getId(), request.propertyDefinitionId(), null)) {
            throw duplicateProperty();
        }
        if (raised) {
            mappedResourceReload.reloadMapped(
                    target.property().getId(), locked, Set.of(resource.getId()));
        }
        PropertySourceMapping mapping =
                propertyMappingRepository.saveAndFlush(
                        PropertySourceMapping.createManual(
                                parent.getId(),
                                request.sourceResourceId(),
                                request.propertyDefinitionId(),
                                request.sourceColumnId(),
                                stored(request.transformSteps())));
        ReloadReason reloadReason = null;
        if (parent.isActive()) {
            catalogService.requireFullReload(resource);
            reloadReason = ReloadReason.PROPERTY_MAPPING_ADDED;
        }
        return PropertyMappingWriteResponse.of(PropertyMappingResponse.from(mapping), reloadReason);
    }

    /**
     * API 039. 보낸 값이 지금과 같으면 아무것도 바꾸지 않는다. 다른 객체 매핑으로 옮기면 옮기기 전·후 매핑 가운데 켜져 있는 쪽의 테이블을 모두 다시 읽는다(앞
     * 테이블의 객체에는 이 속성이 빠져야 하기 때문이다).
     */
    @Transactional
    public PropertyMappingWriteResponse update(Long id, PropertyMappingUpdateRequest request) {
        requireColumnSource(request.sourceColumnId(), request.sourcePath());
        List<TransformStep> steps = TransformStepsValidator.parse(request.transformSteps());
        PropertySourceMapping mapping = findMapping(id);
        if (request.rowVersion() != mapping.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", mapping.getRowVersion()));
        }
        List<Long> requested = List.of(mapping.getSourceResourceId(), request.sourceResourceId());
        Map<Long, SourceResource> locked =
                lockResources(requested, request.propertyDefinitionId(), request.sourceColumnId());
        ObjectSourceMapping previousParent = findParent(mapping.getSourceMappingId(), null);
        ObjectSourceMapping parent =
                findParent(request.sourceMappingId(), request.sourceResourceId());
        Target target =
                validateTarget(
                        parent,
                        request.propertyDefinitionId(),
                        request.sourceColumnId(),
                        request.sourceResourceId(),
                        steps);
        boolean raised = applySourcePolicy(locked.get(request.sourceResourceId()), target);
        if (propertyMappingRepository.existsForProperty(
                parent.getId(), request.propertyDefinitionId(), id)) {
            throw duplicateProperty();
        }

        List<Map<String, Object>> transformSteps = stored(request.transformSteps());
        ReloadReason reloadReason = null;
        if (mapping.differs(
                parent.getId(),
                request.sourceResourceId(),
                request.propertyDefinitionId(),
                request.sourceColumnId(),
                transformSteps)) {
            mapping.change(
                    parent.getId(),
                    request.sourceResourceId(),
                    request.propertyDefinitionId(),
                    request.sourceColumnId(),
                    transformSteps);
            Set<ObjectSourceMapping> affected =
                    new LinkedHashSet<>(List.of(previousParent, parent));
            for (ObjectSourceMapping affectedParent : affected) {
                if (affectedParent.isActive()) {
                    catalogService.requireFullReload(
                            locked.get(affectedParent.getSourceResourceId()));
                    reloadReason = ReloadReason.PROPERTY_MAPPING_CHANGED;
                }
            }
        }
        if (raised) {
            // 같은 값을 다시 저장해도 정책 때문에 민감으로 올렸으면 그동안 CDC가 건너뛴 변경을 되찾도록 다시 읽는다(391).
            if (reloadReason == null && parent.isActive()) {
                catalogService.requireFullReload(locked.get(request.sourceResourceId()));
                reloadReason = ReloadReason.PROPERTY_MAPPING_CHANGED;
            }
            mappedResourceReload.reloadMapped(
                    target.property().getId(), locked, new HashSet<>(requested));
        }
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        propertyMappingRepository.flush();
        return PropertyMappingWriteResponse.of(PropertyMappingResponse.from(mapping), reloadReason);
    }

    /** API 101. 소속 객체 매핑이 켜져 있으면 다음 수집이 테이블을 처음부터 읽어 객체에서 이 속성 값을 뺀다. */
    @Transactional
    public PropertyMappingDeleteResponse delete(Long id) {
        PropertySourceMapping mapping = findMapping(id);
        SourceResource resource = lockResource(mapping.getSourceResourceId());
        ObjectSourceMapping parent = findParent(mapping.getSourceMappingId(), null);
        propertyMappingRepository.delete(mapping);
        propertyMappingRepository.flush();
        ReloadReason reloadReason = null;
        if (parent.isActive()) {
            catalogService.requireFullReload(resource);
            reloadReason = ReloadReason.PROPERTY_MAPPING_DELETED;
        }
        return PropertyMappingDeleteResponse.of(
                id, mapping.getSourceMappingId(), mapping.getSourceResourceId(), reloadReason);
    }

    private PropertySourceMapping findMapping(Long id) {
        return propertyMappingRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "property_source_mapping", "id", id)));
    }

    private SourceResource lockResource(Long resourceId) {
        return catalogService
                .lockResource(resourceId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                        "원천 자원을 찾을 수 없습니다.",
                                        Map.of("field", "sourceResourceId")));
    }

    /** 객체 매핑을 찾는다. resourceId를 주면 그 매핑의 원천 자원과 같은지도 본다(DB 복합 FK와 같은 규칙). */
    private ObjectSourceMapping findParent(Long sourceMappingId, Long resourceId) {
        ObjectSourceMapping parent =
                objectMappingRepository
                        .findById(sourceMappingId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                                "객체 원천 매핑을 찾을 수 없습니다.",
                                                Map.of("field", "sourceMappingId")));
        if (resourceId != null && !parent.getSourceResourceId().equals(resourceId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "객체 원천 매핑의 원천 자원과 다릅니다.",
                    Map.of("field", "sourceResourceId"));
        }
        return parent;
    }

    /**
     * 원천 데이터 정책을 적용하고, 속성을 민감으로 올렸으면 true를 돌려준다(384·391). 금지 컬럼이면 거절한다. 민감 컬럼이면 속성을
     * 민감(RESTRICTED)으로 올린다. 속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다. 식별·연결 키 속성은 민감이 될 수 없어 거절하고, 대표 속성이면
     * 대표를 비운다(표시 이름은 원천 키로 대신한다).
     */
    private boolean applySourcePolicy(SourceResource resource, Target target) {
        String column = target.column().getColumnName();
        sourceColumnPolicy.requireCollectable(
                resource.getResourceKey(), List.of(column), "sourceColumnId");
        PropertyDefinition property = target.property();
        if (sourceColumnPolicy.gradeOf(resource.getResourceKey(), column)
                        != SourceColumnGrade.RESTRICTED
                || property.getSensitivity() == PropertySensitivity.RESTRICTED) {
            return false;
        }
        if (property.isIdentifier()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 민감 컬럼이라 식별 속성에 매핑할 수 없습니다.",
                    Map.of("field", "sourceColumnId", "column", column, "reason", "IDENTIFIER"));
        }
        if (linkKeyUsage.isUsed(property)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 민감 컬럼이라 연결 규칙의 조건에 쓰는 속성에 매핑할 수 없습니다.",
                    Map.of("field", "sourceColumnId", "column", column, "reason", "LINK_KEY"));
        }
        // 요청 대상(속성 매핑) 밖의 정의 행을 바꾸므로 감사 기록에 함께 남긴다.
        sideEffects.beforeUpdate("property_definition", property.getId());
        property.restrictBySourcePolicy();
        objectTypeRepository
                .findById(property.getObjectTypeId())
                .filter(type -> property.getId().equals(type.getDisplayPropertyId()))
                .ifPresent(
                        type -> {
                            sideEffects.beforeUpdate("object_type", type.getId());
                            type.update(
                                    type.getName(),
                                    type.getDescription(),
                                    type.getSynonyms(),
                                    type.getAccessGroup(),
                                    null);
                        });
        return true;
    }

    /**
     * 요청한 원천 자원을 잠근다. 이 요청이 속성을 민감으로 올릴 수 있으면(일반 속성에 민감 컬럼) 그 속성을 담는 다른 켜진 매핑의 자원도 함께 잠근다.
     * 수집(SyncRunJob)과 같이 모든 자원을 ID 순서로 한 번에 잡아 서로 기다리다 멈추지 않게 한다(391). 올렸을 때 그 자원들을 다시 읽기로
     * 표시한다({@link MappedResourceReload}). 391 이전 매핑이 있던 표는 적재 계획이 실패해 CDC가 건너뛰었으므로 빠진 변경을 되찾아야 한다.
     */
    private Map<Long, SourceResource> lockResources(
            List<Long> requested, Long propertyId, Long columnId) {
        Set<Long> ids = new TreeSet<>(requested);
        if (mayRaise(propertyId, columnId)) {
            ids.addAll(mappedResourceReload.activeResourceIds(propertyId));
        }
        Map<Long, SourceResource> locked = new HashMap<>();
        for (Long id : ids) {
            if (requested.contains(id)) {
                locked.put(id, lockResource(id));
            } else {
                catalogService.lockResource(id).ifPresent(resource -> locked.put(id, resource));
            }
        }
        return locked;
    }

    /** 잠그기 전에 읽은 값으로, 이 매핑이 일반 속성에 민감 컬럼을 담는지 본다. 잠근 뒤 다시 판단하므로 여기서는 잠글 범위만 정한다. */
    private boolean mayRaise(Long propertyId, Long columnId) {
        if (propertyId == null || columnId == null) {
            return false;
        }
        PropertyDefinition property = propertyRepository.findById(propertyId).orElse(null);
        SourceColumn column = columnRepository.findById(columnId).orElse(null);
        if (property == null
                || column == null
                || property.getSensitivity() == PropertySensitivity.RESTRICTED) {
            return false;
        }
        return resourceRepository
                .findById(column.getSourceResourceId())
                .map(
                        resource ->
                                sourceColumnPolicy.gradeOf(
                                                resource.getResourceKey(), column.getColumnName())
                                        == SourceColumnGrade.RESTRICTED)
                .orElse(false);
    }

    private record Target(PropertyDefinition property, SourceColumn column) {}

    /** 속성이 매핑의 객체 유형에 속하는지, 컬럼이 같은 자원에 지금 있는지, 변환 결과를 속성에 넣을 수 있는지. */
    private Target validateTarget(
            ObjectSourceMapping parent,
            Long propertyDefinitionId,
            Long sourceColumnId,
            Long resourceId,
            List<TransformStep> steps) {
        PropertyDefinition property =
                propertyRepository
                        .findById(propertyDefinitionId)
                        .filter(found -> found.getObjectTypeId().equals(parent.getObjectTypeId()))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                                "객체 원천 매핑의 객체 유형에 속한 속성이 아닙니다.",
                                                Map.of("field", "propertyDefinitionId")));
        SourceColumn column =
                columnRepository
                        .findById(sourceColumnId)
                        .filter(found -> found.getSourceResourceId().equals(resourceId))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                                "이 원천 자원의 컬럼이 아닙니다.",
                                                Map.of("field", "sourceColumnId")));
        if (!column.isPresent()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천에서 사라진 컬럼입니다.",
                    Map.of("field", "sourceColumnId"));
        }
        TransformStepsValidator.requireCompatible(steps, property.getDataType());
        return new Target(property, column);
    }

    /** 문서 경로 형식이 정해지기 전에는 표 컬럼만 매핑한다(2026-11-03). 경로를 보내면 무시하지 않고 거절한다. */
    private static void requireColumnSource(Long sourceColumnId, String sourcePath) {
        if (sourcePath != null) {
            throw BusinessException.invalidField(
                    "sourcePath", "문서 경로 매핑은 아직 지원하지 않습니다. sourceColumnId를 지정해 주세요.");
        }
        if (sourceColumnId == null) {
            throw BusinessException.invalidField("sourceColumnId", "원천 컬럼을 지정해 주세요.");
        }
    }

    private static List<Map<String, Object>> stored(List<TransformStepPayload> payloads) {
        return payloads == null
                ? List.of()
                : payloads.stream().map(TransformStepPayload::toStored).toList();
    }

    private static BusinessException duplicateProperty() {
        return new BusinessException(
                CommonErrorCode.CONFLICT,
                "이 객체 원천 매핑에 같은 속성의 매핑이 이미 있습니다.",
                Map.of("field", "propertyDefinitionId"));
    }
}

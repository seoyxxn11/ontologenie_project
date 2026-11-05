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
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.HashMap;
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
        SourceResource resource = lockResource(request.sourceResourceId());
        ObjectSourceMapping parent =
                findParent(request.sourceMappingId(), request.sourceResourceId());
        validateTarget(
                parent,
                request.propertyDefinitionId(),
                request.sourceColumnId(),
                request.sourceResourceId(),
                steps);
        if (propertyMappingRepository.existsForProperty(
                parent.getId(), request.propertyDefinitionId(), null)) {
            throw duplicateProperty();
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
        // 두 자원을 잠글 때 순서를 정해 두어 서로 기다리다 멈추지 않게 한다.
        Map<Long, SourceResource> locked = new HashMap<>();
        for (Long resourceId :
                new TreeSet<>(List.of(mapping.getSourceResourceId(), request.sourceResourceId()))) {
            locked.put(resourceId, lockResource(resourceId));
        }
        ObjectSourceMapping previousParent = findParent(mapping.getSourceMappingId(), null);
        ObjectSourceMapping parent =
                findParent(request.sourceMappingId(), request.sourceResourceId());
        validateTarget(
                parent,
                request.propertyDefinitionId(),
                request.sourceColumnId(),
                request.sourceResourceId(),
                steps);
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

    /** 속성이 매핑의 객체 유형에 속하는지, 컬럼이 같은 자원에 지금 있는지, 변환 결과를 속성에 넣을 수 있는지. */
    private void validateTarget(
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

package com.axcore.platform.mapping.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.MappingErrorCode;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.ReloadReason;
import com.axcore.platform.mapping.dto.ObjectMappingCreateRequest;
import com.axcore.platform.mapping.dto.ObjectMappingResponse;
import com.axcore.platform.mapping.dto.ObjectMappingUpdateRequest;
import com.axcore.platform.mapping.dto.ObjectMappingUpdateResponse;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.HashSet;
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
 * 객체 원천 매핑 (API 034~036, FN-MAP-02). 같은 원천 자원·객체 유형에는 활성 매핑이 하나만 있다. 매핑을 만들거나 바꿀 때는 원천 자원 행을 잠가 수집과
 * 겹치지 않게 하고, 테이블을 처음부터 다시 읽어야 하면 그 자원의 CDC 기준 LSN을 비운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ObjectMappingService {

    private final ObjectSourceMappingRepository mappingRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final SourceCatalogService catalogService;

    /** API 034. */
    public Page<ObjectMappingResponse> search(
            Long sourceResourceId, Long objectTypeId, Pageable pageable) {
        return mappingRepository
                .search(sourceResourceId, objectTypeId, pageable)
                .map(ObjectMappingResponse::from);
    }

    /** API 035. 활성 매핑이면 다음 수집이 그 테이블을 처음부터 읽는다. */
    @Transactional
    public ObjectMappingResponse create(ObjectMappingCreateRequest request) {
        requireEmptyRowFilter(request.rowFilter());
        requireDistinct(request.keyColumns());
        SourceResource resource = lockResource(request.sourceResourceId());
        validateReferences(
                request.sourceResourceId(), request.objectTypeId(), request.keyColumns());
        boolean active = !Boolean.FALSE.equals(request.isActive());
        if (active
                && mappingRepository.existsActive(
                        request.sourceResourceId(), request.objectTypeId(), null)) {
            throw duplicateActive();
        }
        ObjectSourceMapping mapping =
                mappingRepository.saveAndFlush(
                        ObjectSourceMapping.createManual(
                                request.sourceResourceId(),
                                request.objectTypeId(),
                                request.keyColumns(),
                                active));
        if (active) {
            catalogService.requireFullReload(resource);
        }
        return ObjectMappingResponse.from(mapping);
    }

    /**
     * API 036. 객체가 만들어진 매핑은 원천 자원·객체 유형·키 컬럼을 바꿀 수 없다. 객체가 없는 매핑의 원천 식별을 바꾸거나 멈춘 매핑을 다시 켜면 그 테이블을
     * 처음부터 다시 읽는다(멈춘 동안의 변경을 반영하지 않았기 때문이다).
     */
    @Transactional
    public ObjectMappingUpdateResponse update(Long id, ObjectMappingUpdateRequest request) {
        requireEmptyRowFilter(request.rowFilter());
        requireDistinct(request.keyColumns());
        ObjectSourceMapping mapping =
                mappingRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "object_source_mapping",
                                                        "id",
                                                        id)));
        if (request.rowVersion() != mapping.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", mapping.getRowVersion()));
        }
        // 두 자원을 잠글 때 순서를 정해 두어 서로 기다리다 멈추지 않게 한다.
        SourceResource target = null;
        for (Long resourceId :
                new TreeSet<>(List.of(mapping.getSourceResourceId(), request.sourceResourceId()))) {
            SourceResource locked = lockResource(resourceId);
            if (resourceId.equals(request.sourceResourceId())) {
                target = locked;
            }
        }
        validateReferences(
                request.sourceResourceId(), request.objectTypeId(), request.keyColumns());

        boolean identityChanged =
                mapping.changesIdentity(
                        request.sourceResourceId(), request.objectTypeId(), request.keyColumns());
        if (identityChanged && mappingRepository.hasObjects(id)) {
            throw new BusinessException(
                    MappingErrorCode.MAPPING_IDENTITY_LOCKED, Map.of("mappingId", id));
        }
        boolean wasActive = mapping.isActive();
        boolean active = request.isActive() == null ? wasActive : request.isActive();
        if (active
                && mappingRepository.existsActive(
                        request.sourceResourceId(), request.objectTypeId(), id)) {
            throw duplicateActive();
        }

        ReloadReason reloadReason = null;
        if (active && identityChanged) {
            reloadReason = ReloadReason.IDENTITY_CHANGED;
        } else if (active && !wasActive) {
            reloadReason = ReloadReason.REACTIVATED;
        }
        mapping.changeIdentity(
                request.sourceResourceId(), request.objectTypeId(), request.keyColumns());
        mapping.changeActive(active);
        if (reloadReason != null) {
            catalogService.requireFullReload(target);
        }
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        mappingRepository.flush();
        return ObjectMappingUpdateResponse.of(ObjectMappingResponse.from(mapping), reloadReason);
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

    private void validateReferences(Long sourceResourceId, Long objectTypeId, List<String> keys) {
        if (!objectTypeRepository.existsById(objectTypeId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "객체 유형을 찾을 수 없습니다.",
                    Map.of("field", "objectTypeId"));
        }
        Set<String> present = catalogService.presentColumnNames(sourceResourceId);
        List<String> missing = keys.stream().filter(key -> !present.contains(key)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "키 컬럼이 이 원천 자원의 현재 컬럼 목록에 없습니다.",
                    Map.of("field", "keyColumns", "missingColumns", missing));
        }
    }

    /** 행 조건 형식이 정해지기 전에는 표 전체를 매핑한다(2026-11-03). 조건을 보내면 무시하지 않고 거절한다. */
    private static void requireEmptyRowFilter(Map<String, Object> rowFilter) {
        if (rowFilter != null && !rowFilter.isEmpty()) {
            throw BusinessException.invalidField(
                    "rowFilter", "행 조건은 아직 지원하지 않습니다. 빈 객체({})로 보내 주세요.");
        }
    }

    private static void requireDistinct(List<String> keyColumns) {
        if (new HashSet<>(keyColumns).size() != keyColumns.size()) {
            throw BusinessException.invalidField("keyColumns", "같은 컬럼을 두 번 넣을 수 없습니다.");
        }
    }

    private static BusinessException duplicateActive() {
        return new BusinessException(
                CommonErrorCode.CONFLICT,
                "같은 원천 자원과 객체 유형의 활성 매핑이 이미 있습니다.",
                Map.of("field", "objectTypeId"));
    }
}

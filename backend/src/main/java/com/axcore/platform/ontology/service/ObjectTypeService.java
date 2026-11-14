package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.dto.DefinitionReference;
import com.axcore.platform.ontology.dto.ObjectTypeCreateRequest;
import com.axcore.platform.ontology.dto.ObjectTypeResponse;
import com.axcore.platform.ontology.dto.ObjectTypeUpdateRequest;
import com.axcore.platform.ontology.repository.DefinitionReferenceRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.SourcePolicyViolation;
import com.axcore.platform.source.service.FullLoadPlanner;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ObjectTypeService {

    private final ObjectTypeRepository objectTypeRepository;
    private final PropertyDefinitionRepository propertyDefinitionRepository;
    private final DefinitionReferenceRepository referenceRepository;
    private final ObjectSourceMappingRepository mappingRepository;
    private final SourceCatalogService catalogService;
    private final FullLoadPlanner fullLoadPlanner;

    /** API 021. q가 비어 있으면 전체를 돌려준다. */
    public Page<ObjectTypeResponse> search(String q, Pageable pageable) {
        return objectTypeRepository
                .search(SearchPattern.contains(q), pageable)
                .map(ObjectTypeResponse::from);
    }

    /** API 023. */
    public ObjectTypeResponse getObjectType(Long id) {
        return ObjectTypeResponse.from(findObjectType(id));
    }

    /** API 022. 코드는 전체에서 유일하다. 동시에 같은 코드로 등록하면 DB 제약이 하나만 남긴다. */
    @Transactional
    public ObjectTypeResponse create(ObjectTypeCreateRequest request) {
        return create(request, DefinitionSource.MANUAL);
    }

    /** 승인한 후보 반영(189)도 이 경로로 만든다. 출처만 다르다. */
    @Transactional
    public ObjectTypeResponse create(ObjectTypeCreateRequest request, DefinitionSource source) {
        if (objectTypeRepository.existsByCode(request.code())) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        ObjectType objectType =
                ObjectType.createManual(
                        request.code(),
                        request.name(),
                        request.description(),
                        request.synonyms() == null ? List.of() : request.synonyms(),
                        request.accessGroup());
        objectType.recordSource(source);
        return ObjectTypeResponse.from(objectTypeRepository.saveAndFlush(objectType));
    }

    /**
     * API 024. 대표 속성은 이 유형의 속성이어야 한다. 민감(RESTRICTED) 속성은 대표로 쓸 수 없다. 대표 속성 값은 객체 표시 이름이 되어 민감 값을 볼
     * 수 없는 사용자에게도 보이기 때문이다(S15P31C106-205 결정 D2).
     *
     * <p>표시 이름은 수집할 때 저장된다. 대표 속성이 바뀌면 이 유형이 켜진 매핑으로 연결된 원천 테이블을 다시 읽기로 표시해 기존 객체의 표시 이름도 새 대표 속성으로
     * 다시 만든다(S15P31C106-371). 읽고 있던 전체 읽기는 저장할 때 계획(유형 버전·대표 속성)이 달라진 것을 보고 저장하지 않는다.
     */
    @Transactional
    public ObjectTypeResponse update(Long id, ObjectTypeUpdateRequest request) {
        ObjectType objectType = findObjectType(id);
        if (request.getRowVersion() != objectType.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.getRowVersion(),
                            "actual", objectType.getRowVersion()));
        }
        Long displayPropertyId =
                request.hasDisplayPropertyId()
                        ? request.getDisplayPropertyId()
                        : objectType.getDisplayPropertyId();
        boolean displayChanged =
                !Objects.equals(displayPropertyId, objectType.getDisplayPropertyId());
        // 매핑 변경과 같이 원천 테이블 행을 속성 행보다 먼저 잠근다. 속성 매핑을 만드는 쪽은 테이블 행을 잠근 뒤 속성 행을 FK로 참조한다.
        List<SourceResource> reloadTargets = displayChanged ? lockMappedResources(id) : List.of();
        if (displayChanged && displayPropertyId != null) {
            // 028(속성 수정)과 같은 속성 행 잠금을 잡는다. 대표 지정과 민감 전환이 동시에 와도 뒤의 요청이 최신 값을 보고 거절된다.
            PropertyDefinition property =
                    propertyDefinitionRepository
                            .findForUpdate(displayPropertyId)
                            .filter(found -> found.getObjectTypeId().equals(id))
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                                    "대표 속성은 같은 객체 유형의 속성이어야 합니다.",
                                                    Map.of("field", "displayPropertyId")));
            if (property.getSensitivity() == PropertySensitivity.RESTRICTED) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "민감 속성은 대표 속성으로 지정할 수 없습니다.",
                        Map.of("field", "displayPropertyId"));
            }
        }
        String accessGroup =
                request.hasAccessGroup() ? request.getAccessGroup() : objectType.getAccessGroup();
        if (accessGroup != null && !accessGroup.equals(objectType.getAccessGroup())) {
            requirePublishable(id);
        }
        objectType.update(
                request.getName() == null ? objectType.getName() : request.getName(),
                request.hasDescription() ? request.getDescription() : objectType.getDescription(),
                request.getSynonyms() == null ? objectType.getSynonyms() : request.getSynonyms(),
                accessGroup,
                displayPropertyId);
        reloadTargets.forEach(catalogService::requireFullReload);
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        objectTypeRepository.flush();
        return ObjectTypeResponse.from(objectType);
    }

    /**
     * 공개 전 검사(402). 그룹을 새로 지정하거나 바꾸면 그 그룹 역할에게 객체가 보인다. 이 유형의 켜진 매핑이 원천 데이터 정책을 어기면(금지 컬럼, 민감 키, 민감
     * 컬럼을 담는 일반 속성) 거절한다. 적재 계획과 같은 판단이다. 정리보다 공개가 먼저 되어 금지 값이 나간 일(398)을 서버가 막는다. 그룹을 비우는 것은 막지
     * 않는다.
     */
    private void requirePublishable(Long objectTypeId) {
        List<SourcePolicyViolation> violations =
                mappingRepository.findActiveSourceResourceIds(objectTypeId).stream()
                        .flatMap(resourceId -> fullLoadPlanner.violations(resourceId).stream())
                        .filter(violation -> violation.objectTypeId() == objectTypeId)
                        .toList();
        if (violations.isEmpty()) {
            return;
        }
        throw new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                "원천 데이터 정책을 어기는 매핑이 있어 그룹을 지정할 수 없습니다. 매핑을 고친 뒤 지정하세요.",
                Map.of(
                        "field",
                        "accessGroup",
                        "violations",
                        violations.stream().map(ObjectTypeService::summary).toList()));
    }

    private static Map<String, Object> summary(SourcePolicyViolation violation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", violation.kind().name());
        summary.put("resourceKey", violation.resourceKey());
        summary.put("columnName", violation.columnName());
        summary.put("objectMappingId", violation.objectMappingId());
        summary.put("propertyMappingId", violation.propertyMappingId());
        summary.put("propertyCode", violation.propertyCode());
        return summary;
    }

    /** 이 유형이 켜진 매핑으로 연결된 원천 테이블 행을 번호 순으로 잠근다. 번호 순이라 다른 잠금과 서로 기다리다 멈추지 않는다. */
    private List<SourceResource> lockMappedResources(Long objectTypeId) {
        return mappingRepository.findActiveSourceResourceIds(objectTypeId).stream()
                .map(catalogService::lockResource)
                .flatMap(Optional::stream)
                .toList();
    }

    /** API 025. 참조가 하나라도 있으면 지우지 않고 참조 목록을 돌려준다. */
    @Transactional
    public void delete(Long id) {
        ObjectType objectType =
                objectTypeRepository.findForUpdate(id).orElseThrow(() -> notFound(id));
        List<DefinitionReference> references = referenceRepository.findObjectTypeReferences(id);
        if (!references.isEmpty()) {
            throw new BusinessException(CommonErrorCode.IN_USE, Map.of("references", references));
        }
        objectTypeRepository.delete(objectType);
    }

    private ObjectType findObjectType(Long id) {
        return objectTypeRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "object_type", "id", id));
    }
}

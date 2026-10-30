package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.dto.DefinitionReference;
import com.axcore.platform.ontology.dto.ObjectTypeCreateRequest;
import com.axcore.platform.ontology.dto.ObjectTypeResponse;
import com.axcore.platform.ontology.dto.ObjectTypeUpdateRequest;
import com.axcore.platform.ontology.repository.DefinitionReferenceRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import java.util.List;
import java.util.Map;
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
        return ObjectTypeResponse.from(objectTypeRepository.saveAndFlush(objectType));
    }

    /** API 024. 대표 속성은 이 유형의 속성이어야 한다. */
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
        if (displayPropertyId != null
                && !displayPropertyId.equals(objectType.getDisplayPropertyId())
                && !propertyDefinitionRepository.existsByIdAndObjectTypeId(displayPropertyId, id)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "대표 속성은 같은 객체 유형의 속성이어야 합니다.",
                    Map.of("field", "displayPropertyId"));
        }
        objectType.update(
                request.getName() == null ? objectType.getName() : request.getName(),
                request.hasDescription() ? request.getDescription() : objectType.getDescription(),
                request.getSynonyms() == null ? objectType.getSynonyms() : request.getSynonyms(),
                request.hasAccessGroup() ? request.getAccessGroup() : objectType.getAccessGroup(),
                displayPropertyId);
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        objectTypeRepository.flush();
        return ObjectTypeResponse.from(objectType);
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

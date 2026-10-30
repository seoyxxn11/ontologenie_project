package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.dto.DefinitionReference;
import com.axcore.platform.ontology.dto.LinkTypeCreateRequest;
import com.axcore.platform.ontology.dto.LinkTypeResponse;
import com.axcore.platform.ontology.dto.LinkTypeSearchRequest;
import com.axcore.platform.ontology.dto.LinkTypeUpdateRequest;
import com.axcore.platform.ontology.repository.DefinitionReferenceRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LinkTypeService {

    private final LinkTypeRepository linkTypeRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final DefinitionReferenceRepository referenceRepository;

    /** API 030. 양 끝 유형 조건은 주어진 것만 쓴다. */
    public Page<LinkTypeResponse> search(LinkTypeSearchRequest request, Pageable pageable) {
        return linkTypeRepository
                .search(
                        SearchPattern.contains(request.q()),
                        request.sourceTypeId(),
                        request.targetTypeId(),
                        pageable)
                .map(LinkTypeResponse::from);
    }

    /** API 031. 코드는 전체에서 유일하다. 동시에 같은 코드로 등록하면 DB 제약이 하나만 남긴다. */
    @Transactional
    public LinkTypeResponse create(LinkTypeCreateRequest request) {
        requireObjectTypes(request.sourceTypeId(), request.targetTypeId());
        if (linkTypeRepository.existsByCode(request.code())) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        LinkType linkType =
                LinkType.manual()
                        .code(request.code())
                        .name(request.name())
                        .sourceTypeId(request.sourceTypeId())
                        .targetTypeId(request.targetTypeId())
                        .cardinality(LinkCardinality.valueOf(request.cardinality()))
                        .description(request.description())
                        .build();
        return LinkTypeResponse.from(linkTypeRepository.saveAndFlush(linkType));
    }

    /**
     * API 032. 이름·설명·관계 수는 언제든 바꿀 수 있다. 코드와 양 끝 유형은 이 관계 유형을 쓰는 규칙·연결·보류 건이 없을 때만 바뀐다. 행을 잠가 확인과 변경
     * 사이에 새 규칙·연결이 생기지 않게 한다.
     */
    @Transactional
    public LinkTypeResponse update(Long id, LinkTypeUpdateRequest request) {
        LinkType linkType = linkTypeRepository.findForUpdate(id).orElseThrow(() -> notFound(id));
        if (request.rowVersion() != linkType.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", linkType.getRowVersion()));
        }
        requireObjectTypes(request.sourceTypeId(), request.targetTypeId());
        String changedKeyField = changedKeyField(linkType, request);
        if (changedKeyField != null) {
            List<DefinitionReference> references = referenceRepository.findLinkTypeReferences(id);
            if (!references.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("field", changedKeyField);
                details.put("references", references);
                throw new BusinessException(
                        CommonErrorCode.IN_USE,
                        // API 명세(032)가 정한 문구. 코드를 바꾸려 한 경우도 같은 문구를 쓴다.
                        "이 관계 유형을 쓰는 규칙·연결·보류 건이 있어 출발·도착 유형을 바꿀 수 없습니다.",
                        details);
            }
        }
        if (!request.code().equals(linkType.getCode())
                && linkTypeRepository.existsByCode(request.code())) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        linkType.update(
                request.code(),
                request.name(),
                request.sourceTypeId(),
                request.targetTypeId(),
                LinkCardinality.valueOf(request.cardinality()),
                request.description());
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        linkTypeRepository.flush();
        return LinkTypeResponse.from(linkType);
    }

    /** API 033. 참조가 하나라도 있으면 지우지 않고 참조 목록을 돌려준다. */
    @Transactional
    public void delete(Long id) {
        LinkType linkType = linkTypeRepository.findForUpdate(id).orElseThrow(() -> notFound(id));
        List<DefinitionReference> references = referenceRepository.findLinkTypeReferences(id);
        if (!references.isEmpty()) {
            throw new BusinessException(CommonErrorCode.IN_USE, Map.of("references", references));
        }
        linkTypeRepository.delete(linkType);
    }

    /** 바뀌는 고정 항목 중 첫 번째. 없으면 null. */
    private static String changedKeyField(LinkType linkType, LinkTypeUpdateRequest request) {
        if (!Objects.equals(request.sourceTypeId(), linkType.getSourceTypeId())) {
            return "sourceTypeId";
        }
        if (!Objects.equals(request.targetTypeId(), linkType.getTargetTypeId())) {
            return "targetTypeId";
        }
        if (!request.code().equals(linkType.getCode())) {
            return "code";
        }
        return null;
    }

    /** 양 끝은 있는 객체 유형이어야 한다. 명세가 이 경우를 입력 오류로 정해 400으로 알린다. */
    private void requireObjectTypes(Long sourceTypeId, Long targetTypeId) {
        if (!objectTypeRepository.existsById(sourceTypeId)) {
            throw BusinessException.invalidField("sourceTypeId", "없는 객체 유형입니다.");
        }
        if (!objectTypeRepository.existsById(targetTypeId)) {
            throw BusinessException.invalidField("targetTypeId", "없는 객체 유형입니다.");
        }
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "link_type", "id", id));
    }
}

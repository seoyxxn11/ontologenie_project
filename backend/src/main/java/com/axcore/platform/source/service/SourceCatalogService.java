package com.axcore.platform.source.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.service.SearchPattern;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.dto.SourceColumnResponse;
import com.axcore.platform.source.dto.SourceResourceResponse;
import com.axcore.platform.source.dto.SourceResourceSearchRequest;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceQueryRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원천 자원·컬럼 목록 (API 014·015, FN-SRC-02·FN-MAP-01). 연결 검사(배치)가 채운 목록을 읽기만 한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceCatalogService {

    private final DataSourceService dataSourceService;
    private final SourceResourceRepository resourceRepository;
    private final SourceColumnRepository columnRepository;
    private final SourceQueryRepository queryRepository;

    /** API 014. */
    public Page<SourceResourceResponse> resources(
            Long dataSourceId, SourceResourceSearchRequest request, Pageable pageable) {
        dataSourceService.findDataSource(dataSourceId);
        Page<SourceResource> page =
                resourceRepository.search(
                        dataSourceId,
                        request.resourceKind() == null ? null : request.resourceKind().name(),
                        request.isReadable(),
                        SearchPattern.contains(request.q()),
                        pageable);
        var lastSyncs =
                queryRepository.findLastSyncs(
                        page.getContent().stream().map(SourceResource::getId).toList());
        return page.map(
                resource -> SourceResourceResponse.from(resource, lastSyncs.get(resource.getId())));
    }

    /**
     * 매핑을 만들거나 바꾸기 전에 원천 자원 행을 잠근다(S15P31C106-182). 수집도 같은 행을 잠그므로 한 번의 수집에 매핑 버전이 섞이지 않는다. 자원이 없으면
     * 빈 값이다.
     */
    @Transactional
    public Optional<SourceResource> lockResource(Long resourceId) {
        return resourceRepository.findForUpdate(resourceId);
    }

    /** 자원의 현재 컬럼 이름. 원천에서 사라진 컬럼(isPresent=false)은 뺀다. */
    public Set<String> presentColumnNames(Long resourceId) {
        return columnRepository
                .findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(resourceId)
                .stream()
                .filter(SourceColumn::isPresent)
                .map(SourceColumn::getColumnName)
                .collect(Collectors.toSet());
    }

    /** 매핑이 바뀐 테이블을 처음부터 다시 읽게 한다. 먼저 {@link #lockResource}로 잠근 자원이어야 한다. */
    @Transactional
    public void requireFullReload(SourceResource resource) {
        resource.requireFullReload();
    }

    /** API 015. 자원이 경로의 원천에 속하지 않으면 없는 것으로 본다. */
    public List<SourceColumnResponse> columns(Long dataSourceId, Long resourceId) {
        resourceRepository
                .findByIdAndDataSourceId(resourceId, dataSourceId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "source_resource", "id", resourceId)));
        return columnRepository
                .findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(resourceId)
                .stream()
                .map(SourceColumnResponse::from)
                .toList();
    }
}

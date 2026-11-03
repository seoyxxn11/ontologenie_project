package com.axcore.platform.source.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.service.SearchPattern;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.dto.SourceColumnResponse;
import com.axcore.platform.source.dto.SourceResourceResponse;
import com.axcore.platform.source.dto.SourceResourceSearchRequest;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceQueryRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.List;
import java.util.Map;
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

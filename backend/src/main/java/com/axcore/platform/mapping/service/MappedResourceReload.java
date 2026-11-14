package com.axcore.platform.mapping.service;

import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 속성을 민감(RESTRICTED)으로 올릴 때 그 속성이 매핑된 원천 표를 처음부터 다시 읽게 한다(391).
 *
 * <p>391부터 적재 계획은 민감 컬럼을 담는 일반 속성을 실패로 막는다(391 이전 매핑을 거르기 위해서다). CDC 반영은 계획이 실패한 표의 이벤트를 건너뛰고 위치를
 * 넘기므로, 그동안의 변경·삭제는 빠진 채 남는다. 관리자가 안내대로 속성을 민감으로 올리면 계획은 다시 통과하지만 빠진 변경은 돌아오지 않는다. 그래서 올리는 순간 그 속성을
 * 담는 켜진 매핑의 표를 다시 읽게 해, 전체 읽기가 저장된 객체와 비교해 맞춘다.
 *
 * <p>잠금 순서는 매핑 변경·수집과 같이 원천 자원 행을 ID 순서로 먼저 잡는다. 부르는 쪽 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
public class MappedResourceReload {

    private final PropertySourceMappingRepository propertyMappingRepository;
    private final SourceCatalogService catalogService;

    /**
     * 속성을 담는 켜진 매핑의 원천 자원을 ID 순서로 잠근다. {@code alreadyLocked}에 있는 자원은 부르는 쪽이 이미 잠갔으므로 뺀다.
     *
     * @return 잠근 자원(다시 읽기는 {@link #requireFullReload}로 따로 표시한다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SourceResource> lockMappedResources(long propertyId, Set<Long> alreadyLocked) {
        return activeResourceIds(propertyId).stream()
                .filter(id -> !alreadyLocked.contains(id))
                .map(catalogService::lockResource)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 속성을 담는 켜진 매핑의 원천 자원을 다시 읽기로 표시한다. {@code skip}은 부르는 쪽이 따로 처리한 자원이다. 잠그지 않은 자원이 남았으면(먼저 읽은 뒤
     * 매핑이 바뀐 드문 경우) 그 자리에서 잠근다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reloadMapped(long propertyId, Map<Long, SourceResource> locked, Set<Long> skip) {
        for (Long id : activeResourceIds(propertyId)) {
            if (skip.contains(id)) {
                continue;
            }
            SourceResource resource =
                    locked.containsKey(id)
                            ? locked.get(id)
                            : catalogService.lockResource(id).orElse(null);
            if (resource != null) {
                catalogService.requireFullReload(resource);
            }
        }
    }

    /** 속성을 담는 켜진 매핑의 원천 자원 ID(오름차순). 잠그지 않고, 매핑 엔티티도 불러오지 않는다(429). */
    public Set<Long> activeResourceIds(long propertyId) {
        return new TreeSet<>(propertyMappingRepository.findActiveSourceResourceIds(propertyId));
    }

    /** 잠근 자원들을 다시 읽기 대상으로 표시한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFullReload(Collection<SourceResource> resources) {
        resources.forEach(catalogService::requireFullReload);
    }
}

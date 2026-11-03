package com.axcore.platform.check.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 검사 입력의 기준이 되는 원천 자원별 마지막 성공 수집 실행 ID. */
public record CheckDataBasis(List<ResourceCheckpoint> resources) {

    public CheckDataBasis {
        Objects.requireNonNull(resources, "resources");

        // 호출한 쪽에서 원본 목록을 바꿔도 검사 기준은 유지한다.
        resources = List.copyOf(resources);

        Set<Long> resourceIds = new HashSet<>();
        for (ResourceCheckpoint resource : resources) {
            if (!resourceIds.add(resource.sourceResourceId())) {
                throw new IllegalArgumentException("같은 원천 자원의 체크포인트를 중복 지정할 수 없습니다.");
            }
        }

        // 같은 입력 기준은 자원을 전달한 순서와 관계없이 같도록 정렬한다.
        resources =
                resources.stream()
                        .sorted(Comparator.comparingLong(ResourceCheckpoint::sourceResourceId))
                        .toList();
    }

    /** 원천 자원 하나의 마지막 성공 수집 실행. 성공 이력이 없으면 null로 보존한다. */
    public record ResourceCheckpoint(long sourceResourceId, Long lastSuccessfulSyncRunId) {

        public ResourceCheckpoint {
            if (sourceResourceId < 1) {
                throw new IllegalArgumentException("원천 자원 ID는 1 이상이어야 합니다.");
            }

            if (lastSuccessfulSyncRunId != null && lastSuccessfulSyncRunId < 1) {
                throw new IllegalArgumentException("성공 수집 실행 ID는 1 이상이어야 합니다.");
            }
        }
    }
}

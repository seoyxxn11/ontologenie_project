package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.ResourceKind;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.SourceQueryRepository.LastSync;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** API 014 한 건. lastSyncRunId·lastSyncFinishedAt은 저장 칸이 아니라 마지막 성공 수집에서 계산한다. 성공한 적이 없으면 null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceResourceResponse(
        Long id,
        Long dataSourceId,
        String resourceKey,
        ResourceKind resourceKind,
        boolean isReadable,
        Map<String, Object> sourceMetadata,
        SyncStrategy syncStrategy,
        Long loadedLsn,
        Instant lastSeenAt,
        Long lastSyncRunId,
        Instant lastSyncFinishedAt) {

    public static SourceResourceResponse from(SourceResource resource, LastSync lastSync) {
        return new SourceResourceResponse(
                resource.getId(),
                resource.getDataSourceId(),
                resource.getResourceKey(),
                resource.getResourceKind(),
                resource.isReadable(),
                new LinkedHashMap<>(resource.getSourceMetadata()),
                resource.getSyncStrategy(),
                resource.getLoadedLsn(),
                resource.getLastSeenAt(),
                lastSync == null ? null : lastSync.syncRunId(),
                lastSync == null ? null : lastSync.finishedAt());
    }
}

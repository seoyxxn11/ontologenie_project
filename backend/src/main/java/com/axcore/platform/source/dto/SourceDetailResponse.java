package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.dto.SourceResponse.CredentialResponse;
import com.axcore.platform.source.repository.SourceQueryRepository.ConnectionCheck;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API 011. Source에 가장 최근 연결 검사(없으면 null)를 더한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceDetailResponse(
        Long id,
        String code,
        String name,
        SourceKind sourceKind,
        ConnectionStatus connectionStatus,
        Instant lastCheckedAt,
        boolean syncEnabled,
        Integer syncIntervalMinutes,
        long rowVersion,
        Map<String, Object> connectionConfig,
        List<CredentialResponse> credentials,
        LatestConnectionCheck latestConnectionCheck) {

    public static SourceDetailResponse of(SourceResponse source, ConnectionCheck check) {
        return new SourceDetailResponse(
                source.id(),
                source.code(),
                source.name(),
                source.sourceKind(),
                source.connectionStatus(),
                source.lastCheckedAt(),
                source.syncEnabled(),
                source.syncIntervalMinutes(),
                source.rowVersion(),
                source.connectionConfig(),
                source.credentials(),
                check == null
                        ? null
                        : new LatestConnectionCheck(
                                check.id(),
                                check.status(),
                                check.finishedAt(),
                                check.errorMessage()));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LatestConnectionCheck(
            long checkId, String status, Instant finishedAt, String errorMessage) {}
}

package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API 명세의 Source (009·010·012). 접속 계정은 용도·계정명만 담고 비밀번호·암호문은 담지 않는다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceResponse(
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
        List<CredentialResponse> credentials) {

    public static SourceResponse from(DataSource source, List<CredentialResponse> credentials) {
        return new SourceResponse(
                source.getId(),
                source.getCode(),
                source.getName(),
                source.getSourceKind(),
                source.getConnectionStatus(),
                source.getLastCheckedAt(),
                source.isSyncEnabled(),
                source.getSyncIntervalMinutes(),
                source.getRowVersion(),
                new LinkedHashMap<>(source.getConnectionConfig()),
                List.copyOf(credentials));
    }

    public record CredentialResponse(String purpose, String username) {}
}

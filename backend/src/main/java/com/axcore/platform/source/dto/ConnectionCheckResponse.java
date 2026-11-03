package com.axcore.platform.source.dto;

import com.axcore.platform.source.repository.SourceQueryRepository.ConnectionCheck;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** API 013 접수 결과. joined가 true면 같은 원천의 대기·실행 중 검사에 합류한 것이다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConnectionCheckResponse(
        long checkId,
        String status,
        boolean joined,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {

    public static ConnectionCheckResponse of(ConnectionCheck check, boolean joined) {
        return new ConnectionCheckResponse(
                check.id(),
                check.status(),
                joined,
                check.requestedAt(),
                check.startedAt(),
                check.finishedAt(),
                check.errorMessage());
    }
}

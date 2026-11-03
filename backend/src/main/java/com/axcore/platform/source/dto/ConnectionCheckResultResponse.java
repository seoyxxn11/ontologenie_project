package com.axcore.platform.source.dto;

import com.axcore.platform.source.repository.SourceQueryRepository.ConnectionCheck;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API 100. 배치가 result에 쓴 용도별 계정 결과를 정해진 칸만 골라 돌려준다(그 밖의 칸은 내보내지 않는다). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConnectionCheckResultResponse(
        long checkId,
        long dataSourceId,
        String status,
        long requestedBy,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        Result result) {

    public static ConnectionCheckResultResponse from(ConnectionCheck check) {
        List<AccountResult> accounts =
                check.result().get("accounts") instanceof List<?> list
                        ? list.stream()
                                .filter(Map.class::isInstance)
                                .map(item -> AccountResult.from((Map<?, ?>) item))
                                .toList()
                        : List.of();
        return new ConnectionCheckResultResponse(
                check.id(),
                check.dataSourceId(),
                check.status(),
                check.requestedBy(),
                check.requestedAt(),
                check.startedAt(),
                check.finishedAt(),
                check.errorMessage(),
                new Result(accounts));
    }

    public record Result(List<AccountResult> accounts) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountResult(
            String purpose,
            String status,
            Long readableTableCount,
            Long readableColumnCount,
            String errorMessage) {

        static AccountResult from(Map<?, ?> account) {
            return new AccountResult(
                    text(account.get("purpose")),
                    text(account.get("status")),
                    number(account.get("readableTableCount")),
                    number(account.get("readableColumnCount")),
                    text(account.get("errorMessage")));
        }

        private static String text(Object value) {
            return value instanceof String text ? text : null;
        }

        private static Long number(Object value) {
            return value instanceof Number number ? number.longValue() : null;
        }
    }
}

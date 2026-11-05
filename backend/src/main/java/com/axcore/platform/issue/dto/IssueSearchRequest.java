package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** API 065 조회 조건. from·to는 문제 등록 시각(createdAt) 기준이고 둘 다 포함한다. */
public record IssueSearchRequest(
        @Pattern(
                        regexp = "NEW|CHECKING|FIXED_PENDING_REVERIFY|RESOLVED",
                        message = "허용되지 않는 status 값입니다.")
                String status,
        @Pattern(regexp = "UNMATCHED|DUPLICATE|VIOLATION", message = "허용되지 않는 issueKind 값입니다.")
                String issueKind,
        @Min(value = 1, message = "linkMappingId는 1 이상이어야 합니다.") Long linkMappingId,
        Instant from,
        Instant to,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {

    @AssertTrue(message = "from은 to보다 늦을 수 없습니다.")
    public boolean isPeriodOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }
}

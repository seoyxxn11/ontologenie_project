package com.axcore.platform.audit.dto;

import com.axcore.platform.audit.domain.ExecutionStatus;
import jakarta.validation.constraints.Min;
import java.time.Instant;

/** API 080 조회 조건. 기간은 requestedAt 기준이며 from은 포함, to는 포함하지 않는다. */
public record ObjectExecutionSearchRequest(
        Instant from,
        Instant to,
        ExecutionStatus status,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

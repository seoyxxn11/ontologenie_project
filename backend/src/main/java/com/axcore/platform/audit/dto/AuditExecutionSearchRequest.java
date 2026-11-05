package com.axcore.platform.audit.dto;

import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** API 096 조회 조건. 비어 있는 조건으로는 거르지 않는다. 기간은 requestedAt 기준이며 from은 포함, to는 포함하지 않는다. */
public record AuditExecutionSearchRequest(
        @Min(value = 1, message = "actorId는 1 이상이어야 합니다.") Long actorId,
        @Size(max = 100, message = "actionCode는 100자 이하여야 합니다.") String actionCode,
        ExecutionStatus status,
        EntryPoint entryPoint,
        Instant from,
        Instant to,
        EventKind eventKind,
        ExecutionCategory category,
        @Size(max = 50, message = "targetType은 50자 이하여야 합니다.") String targetType,
        @Min(value = 1, message = "targetId는 1 이상이어야 합니다.") Long targetId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API 067 요청. resolvedCheckRunId는 status=RESOLVED일 때만 보낸다(서비스에서 검사). */
public record IssueStatusChangeRequest(
        @NotNull
                @Pattern(
                        regexp = "CHECKING|FIXED_PENDING_REVERIFY|RESOLVED",
                        message = "허용되지 않는 status 값입니다.")
                String status,
        @NotNull
                @Size(max = 1000, message = "reason은 1000자 이하여야 합니다.")
                @Pattern(regexp = "(?s).*\\S.*", message = "reason은 비어 있을 수 없습니다.")
                String reason,
        @Min(value = 1, message = "resolvedCheckRunId는 1 이상이어야 합니다.") Long resolvedCheckRunId,
        @NotNull @Min(value = 0, message = "rowVersion은 0 이상이어야 합니다.") Long rowVersion) {}

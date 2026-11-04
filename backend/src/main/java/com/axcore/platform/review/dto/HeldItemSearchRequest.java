package com.axcore.platform.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record HeldItemSearchRequest(
        @Pattern(regexp = "HELD|APPROVED|AUTO_RESOLVED|DISMISSED", message = "허용되지 않는 status 값입니다.")
                String status,
        @Min(value = 1, message = "linkTypeId는 1 이상이어야 합니다.") Long linkTypeId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

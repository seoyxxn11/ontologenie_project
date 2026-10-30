package com.axcore.platform.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ProposalSearchRequest(
        @Pattern(regexp = "AI|MANUAL", message = "허용되지 않는 origin 값입니다.") String origin,
        @Pattern(
                        regexp =
                                "OBJECT_TYPE|PROPERTY|LINK_TYPE|OBJECT_MAPPING"
                                        + "|PROPERTY_MAPPING|LINK_RULE|DOCUMENT_LINK",
                        message = "허용되지 않는 itemKind 값입니다.")
                String itemKind,
        @Pattern(regexp = "PENDING|APPROVED|REJECTED|HELD", message = "허용되지 않는 reviewStatus 값입니다.")
                String reviewStatus,
        @Pattern(regexp = "NOT_APPLIED|APPLIED|FAILED", message = "허용되지 않는 applyStatus 값입니다.")
                String applyStatus,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

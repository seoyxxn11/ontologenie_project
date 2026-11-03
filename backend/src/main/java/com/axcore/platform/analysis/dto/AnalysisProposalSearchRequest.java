package com.axcore.platform.analysis.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record AnalysisProposalSearchRequest(
        @Pattern(
                        regexp =
                                "OBJECT_TYPE|PROPERTY|LINK_TYPE|OBJECT_MAPPING|PROPERTY_MAPPING|LINK_RULE|DOCUMENT_LINK")
                String itemKind,
        @Pattern(regexp = "PENDING|APPROVED|REJECTED|HELD") String reviewStatus,
        @Pattern(regexp = "NOT_APPLIED|APPLIED|FAILED") String applyStatus,
        @Min(0) Integer page,
        @Min(1) Integer size) {}

package com.axcore.platform.review.dto;

import jakarta.validation.constraints.Min;

public record ProposalDecisionSearchRequest(
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

package com.axcore.platform.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProposalDecisionRequest(
        @NotNull @Min(1) Integer revision,
        @NotNull @Pattern(regexp = "APPROVE|REJECT|HOLD") String decision,
        @Min(1) Long selectedOptionId,
        @NotBlank @Size(max = 1000) String reason) {}

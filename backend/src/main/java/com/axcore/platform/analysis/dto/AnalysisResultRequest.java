package com.axcore.platform.analysis.dto;

import com.axcore.platform.review.dto.AiProposalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record AnalysisResultRequest(
        @NotNull @Pattern(regexp = "RUNNING|SUCCEEDED|FAILED") String status,
        String errorMessage,
        Map<String, Object> resultSummary,
        @Valid List<@NotNull AiProposalRequest> proposals) {}

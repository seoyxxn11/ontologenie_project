package com.axcore.platform.analysis.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record AnalysisSearchRequest(
        @Pattern(regexp = "INITIAL|REANALYSIS") String runKind,
        @Pattern(regexp = "QUEUED|RUNNING|SUCCEEDED|FAILED") String status,
        @Min(0) Integer page,
        @Min(1) Integer size) {}

package com.axcore.platform.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record LatestCheckResponse(
        long checkRunId,
        String status,
        Integer matchedCount,
        Integer totalCount,
        BigDecimal matchRate) {}

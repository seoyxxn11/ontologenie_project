package com.axcore.platform.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalResponse(
        long id,
        Long analysisRunId,
        String origin,
        String itemKind,
        int revision,
        Map<String, Object> payload,
        Map<String, Object> evidence,
        BigDecimal aiConfidence,
        String reviewStatus,
        String applyStatus,
        String applyError,
        Instant appliedAt,
        LatestCheckResponse latestCheck) {

    public ProposalResponse {
        if (evidence == null) {
            evidence = Map.of();
        }
    }
}

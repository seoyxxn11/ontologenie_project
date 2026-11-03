package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.ProposalItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalWriteResponse(
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
        Long createdBy,
        Instant createdAt,
        long checkRunId) {
    public static ProposalWriteResponse from(ProposalItem item, long checkRunId) {
        return new ProposalWriteResponse(
                item.getId(),
                item.getAnalysisRunId(),
                item.getOrigin(),
                item.getItemKind(),
                item.getRevision(),
                item.getPayload(),
                item.getEvidence(),
                item.getAiConfidence(),
                item.getReviewStatus(),
                item.getApplyStatus(),
                item.getApplyError(),
                item.getAppliedAt(),
                item.getCreatedBy(),
                item.getCreatedAt(),
                checkRunId);
    }
}

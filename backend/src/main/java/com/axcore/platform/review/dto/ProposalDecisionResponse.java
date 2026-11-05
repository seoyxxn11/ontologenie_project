package com.axcore.platform.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalDecisionResponse(
        long id,
        long proposalItemId,
        int itemRevision,
        String decision,
        Long selectedOptionId,
        String reason,
        long decidedBy,
        Instant decidedAt) {}

package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.ReviewDecision;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalDecisionResponse(
        long id,
        long proposalItemId,
        int itemRevision,
        String decision,
        Long selectedOptionId,
        Integer selectedOptionNo,
        String reason,
        long decidedBy,
        String decidedByName,
        Instant decidedAt) {

    public static ProposalDecisionResponse from(
            ReviewDecision decision, String decidedByName, Integer selectedOptionNo) {
        return new ProposalDecisionResponse(
                decision.getId(),
                decision.getProposalItemId(),
                decision.getItemRevision(),
                decision.getDecision(),
                decision.getSelectedOptionId(),
                selectedOptionNo,
                decision.getReason(),
                decision.getDecidedBy(),
                decidedByName,
                decision.getDecidedAt());
    }
}

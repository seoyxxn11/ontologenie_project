package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.ProposalOption;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalOptionResponse(
        long id,
        int optionNo,
        int revision,
        Map<String, Object> payload,
        Map<String, Object> evidence,
        BigDecimal aiConfidence) {

    public static ProposalOptionResponse from(ProposalOption option) {
        return new ProposalOptionResponse(
                option.getId(),
                option.getOptionNo(),
                option.getRevision(),
                option.getPayload(),
                option.getEvidence(),
                option.getAiConfidence());
    }
}

package com.axcore.platform.review.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ProposalOptionCreateRequest(
        @NotNull Map<String, Object> payload, Map<String, Object> evidence) {
    public ProposalOptionCreateRequest {
        evidence = evidence == null ? Map.of() : evidence;
    }
}

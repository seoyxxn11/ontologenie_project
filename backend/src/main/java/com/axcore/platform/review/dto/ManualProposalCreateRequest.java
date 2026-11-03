package com.axcore.platform.review.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ManualProposalCreateRequest(
        @NotNull String itemKind,
        @NotNull Map<String, Object> payload,
        Map<String, Object> evidence) {
    public ManualProposalCreateRequest {
        evidence = evidence == null ? Map.of() : evidence;
    }
}

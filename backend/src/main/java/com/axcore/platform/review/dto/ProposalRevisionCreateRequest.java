package com.axcore.platform.review.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record ProposalRevisionCreateRequest(
        @NotNull @Min(1) Integer baseRevision,
        @NotNull Map<String, Object> payload,
        Map<String, Object> evidence,
        List<@NotNull @Valid ProposalOptionCreateRequest> options) {
    public ProposalRevisionCreateRequest {
        evidence = evidence == null ? Map.of() : evidence;
        options = options == null ? List.of() : options;
    }
}

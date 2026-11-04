package com.axcore.platform.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record HeldItemResponse(
        long id,
        Long proposalItemId,
        Long linkMappingId,
        long sourceObjectId,
        long linkTypeId,
        String status,
        String rawValue,
        String transformedValue,
        String holdReason,
        Long resolvedLinkId,
        Long resolvedSyncRunId,
        Long resolvedBy,
        Instant resolvedAt,
        String resolutionNote,
        Instant createdAt) {}

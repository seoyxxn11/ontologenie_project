package com.axcore.platform.analysis.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalysisRunResponse(
        long id,
        String runKind,
        String status,
        List<Target> targets,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> resultSummary,
        String errorMessage,
        long requestedBy,
        Map<String, Object> analysisRange,
        Long basedOnSyncRunId,
        boolean joined) {
    public record Target(long sourceResourceId) {}
}

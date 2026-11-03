package com.axcore.platform.analysis.dto;

import com.axcore.platform.analysis.dto.AnalysisRunResponse.Target;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalysisResponse(
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
        Long basedOnSyncRunId) {
    public AnalysisResponse withTargets(List<Target> targets) {
        return new AnalysisResponse(
                id,
                runKind,
                status,
                targets,
                requestedAt,
                startedAt,
                finishedAt,
                resultSummary,
                errorMessage,
                requestedBy,
                analysisRange,
                basedOnSyncRunId);
    }
}

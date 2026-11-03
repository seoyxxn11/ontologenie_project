package com.axcore.platform.analysis.dto;

import java.time.Instant;
import java.util.List;

public record AnalysisResultAck(
        long analysisRunId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        List<Long> proposalItemIds) {}

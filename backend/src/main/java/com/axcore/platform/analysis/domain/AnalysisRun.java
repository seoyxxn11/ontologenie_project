package com.axcore.platform.analysis.domain;

import java.time.Instant;

public record AnalysisRun(long id, String status, Instant startedAt, Instant finishedAt) {}

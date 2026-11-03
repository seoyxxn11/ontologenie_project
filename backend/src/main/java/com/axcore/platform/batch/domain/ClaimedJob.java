package com.axcore.platform.batch.domain;

import java.time.Instant;

/** 배치가 QUEUED에서 RUNNING으로 바꿔 가져간 작업 한 건. */
public record ClaimedJob(QueuedJobType type, long id, Instant requestedAt, Instant startedAt) {}

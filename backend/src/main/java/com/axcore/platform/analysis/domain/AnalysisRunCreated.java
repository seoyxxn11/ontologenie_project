package com.axcore.platform.analysis.domain;

/** 018로 새 분석 실행(QUEUED)을 만들었다. 합류한 요청에서는 내지 않는다. 커밋 뒤 AI에 시작을 알리는 데 쓴다. */
public record AnalysisRunCreated(long runId) {}

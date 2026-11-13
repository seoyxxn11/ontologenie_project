package com.axcore.platform.audit.domain;

public enum EventKind {
    ACTION,
    AGENT_QUERY,
    SYNC_RUN,
    CHECK_RUN,
    ANALYSIS_RUN,
    SOURCE_CHECK,
    SOURCE_PREVIEW,
    HOLD_AUTO_RESOLVE,
    /** 가리지 않은 민감 값을 사람에게 내보낸 응답(394). ADMIN만 096에서 골라 본다. */
    RESTRICTED_VIEW
}

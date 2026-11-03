package com.axcore.platform.batch.domain;

import com.axcore.platform.audit.domain.EventKind;

/**
 * 배치가 가져가 실행하는 작업 표. api가 QUEUED 행을 넣고 배치가 RUNNING → SUCCEEDED/FAILED로 바꾼다. 모든 표가 status,
 * requested_at, started_at, finished_at, error_message 칸을 같은 뜻으로 가진다.
 */
public enum QueuedJobType {
    SYNC_RUN("sync_run", EventKind.SYNC_RUN),
    CHECK_RUN("check_run", EventKind.CHECK_RUN),
    SOURCE_CHECK("source_connection_check", EventKind.SOURCE_CHECK),
    SOURCE_PREVIEW("source_preview", EventKind.SOURCE_PREVIEW);

    private final String table;
    private final EventKind eventKind;

    QueuedJobType(String table, EventKind eventKind) {
        this.table = table;
        this.eventKind = eventKind;
    }

    /** 작업 표 이름. 감사 기록의 target_type으로도 쓴다. */
    public String table() {
        return table;
    }

    public EventKind eventKind() {
        return eventKind;
    }
}

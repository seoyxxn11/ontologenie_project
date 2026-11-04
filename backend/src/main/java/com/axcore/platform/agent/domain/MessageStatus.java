package com.axcore.platform.agent.domain;

/** AGENT 메시지 상태 (USER는 없음). RUNNING = 처리 중, SUCCEEDED = 답변 끝, FAILED = 답변을 끝내지 못함 */
public enum MessageStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}

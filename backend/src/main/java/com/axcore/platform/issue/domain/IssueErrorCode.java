package com.axcore.platform.issue.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum IssueErrorCode implements ErrorCode {
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "지금 상태에서는 요청한 상태로 바꿀 수 없습니다."),
    REVERIFICATION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "해결로 변경하려면 이 문제의 재검증 통과 기록이 필요합니다.");

    private final HttpStatus status;
    private final String message;

    IssueErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}

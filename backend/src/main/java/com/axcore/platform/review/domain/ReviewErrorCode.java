package com.axcore.platform.review.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReviewErrorCode implements ErrorCode {
    REVISION_CONFLICT("다른 사용자가 먼저 후보를 고쳤습니다. 최신 내용을 다시 불러와 주세요."),
    PROPOSAL_ALREADY_DECIDED("이미 승인 또는 거절된 제안입니다.");

    private final String message;

    ReviewErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String message() {
        return message;
    }
}

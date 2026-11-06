package com.axcore.platform.review.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReviewErrorCode implements ErrorCode {
    APPLY_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "승인 반영 중 오류가 발생해 모든 변경을 취소했습니다."),
    REVISION_CONFLICT(HttpStatus.CONFLICT, "다른 사용자가 먼저 후보를 고쳤습니다. 최신 내용을 다시 불러와 주세요."),
    PROPOSAL_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 승인 또는 거절된 제안입니다."),
    REVIEW_VALIDATION_FAILED(
            HttpStatus.UNPROCESSABLE_CONTENT, "이 revision의 후보 시험이 아직 성공하지 않아 승인할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ReviewErrorCode(HttpStatus status, String message) {
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

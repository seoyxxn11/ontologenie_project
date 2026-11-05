package com.axcore.platform.ontology.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 승인한 후보를 반영하지 못한 이유. 131은 이 코드를 {@code proposal_item.apply_error}에 남긴다. */
public enum ApplyErrorCode implements ErrorCode {
    APPLY_PAYLOAD_INVALID(HttpStatus.BAD_REQUEST, "후보 내용의 형식이 맞지 않습니다."),
    APPLY_REFERENCE_MISSING(
            HttpStatus.UNPROCESSABLE_CONTENT, "후보가 가리키는 정의가 아직 없습니다. 필요한 후보를 먼저 승인해 주세요."),
    APPLY_TARGET_EXISTS(HttpStatus.CONFLICT, "같은 정의·매핑·규칙이 이미 있습니다."),
    APPLY_KIND_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "아직 반영할 수 없는 후보 종류입니다.");

    private final HttpStatus status;
    private final String message;

    ApplyErrorCode(HttpStatus status, String message) {
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

package com.axcore.platform.ontology.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 관리자가 확정한 연결(MANUAL·DOCUMENT)을 저장하지 못한 이유(Jira 329). 보류 건 승인(238)이 그대로 응답한다. */
public enum ObjectLinkErrorCode implements ErrorCode {
    LINK_SELF_REFERENCE(HttpStatus.UNPROCESSABLE_CONTENT, "같은 객체끼리는 연결할 수 없습니다."),
    LINK_END_TYPE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "객체 유형이 관계 유형의 출발·도착 객체 유형과 맞지 않습니다."),
    LINK_END_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "원천에서 삭제됐거나 수집을 멈춘 매핑의 객체는 연결할 수 없습니다."),
    LINK_CARDINALITY_EXCEEDED(
            HttpStatus.UNPROCESSABLE_CONTENT, "관계 수 제약을 넘습니다. 이미 다른 객체와 연결되어 있습니다."),
    LINK_EVIDENCE_INVALID(
            HttpStatus.BAD_REQUEST, "문서 연결의 표현 종류(expression_kind)는 OBSERVED·ESTIMATED만 됩니다."),
    LINK_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 연결된 쌍입니다."),
    LINK_SOURCE_LINKED_BY_RULE(HttpStatus.CONFLICT, "규칙이 이미 이 출발 객체를 잇고 있어 수동으로 연결할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ObjectLinkErrorCode(HttpStatus status, String message) {
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

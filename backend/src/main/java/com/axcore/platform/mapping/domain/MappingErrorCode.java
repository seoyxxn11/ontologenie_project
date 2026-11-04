package com.axcore.platform.mapping.domain;

import com.axcore.platform.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 원천 매핑 전용 오류 코드. */
public enum MappingErrorCode implements ErrorCode {
    MAPPING_IDENTITY_LOCKED(
            "객체가 만들어진 매핑의 원천 자원·객체 유형·키 컬럼은 바꿀 수 없습니다. 새 매핑을 만들고 이 매핑은 수집을 멈춰 주세요.");

    private final String message;

    MappingErrorCode(String message) {
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

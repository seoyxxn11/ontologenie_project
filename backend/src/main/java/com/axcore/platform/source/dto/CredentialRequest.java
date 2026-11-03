package com.axcore.platform.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 용도별 접속 계정 (API 010·012). password는 요청에서만 받고 암호화해 저장한다(응답·로그에 넣지 않는다). */
public record CredentialRequest(
        @NotBlank @Pattern(regexp = "SYNC|AI", message = "SYNC 또는 AI만 쓸 수 있습니다.") String purpose,
        @NotBlank @Size(max = 200) String username,
        @NotBlank String password) {

    /** 요청 내용을 로그·예외로 찍어도 비밀번호가 나오지 않게 한다. */
    @Override
    public String toString() {
        return "CredentialRequest[purpose=" + purpose + ", username=" + username + "]";
    }
}

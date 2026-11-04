package com.axcore.platform.account.dto;

import com.axcore.platform.account.domain.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** API 005. 임시 비밀번호 규칙은 Service가 {@code PasswordPolicy}로 검사한다. */
public record AccountCreateRequest(
        @NotBlank @Size(max = 100) String loginId,
        @NotBlank @Size(max = 100) String displayName,
        @NotNull RoleCode roleCode,
        @NotNull String temporaryPassword) {

    /** 임시 비밀번호가 로그·오류 응답에 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "AccountCreateRequest[loginId=%s, displayName=%s, roleCode=%s]"
                .formatted(loginId, displayName, roleCode);
    }
}

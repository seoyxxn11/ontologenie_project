package com.axcore.platform.account.dto;

import com.axcore.platform.account.domain.AccountStatus;
import com.axcore.platform.account.domain.RoleCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** API 007. 보낸 필드만 바꾼다(null은 그대로). 비밀번호는 103으로 바꾼다. */
public record AccountUpdateRequest(
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
                String displayName,
        RoleCode roleCode,
        AccountStatus status,
        @NotNull @PositiveOrZero Long rowVersion) {

    public boolean hasChange() {
        return displayName != null || roleCode != null || status != null;
    }
}

package com.axcore.platform.account.dto;

import com.axcore.platform.account.domain.AccountStatus;
import com.axcore.platform.account.domain.RoleCode;
import jakarta.validation.constraints.Min;

/** API 004 조회 조건. q는 로그인 ID·표시 이름에서 찾는다. 비어 있는 조건으로는 거르지 않는다. */
public record AccountSearchRequest(
        String q,
        AccountStatus status,
        RoleCode roleCode,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

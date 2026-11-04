package com.axcore.platform.account.controller;

import com.axcore.platform.account.dto.RoleResponse;
import com.axcore.platform.account.service.AdminAccountService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 역할 및 권한 목록 (API 008, FN-ADM-02). 계정 생성·역할 변경 화면이 고를 수 있는 역할을 보여 준다. ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/admin/roles")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AdminRoleController {

    private final AdminAccountService adminAccountService;

    @GetMapping
    public ApiResponse<List<RoleResponse>> list(
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(adminAccountService.roles(loginAccount));
    }
}

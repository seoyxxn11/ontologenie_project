package com.axcore.platform.account.controller;

import com.axcore.platform.account.dto.AccountCreateRequest;
import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.account.dto.AccountSearchRequest;
import com.axcore.platform.account.dto.AccountUpdateRequest;
import com.axcore.platform.account.service.AdminAccountService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 계정 관리 (API 004~007, FN-ADM-02·03). 권한 검사는 Service가 한다(막힌 변경도 기록해야 해서). */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AdminAccountController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AdminAccountService adminAccountService;

    @GetMapping
    public ApiResponse<List<AccountResponse>> search(
            @Valid @ModelAttribute AccountSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(
                adminAccountService.search(
                        loginAccount, request.q(), request.status(), request.roleCode(), page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountResponse> create(
            @Valid @RequestBody AccountCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(adminAccountService.create(loginAccount, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(adminAccountService.get(loginAccount, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<AccountResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody AccountUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(adminAccountService.update(loginAccount, id, request));
    }
}

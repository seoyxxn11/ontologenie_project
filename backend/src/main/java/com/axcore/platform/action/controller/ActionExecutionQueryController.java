package com.axcore.platform.action.controller;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse;
import com.axcore.platform.action.service.ActionExecutionQueryService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Action 결과·차단 이유 조회 (API 092). 권한(실행자 본인 또는 ADMIN)은 Service가 기록을 읽은 뒤 검사한다. */
@RestController
@RequestMapping("/api/v1/action-executions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ActionExecutionQueryController {

    private final ActionExecutionQueryService actionExecutionQueryService;

    @GetMapping("/{id}")
    public ApiResponse<ActionExecutionDetailResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(actionExecutionQueryService.get(loginAccount, id));
    }
}

package com.axcore.platform.action.controller;

import com.axcore.platform.action.dto.ActionDefinitionCreateRequest;
import com.axcore.platform.action.dto.ActionDefinitionResponse;
import com.axcore.platform.action.dto.ActionDefinitionSearchRequest;
import com.axcore.platform.action.dto.ActionDefinitionUpdateRequest;
import com.axcore.platform.action.service.ActionDefinitionService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

/** Action 정의 관리 (API 087~090, FN-ACT-01·08). 권한 검사는 Service가 한다(막힌 변경도 기록해야 해서). */
@RestController
@RequestMapping("/api/v1/actions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ActionDefinitionController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ActionDefinitionService actionDefinitionService;

    @GetMapping
    public ApiResponse<List<ActionDefinitionResponse>> search(
            @Valid @ModelAttribute ActionDefinitionSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(
                actionDefinitionService.search(
                        loginAccount, request.isActive(), request.actionKind(), page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ActionDefinitionResponse> create(
            @Valid @RequestBody ActionDefinitionCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(actionDefinitionService.create(loginAccount, request));
    }

    @GetMapping("/{code}")
    public ApiResponse<ActionDefinitionResponse> get(
            @PathVariable("code") @Size(max = 100) String code,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(actionDefinitionService.get(loginAccount, code));
    }

    @PatchMapping("/{code}")
    public ApiResponse<ActionDefinitionResponse> update(
            @PathVariable("code") @Size(max = 100) String code,
            @Valid @RequestBody ActionDefinitionUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(actionDefinitionService.update(loginAccount, code, request));
    }
}

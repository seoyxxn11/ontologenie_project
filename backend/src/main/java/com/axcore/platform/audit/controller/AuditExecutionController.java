package com.axcore.platform.audit.controller;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.AuditExecutionSearchRequest;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 실행·감사 이력 (API 096 목록, 097 상세, 098 변경 전후). 권한은 Service가 검사한다. */
@RestController
@RequestMapping("/api/v1/audit/actions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AuditExecutionController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditExecutionQueryService auditExecutionQueryService;

    @GetMapping
    public ApiResponse<List<AuditExecutionResponse>> search(
            @Valid @ModelAttribute AuditExecutionSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(
                auditExecutionQueryService.search(loginAccount, request, page),
                AuditExecutionQueryService.scopeOf(loginAccount));
    }

    @GetMapping("/{id}")
    public ApiResponse<ActionExecutionDetailResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(auditExecutionQueryService.get(loginAccount, id));
    }

    /** 098. 명세상 097과 같은 응답이다(실행 기록과 변경 전후). */
    @GetMapping("/{id}/changes")
    public ApiResponse<ActionExecutionDetailResponse> changes(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(auditExecutionQueryService.get(loginAccount, id));
    }
}

package com.axcore.platform.audit.controller;

import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
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
import org.springframework.web.bind.annotation.RestController;

/** 객체 관련 실행 이력 (API 080, Agent OBJECT_EXECUTIONS Tool도 쓴다). 권한은 Service가 검사한다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ObjectExecutionController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditExecutionQueryService auditExecutionQueryService;

    @GetMapping("/api/v1/objects/{id}/executions")
    public ApiResponse<List<AuditExecutionResponse>> search(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute ObjectExecutionSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(
                auditExecutionQueryService.objectExecutions(loginAccount, id, request, page));
    }
}

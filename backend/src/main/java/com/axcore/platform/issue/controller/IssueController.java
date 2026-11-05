package com.axcore.platform.issue.controller;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueSearchRequest;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.service.IssueQueryService;
import com.axcore.platform.issue.service.IssueStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정합성 문제 목록 065, 문제·원본 근거 조회 066, 처리 상태 변경 067. 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/issues")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class IssueController {

    private final IssueQueryService issueQueryService;
    private final IssueStatusService issueStatusService;

    @GetMapping
    public ApiResponse<List<IssueResponse>> search(
            @Valid @ModelAttribute IssueSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        return ApiResponse.ofPage(issueQueryService.search(request, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<IssueResponse> getIssue(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueQueryService.getIssue(id));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<IssueResponse> changeStatus(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @RequestBody IssueStatusChangeRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueStatusService.change(id, request, account.id()));
    }

    private static void requireAdmin(LoginAccount account) {
        if (account == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!"ADMIN".equals(account.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }
    }
}

package com.axcore.platform.issue.controller;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.dto.IssueForwardRequest;
import com.axcore.platform.issue.dto.IssueHistoryResponse;
import com.axcore.platform.issue.dto.IssueHistorySearchRequest;
import com.axcore.platform.issue.dto.IssueManualLinkRequest;
import com.axcore.platform.issue.dto.IssueManualLinkResponse;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueSearchRequest;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.dto.IssueVerificationResponse;
import com.axcore.platform.issue.dto.IssueVerificationSearchRequest;
import com.axcore.platform.issue.service.IssueForwardService;
import com.axcore.platform.issue.service.IssueHistoryService;
import com.axcore.platform.issue.service.IssueManualLinkService;
import com.axcore.platform.issue.service.IssueQueryService;
import com.axcore.platform.issue.service.IssueStatusService;
import com.axcore.platform.issue.service.IssueVerificationQueryService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정합성 문제 조회·처리 및 상태·재검증 이력 API(065~070). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/issues")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class IssueController {

    private final IssueQueryService issueQueryService;
    private final IssueManualLinkService issueManualLinkService;
    private final IssueStatusService issueStatusService;
    private final IssueForwardService issueForwardService;
    private final IssueHistoryService issueHistoryService;
    private final IssueVerificationQueryService issueVerificationQueryService;

    @GetMapping
    public ApiResponse<List<IssueResponse>> search(
            @Valid @ModelAttribute IssueSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        return ApiResponse.ofPage(
                issueQueryService.search(account, request, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<IssueResponse> getIssue(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueQueryService.getIssue(account, id));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<IssueResponse> changeStatus(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @RequestBody IssueStatusChangeRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueStatusService.change(id, request, account));
    }

    @PostMapping("/{id}/forward")
    public ApiResponse<IssueResponse> forward(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @RequestBody IssueForwardRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueForwardService.forward(id, request, account));
    }

    @PostMapping("/{issueId}/manual-links")
    public ApiResponse<IssueManualLinkResponse> createManualLink(
            @PathVariable("issueId") @Min(value = 1, message = "issueId는 1 이상이어야 합니다.")
                    long issueId,
            @Valid @RequestBody IssueManualLinkRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(issueManualLinkService.create(issueId, request, account));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<IssueHistoryResponse>> history(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @ModelAttribute IssueHistorySearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        return ApiResponse.ofPage(issueHistoryService.search(id, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}/verifications")
    public ApiResponse<List<IssueVerificationResponse>> verifications(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @ModelAttribute IssueVerificationSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        return ApiResponse.ofPage(
                issueVerificationQueryService.search(account, id, PageRequest.of(page, size)));
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

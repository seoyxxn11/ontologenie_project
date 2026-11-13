package com.axcore.platform.check.controller;

import com.axcore.platform.check.dto.CheckRunCreateRequest;
import com.axcore.platform.check.dto.CheckRunRequestResponse;
import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.CheckRunSearchRequest;
import com.axcore.platform.check.dto.ReverificationComparisonResponse;
import com.axcore.platform.check.service.CheckRunQueryService;
import com.axcore.platform.check.service.CheckRunRequestService;
import com.axcore.platform.check.service.ReverificationComparisonService;
import com.axcore.platform.check.service.ReverificationComparisonService.Comparison;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.response.PageMeta;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 검사 실행 목록 059, 수동 검사·재검증 요청 060, 검사 상태 조회 061. 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/checks/runs")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class CheckRunController {

    private final CheckRunQueryService checkRunQueryService;
    private final CheckRunRequestService checkRunRequestService;
    private final ReverificationComparisonService comparisons;

    @GetMapping
    public ApiResponse<List<CheckRunResponse>> search(
            @Valid @ModelAttribute CheckRunSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        return ApiResponse.ofPage(checkRunQueryService.search(request, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CheckRunResponse> get(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(checkRunQueryService.get(id));
    }

    @GetMapping("/{id}/comparison")
    public ApiResponse<ReverificationComparisonResponse> comparison(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        Comparison comparison = comparisons.get(id, PageRequest.of(page, size));
        return new ApiResponse<>(
                new ReverificationComparisonResponse(
                        comparison.comparedToRunId(),
                        comparison.coverage(),
                        comparison.hasBaselinePassEvidence(),
                        comparison.rows().getContent()),
                PageMeta.from(comparison.rows()));
    }

    /** 새로 접수하거나 진행 중인 같은 검사에 합류하면 202다. 끝난 결과는 다시 쓰지 않는다(389). */
    @PostMapping
    public ResponseEntity<ApiResponse<CheckRunRequestResponse>> request(
            @Valid @RequestBody CheckRunCreateRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        CheckRunRequestResponse response = checkRunRequestService.request(request, account);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
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

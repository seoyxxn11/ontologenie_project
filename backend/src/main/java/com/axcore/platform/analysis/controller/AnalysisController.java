package com.axcore.platform.analysis.controller;

import com.axcore.platform.analysis.dto.AnalysisCreateRequest;
import com.axcore.platform.analysis.dto.AnalysisProposalSearchRequest;
import com.axcore.platform.analysis.dto.AnalysisResponse;
import com.axcore.platform.analysis.dto.AnalysisRunResponse;
import com.axcore.platform.analysis.dto.AnalysisSearchRequest;
import com.axcore.platform.analysis.service.AnalysisCreateService;
import com.axcore.platform.analysis.service.AnalysisQueryService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.ProposalResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisCreateService service;
    private final AnalysisQueryService queries;

    @Operation(summary = "018 선택 범위 AI 분석 실행 접수")
    @PostMapping("/api/v1/analyses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisRunResponse> create(
            @Valid @RequestBody AnalysisCreateRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(service.create(request, account));
    }

    @Operation(summary = "017 분석 실행 목록")
    @GetMapping("/api/v1/analyses")
    public ApiResponse<List<AnalysisResponse>> search(
            @Valid @ModelAttribute AnalysisSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.ofPage(queries.search(request, page(request.page(), request.size())));
    }

    @Operation(summary = "019 분석 실행 상태·결과 조회")
    @GetMapping("/api/v1/analyses/{id}")
    public ApiResponse<AnalysisResponse> get(
            @PathVariable("id") @Min(1) long id, @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(queries.get(id));
    }

    @Operation(summary = "020 분석에서 생성된 제안 조회")
    @GetMapping("/api/v1/analyses/{id}/proposals")
    public ApiResponse<List<ProposalResponse>> proposals(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute AnalysisProposalSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.ofPage(
                queries.proposals(id, request, page(request.page(), request.size())));
    }

    private static PageRequest page(Integer page, Integer size) {
        return PageRequest.of(
                page == null ? 0 : page, size == null ? 20 : size, Sort.by("id").descending());
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

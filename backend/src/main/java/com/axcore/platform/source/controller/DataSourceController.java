package com.axcore.platform.source.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.ConnectionCheckResponse;
import com.axcore.platform.source.dto.ConnectionCheckResultResponse;
import com.axcore.platform.source.dto.SourceColumnResponse;
import com.axcore.platform.source.dto.SourceCreateRequest;
import com.axcore.platform.source.dto.SourceDetailResponse;
import com.axcore.platform.source.dto.SourceResourceResponse;
import com.axcore.platform.source.dto.SourceResourceSearchRequest;
import com.axcore.platform.source.dto.SourceResponse;
import com.axcore.platform.source.dto.SourceSearchRequest;
import com.axcore.platform.source.dto.SourceUpdateRequest;
import com.axcore.platform.source.service.DataSourceService;
import com.axcore.platform.source.service.SourceCatalogService;
import com.axcore.platform.source.service.SourceConnectionCheckService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
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

/** 원천 정보·연결 검사·자원 목록 (API 009~015, 100, FN-SRC-01·02). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/sources")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class DataSourceController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DataSourceService dataSourceService;
    private final SourceConnectionCheckService connectionCheckService;
    private final SourceCatalogService catalogService;

    @GetMapping
    public ApiResponse<List<SourceResponse>> search(
            @Valid @ModelAttribute SourceSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.ofPage(
                dataSourceService.search(
                        request.sourceKind(),
                        request.connectionStatus(),
                        page(request.page(), request.size())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceResponse> create(
            @Valid @RequestBody SourceCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.create(request, loginAccount.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SourceDetailResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SourceResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody SourceUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.update(id, request, loginAccount.id()));
    }

    @PostMapping("/{id}/test-connection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ConnectionCheckResponse> requestConnectionCheck(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(connectionCheckService.request(id, loginAccount));
    }

    @GetMapping("/{id}/connection-checks/{checkId}")
    public ApiResponse<ConnectionCheckResultResponse> connectionCheck(
            @PathVariable("id") @Min(1) long id,
            @PathVariable("checkId") @Min(1) long checkId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(connectionCheckService.result(id, checkId));
    }

    @GetMapping("/{id}/resources")
    public ApiResponse<List<SourceResourceResponse>> resources(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute SourceResourceSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.ofPage(
                catalogService.resources(id, request, page(request.page(), request.size())));
    }

    @GetMapping("/{id}/resources/{resourceId}/columns")
    public ApiResponse<List<SourceColumnResponse>> columns(
            @PathVariable("id") @Min(1) long id,
            @PathVariable("resourceId") @Min(1) long resourceId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(catalogService.columns(id, resourceId));
    }

    private static PageRequest page(Integer page, Integer size) {
        return PageRequest.of(page == null ? 0 : page, size == null ? DEFAULT_PAGE_SIZE : size);
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}

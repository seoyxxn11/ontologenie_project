package com.axcore.platform.mapping.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.mapping.dto.ObjectMappingCreateRequest;
import com.axcore.platform.mapping.dto.ObjectMappingResponse;
import com.axcore.platform.mapping.dto.ObjectMappingSearchRequest;
import com.axcore.platform.mapping.dto.ObjectMappingUpdateRequest;
import com.axcore.platform.mapping.dto.ObjectMappingUpdateResponse;
import com.axcore.platform.mapping.service.ObjectMappingService;
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

/** 객체 원천 매핑 (API 034~036, FN-MAP-02). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/object-mappings")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ObjectMappingController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ObjectMappingService mappingService;

    @GetMapping
    public ApiResponse<List<ObjectMappingResponse>> search(
            @Valid @ModelAttribute ObjectMappingSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        return ApiResponse.ofPage(
                mappingService.search(
                        request.sourceResourceId(),
                        request.objectTypeId(),
                        PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ObjectMappingResponse> create(
            @Valid @RequestBody ObjectMappingCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(mappingService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ObjectMappingUpdateResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody ObjectMappingUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(mappingService.update(id, request));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}

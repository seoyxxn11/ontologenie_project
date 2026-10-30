package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectTypeCreateRequest;
import com.axcore.platform.ontology.dto.ObjectTypeResponse;
import com.axcore.platform.ontology.dto.ObjectTypeSearchRequest;
import com.axcore.platform.ontology.dto.ObjectTypeUpdateRequest;
import com.axcore.platform.ontology.service.ObjectTypeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 객체 유형 관리 (API 021~025, FN-ONT-01). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/object-types")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ObjectTypeController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ObjectTypeService objectTypeService;

    @GetMapping
    public ApiResponse<List<ObjectTypeResponse>> search(
            @Valid @ModelAttribute ObjectTypeSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        return ApiResponse.ofPage(
                objectTypeService.search(request.q(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ObjectTypeResponse> getObjectType(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(objectTypeService.getObjectType(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ObjectTypeResponse> create(
            @Valid @RequestBody ObjectTypeCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(objectTypeService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ObjectTypeResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody ObjectTypeUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(objectTypeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        objectTypeService.delete(id);
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}

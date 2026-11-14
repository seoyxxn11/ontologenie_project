package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.DefinitionEditAction;
import com.axcore.platform.ontology.dto.PropertyCreateRequest;
import com.axcore.platform.ontology.dto.PropertyResponse;
import com.axcore.platform.ontology.dto.PropertyUpdateRequest;
import com.axcore.platform.ontology.service.DefinitionEditRecorder;
import com.axcore.platform.ontology.service.PropertyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 속성 관리 (API 026~029, FN-ONT-02). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class PropertyController {

    /** 026을 객체 조회 범위로 받는 값. 관리자도 검색 조건 목록은 이 범위로 받는다(420). */
    private static final String VIEWABLE_SCOPE = "viewable";

    private final PropertyService propertyService;
    private final DefinitionEditRecorder editRecorder;
    private final DataAccessPolicy dataAccessPolicy;

    /**
     * API 026. 관리자는 정의를 관리하므로 모든 속성을 본다. 다른 역할은 객체 조회(076~078)와 같은 범위로, 볼 수 있는 유형의 볼 수 있는 속성만 받는다.
     * 탐색 화면의 속성 조건 검색과 속성 이름·단위 표시에 쓴다. {@code scope=viewable}이면 관리자도 객체 조회 범위로 받는다. 검색 조건 목록에 그
     * 사람이 검색할 수 없는 민감 속성을 보이지 않기 위해서다(S15P31C106-420).
     */
    @GetMapping("/object-types/{id}/properties")
    public ApiResponse<List<PropertyResponse>> findProperties(
            @PathVariable("id") @Min(1) long objectTypeId,
            @RequestParam(name = "scope", required = false) String scope,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        if (isAdmin(loginAccount) && !VIEWABLE_SCOPE.equals(scope)) {
            return ApiResponse.of(propertyService.findProperties(objectTypeId));
        }
        return ApiResponse.of(
                propertyService.findViewableProperties(
                        objectTypeId, dataAccessPolicy.of(loginAccount)));
    }

    @PostMapping("/object-types/{id}/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PropertyResponse> create(
            @PathVariable("id") @Min(1) long objectTypeId,
            @Valid @RequestBody PropertyCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.create(
                        loginAccount,
                        DefinitionEditAction.CREATE_PROPERTY,
                        editRecorder.input("objectTypeId", objectTypeId, request),
                        () -> propertyService.create(objectTypeId, request),
                        PropertyResponse::id));
    }

    @PatchMapping("/properties/{id}")
    public ApiResponse<PropertyResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody PropertyUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.update(
                        loginAccount,
                        DefinitionEditAction.UPDATE_PROPERTY,
                        id,
                        editRecorder.input(id, request),
                        () -> propertyService.update(id, request)));
    }

    @DeleteMapping("/properties/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        editRecorder.delete(
                loginAccount,
                DefinitionEditAction.DELETE_PROPERTY,
                id,
                editRecorder.input(id, null),
                () -> {
                    propertyService.delete(id);
                    return null;
                });
    }

    private static boolean isAdmin(LoginAccount loginAccount) {
        return RoleCode.ADMIN.name().equals(loginAccount.roleCode());
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!isAdmin(loginAccount)) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}

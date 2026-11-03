package com.axcore.platform.mapping.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.mapping.domain.TransformFunction;
import com.axcore.platform.mapping.dto.TransformFunctionResponse;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 허용 변환 함수 목록 (API 041, FN-MAP-05). 매핑 편집 화면이 쓰며 ADMIN만 볼 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/transforms")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TransformController {

    private static final List<TransformFunctionResponse> FUNCTIONS =
            TransformFunction.ALL.stream().map(TransformFunctionResponse::from).toList();

    @GetMapping
    public ApiResponse<List<TransformFunctionResponse>> list(
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(FUNCTIONS);
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}

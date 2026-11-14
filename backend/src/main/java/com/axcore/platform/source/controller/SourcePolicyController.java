package com.axcore.platform.source.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.SourcePolicyReportResponse;
import com.axcore.platform.source.service.SourcePolicyReportService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원천 데이터 정책 위반 보고(S15P31C106-402). ADMIN만 쓸 수 있다. 정책 변경·배포 전과 유형 공개 전에 본다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SourcePolicyController {

    private final SourcePolicyReportService reportService;

    @GetMapping("/api/v1/sources/policy-violations")
    public ApiResponse<SourcePolicyReportResponse> report(
            @AuthenticationPrincipal LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
        return ApiResponse.of(reportService.report());
    }
}

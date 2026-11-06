package com.axcore.platform.analysis.controller;

import com.axcore.platform.analysis.service.TestAnalysisCleanupService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테스트로 돌린 분석과 그 제안을 지우는 임시 API(S15P31C106-307). API 명세에 없는 개발용이며 시연 전 없앤다. 없앨 때는
 * TestAnalysisCleanup* 세 파일과 그 테스트, 화면의 TestAnalysisDelete를 지운다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class TestAnalysisCleanupController {
    private final TestAnalysisCleanupService service;

    @Operation(summary = "(임시) 끝난 테스트 분석과 그 제안 모두 지우기")
    @DeleteMapping("/api/v1/dev/test-analyses")
    public ApiResponse<Map<String, Object>> deleteAll(
            @AuthenticationPrincipal LoginAccount account) {
        if (account == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!"ADMIN".equals(account.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }
        return ApiResponse.of(service.deleteAll(account));
    }
}

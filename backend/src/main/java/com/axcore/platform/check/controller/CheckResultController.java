package com.axcore.platform.check.controller;

import com.axcore.platform.check.dto.CheckResultResponse;
import com.axcore.platform.check.dto.CheckResultSearchRequest;
import com.axcore.platform.check.service.CheckResultService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 검사 결과·매칭률 조회 API 062. ADMIN만 조회할 수 있다. */
@RestController
@RequestMapping("/api/v1/checks/runs")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class CheckResultController {

    private final CheckResultService checkResultService;

    @GetMapping("/{id}/results")
    public ApiResponse<List<CheckResultResponse>> search(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @ModelAttribute CheckResultSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {

        requireAdmin(loginAccount);

        // 페이지 조건을 생략하면 기존 목록 API와 같은 기본값을 사용한다.
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();

        // 앞서 작성한 DB 테스트와 같이 결과 ID 오름차순으로 조회한다.
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id").ascending());

        return ApiResponse.ofPage(checkResultService.search(id, request, pageable));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (loginAccount == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }

        if (!"ADMIN".equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }
    }
}

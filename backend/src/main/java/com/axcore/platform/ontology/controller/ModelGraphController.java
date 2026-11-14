package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ModelGraphResponse;
import com.axcore.platform.ontology.service.ModelGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 객체 유형 관계도 (API 042, FN-ONT-08). 관리자는 모든 유형을 본다. 다른 역할은 객체 조회와 같은 범위로, 볼 수 있는 유형과 그 유형끼리의 관계만
 * 받는다(S15P31C106-420). 탐색 화면의 "전체 구조"가 쓴다.
 */
@RestController
@RequestMapping("/api/v1/ontology/model-graph")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ModelGraphController {

    private final ModelGraphService modelGraphService;
    private final DataAccessPolicy dataAccessPolicy;

    @GetMapping
    public ApiResponse<ModelGraphResponse> getGraph(
            @AuthenticationPrincipal LoginAccount loginAccount) {
        if (RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            return ApiResponse.of(modelGraphService.getGraph());
        }
        return ApiResponse.of(modelGraphService.getVisibleGraph(dataAccessPolicy.of(loginAccount)));
    }
}

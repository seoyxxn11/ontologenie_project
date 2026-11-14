package com.axcore.platform.ontology.controller;

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
 * 객체 유형 관계도 (API 042, FN-ONT-08). 탐색 화면의 "전체 구조"가 쓴다. 관리자를 포함해 모든 역할이 객체 조회와 같은 범위로, 볼 수 있는 유형과 그
 * 유형끼리의 관계만 받는다(S15P31C106-420, 427). 연결 건수가 객체 데이터라 관리자 데이터 범위(V21) 밖 유형은 보이지 않아야 하고, 탐색 화면의 다른
 * 목록과 범위가 같아야 노드를 눌렀을 때 검색 조건이 깨지지 않는다.
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
        return ApiResponse.of(modelGraphService.getVisibleGraph(dataAccessPolicy.of(loginAccount)));
    }
}

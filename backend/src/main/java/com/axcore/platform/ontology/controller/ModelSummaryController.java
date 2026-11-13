package com.axcore.platform.ontology.controller;

import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.service.ModelSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI·Agent용 모델 요약 (API 043, FN-ONT-09). 내부 API라 X-Internal-Token으로만 부른다(보안 설정). Agent는 HTTP가 아니라
 * {@link ModelSummaryService}를 질문한 사용자의 범위로 직접 부른다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ModelSummaryController {

    private final ModelSummaryService modelSummaryService;
    private final DataAccessPolicy dataAccessPolicy;

    @Operation(summary = "043 AI·Agent용 모델 요약(내부)")
    @GetMapping("/api/v1/internal/ontology/model-summary")
    public ApiResponse<ModelSummaryResponse> summary() {
        return ApiResponse.of(modelSummaryService.summarize(dataAccessPolicy.forDefinitions()));
    }
}

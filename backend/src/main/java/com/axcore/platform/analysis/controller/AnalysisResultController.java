package com.axcore.platform.analysis.controller;

import com.axcore.platform.analysis.dto.AnalysisResultAck;
import com.axcore.platform.analysis.dto.AnalysisResultRequest;
import com.axcore.platform.analysis.service.AnalysisResultService;
import com.axcore.platform.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AnalysisResultController {
    private final AnalysisResultService service;

    @Operation(summary = "105 분석 결과 내부 저장")
    @PostMapping("/api/v1/internal/analyses/{id}/results")
    public ApiResponse<AnalysisResultAck> accept(
            @PathVariable @Positive long id, @Valid @RequestBody AnalysisResultRequest request) {
        return ApiResponse.of(service.accept(id, request));
    }
}

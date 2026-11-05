package com.axcore.platform.action.controller;

import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import com.axcore.platform.action.dto.ReassignmentPlanSearchRequest;
import com.axcore.platform.action.service.ReassignmentPlanQueryService;
import com.axcore.platform.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재배정 계획 조회 (API 093·094·095). 로그인만 확인한다(4역할 모두 조회, D4). */
@RestController
@RequestMapping("/api/v1/reassignment-plans")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ReassignmentPlanController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ReassignmentPlanQueryService reassignmentPlanQueryService;

    @GetMapping
    public ApiResponse<List<ReassignmentPlanResponse>> search(
            @Valid @ModelAttribute ReassignmentPlanSearchRequest request) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(
                reassignmentPlanQueryService.search(
                        request.fromEquipmentId(), request.createdBy(), page));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReassignmentPlanResponse> get(@PathVariable("id") @Min(1) long id) {
        return ApiResponse.of(reassignmentPlanQueryService.get(id));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<ReassignmentPlanItemResponse>> items(
            @PathVariable("id") @Min(1) long id) {
        return ApiResponse.of(reassignmentPlanQueryService.items(id));
    }
}

package com.axcore.platform.action.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 재배정 계획 (API 093·094의 ReassignmentPlan). status는 CREATED뿐이며 원천 MES에 적용됐다는 뜻이 아니다.
 * actionExecutionId로 계획을 만든 실행 기록(092)을 찾는다.
 */
public record ReassignmentPlanResponse(
        Long id,
        String status,
        Long fromEquipmentId,
        Map<String, Object> selectionBasis,
        Instant dataAsOf,
        Long actionExecutionId,
        Long agentConversationId,
        Long createdBy,
        Instant createdAt,
        List<ReassignmentPlanItemResponse> items) {

    public ReassignmentPlanResponse withItems(List<ReassignmentPlanItemResponse> planItems) {
        return new ReassignmentPlanResponse(
                id,
                status,
                fromEquipmentId,
                selectionBasis,
                dataAsOf,
                actionExecutionId,
                agentConversationId,
                createdBy,
                createdAt,
                planItems);
    }
}

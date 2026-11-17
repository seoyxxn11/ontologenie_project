package com.axcore.platform.action.dto;

import com.axcore.platform.action.domain.ReassignmentPlan;
import com.axcore.platform.action.handler.ReassignmentSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 재배정 계획 (API 093·094의 ReassignmentPlan). status는 CREATED뿐이며 원천 MES에 적용됐다는 뜻이 아니다.
 * actionExecutionId로 계획을 만든 실행 기록(092)을 찾는다. selectionBasis는 저장된 근거 중 이유 문장(reason)만 담는다(404). 다른
 * 필드는 정해진 모양이 없어 무엇이 들었는지 알 수 없으므로 내보내지 않는다.
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
        String createdByName,
        Instant createdAt,
        List<ReassignmentPlanItemResponse> items) {

    public static ReassignmentPlanResponse from(
            ReassignmentPlan plan, String createdByName, List<ReassignmentPlanItemResponse> items) {
        return new ReassignmentPlanResponse(
                plan.getId(),
                plan.getStatus(),
                plan.getFromEquipmentId(),
                ReassignmentSnapshot.selectionBasis(plan.getSelectionBasis()),
                plan.getDataAsOf(),
                plan.getActionExecutionId(),
                plan.getAgentConversationId(),
                plan.getCreatedBy(),
                createdByName,
                plan.getCreatedAt(),
                items);
    }
}

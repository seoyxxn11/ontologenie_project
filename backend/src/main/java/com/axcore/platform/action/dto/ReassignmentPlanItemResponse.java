package com.axcore.platform.action.dto;

import com.axcore.platform.action.domain.ReassignmentPlanItem;
import java.util.List;
import java.util.Map;

/**
 * 재배정 계획 항목 (API 093·094·095의 ReassignmentItem). 새 계획은 공정마다 한 항목이다. operationCode가 null이면 공정별로 나누기
 * 전에 저장한 작업지시 전체 항목이거나, 보는 사람에게 가린 값이다. operationCode와 sourceSnapshot의 민감 속성 값은 보는 사람의 범위로 가리고, 가린
 * 속성 코드는 restrictedPropertyCodes에 담는다(384·390).
 */
public record ReassignmentPlanItemResponse(
        Long id,
        Long workOrderId,
        String operationCode,
        Long fromEquipmentId,
        Long toEquipmentId,
        Map<String, Object> sourceSnapshot,
        List<String> restrictedPropertyCodes) {

    /** 가린 공정 코드와 스냅샷으로 만든다. */
    public static ReassignmentPlanItemResponse of(
            ReassignmentPlanItem item,
            String maskedOperationCode,
            Map<String, Object> maskedSnapshot,
            List<String> restrictedPropertyCodes) {
        return new ReassignmentPlanItemResponse(
                item.getId(),
                item.getWorkOrderId(),
                maskedOperationCode,
                item.getFromEquipmentId(),
                item.getToEquipmentId(),
                maskedSnapshot == null ? Map.of() : maskedSnapshot,
                List.copyOf(restrictedPropertyCodes));
    }
}

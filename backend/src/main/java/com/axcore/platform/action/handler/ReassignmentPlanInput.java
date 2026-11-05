package com.axcore.platform.action.handler;

import java.util.List;
import java.util.Map;

/** CREATE_REASSIGNMENT_PLAN의 input(Action 정의서 4-1). 형식은 공통 실행기가 inputSchema로 먼저 검사한다. */
public record ReassignmentPlanInput(
        Long fromEquipmentId,
        List<Item> items,
        Map<String, Object> selectionBasis,
        String dataAsOf) {

    /** operationCode가 없으면 작업지시에서 정지 설비에 배정된 남은 공정 전체를 옮긴다. */
    public record Item(Long workOrderId, String operationCode, Long toEquipmentId) {}
}

package com.axcore.platform.action.dto;

import java.util.Map;

/** 재배정 계획 항목 (API 093·094·095의 ReassignmentItem). operationCode가 null이면 작업지시 전체다. */
public record ReassignmentPlanItemResponse(
        Long id,
        Long workOrderId,
        String operationCode,
        Long fromEquipmentId,
        Long toEquipmentId,
        Map<String, Object> sourceSnapshot) {}

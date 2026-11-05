package com.axcore.platform.audit.dto;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실행·감사 이력 한 줄 (API 096·080). 표시 이름(actionCode·actionName·actorName·targetLabel)과 종류(category)는 서버가
 * 채운다. input·result·변경 내용은 기록할 때 이미 비밀값을 뺀 값이다. restrictedPropertyCodes는 제한 속성 가림(S15P31C106-205)이
 * 생기기 전까지 늘 빈 목록이다.
 */
public record AuditExecutionResponse(
        Long id,
        UUID requestId,
        EventKind eventKind,
        ExecutionCategory category,
        Long actionDefinitionId,
        String actionCode,
        String actionName,
        ActorType actorType,
        Long actorId,
        String actorName,
        String actorRoleCode,
        String checkedPermissionCode,
        EntryPoint entryPoint,
        String targetType,
        Long targetId,
        String targetLabel,
        Map<String, Object> input,
        ExecutionStatus status,
        String blockedReason,
        Map<String, Object> result,
        Instant requestedAt,
        Instant finishedAt,
        List<EntityChangeResponse> entityChanges,
        List<String> restrictedPropertyCodes) {

    public AuditExecutionResponse withEntityChanges(List<EntityChangeResponse> changes) {
        return new AuditExecutionResponse(
                id,
                requestId,
                eventKind,
                category,
                actionDefinitionId,
                actionCode,
                actionName,
                actorType,
                actorId,
                actorName,
                actorRoleCode,
                checkedPermissionCode,
                entryPoint,
                targetType,
                targetId,
                targetLabel,
                input,
                status,
                blockedReason,
                result,
                requestedAt,
                finishedAt,
                changes,
                restrictedPropertyCodes);
    }
}

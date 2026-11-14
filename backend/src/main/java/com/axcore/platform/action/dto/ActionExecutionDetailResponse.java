package com.axcore.platform.action.dto;

import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.domain.EntityChange;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실행 기록 하나와 그 실행이 바꾼 행 (API 092). input·result·변경 내용은 기록할 때 이미 비밀값을 뺀 값이다. input·result·변경 내용은
 * Handler가 알린 자리만 보여 주고 민감 속성 값은 보는 사람의 범위로 가리며, 가린 속성 코드는 restrictedPropertyCodes에 담는다(감사 097·098과
 * 같은 규칙, 384·390·404).
 */
public record ActionExecutionDetailResponse(
        Long id,
        UUID requestId,
        EventKind eventKind,
        Long actionDefinitionId,
        ActorType actorType,
        Long actorId,
        String actorRoleCode,
        String checkedPermissionCode,
        EntryPoint entryPoint,
        String targetType,
        Long targetId,
        Map<String, Object> input,
        ExecutionStatus status,
        String blockedReason,
        Map<String, Object> result,
        Instant requestedAt,
        Instant finishedAt,
        List<EntityChangeResponse> entityChanges,
        List<String> restrictedPropertyCodes) {

    /** 가린 input·result와 변경 내용으로 만든다. */
    public static ActionExecutionDetailResponse of(
            ActionExecution execution,
            Map<String, Object> maskedInput,
            Map<String, Object> maskedResult,
            String maskedBlockedReason,
            List<EntityChangeResponse> maskedChanges,
            List<String> restrictedPropertyCodes) {
        return new ActionExecutionDetailResponse(
                execution.getId(),
                execution.getRequestId(),
                execution.getEventKind(),
                execution.getActionDefinitionId(),
                execution.getActorType(),
                execution.getActorId(),
                execution.getActorRoleCode(),
                execution.getCheckedPermissionCode(),
                execution.getEntryPoint(),
                execution.getTargetType(),
                execution.getTargetId(),
                orEmpty(maskedInput),
                execution.getStatus(),
                maskedBlockedReason,
                orEmpty(maskedResult),
                execution.getRequestedAt(),
                execution.getFinishedAt(),
                List.copyOf(maskedChanges),
                List.copyOf(restrictedPropertyCodes));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    /** 바뀐 행 하나. INSERT는 beforeData, DELETE는 afterData가 없다. */
    public record EntityChangeResponse(
            String tableName,
            Map<String, Object> rowKey,
            ChangeKind changeKind,
            Map<String, Object> beforeData,
            Map<String, Object> afterData) {

        public static EntityChangeResponse from(EntityChange change) {
            return new EntityChangeResponse(
                    change.getTableName(),
                    change.getRowKey(),
                    change.getChangeKind(),
                    change.getBeforeData(),
                    change.getAfterData());
        }
    }
}

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

/** 실행 기록 하나와 그 실행이 바꾼 행 (API 092). input·result·변경 내용은 기록할 때 이미 비밀값을 뺀 값이다. */
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
        List<EntityChangeResponse> entityChanges) {

    public static ActionExecutionDetailResponse of(
            ActionExecution execution, List<EntityChange> changes) {
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
                orEmpty(execution.getInput()),
                execution.getStatus(),
                execution.getBlockedReason(),
                orEmpty(execution.getResult()),
                execution.getRequestedAt(),
                execution.getFinishedAt(),
                changes.stream().map(EntityChangeResponse::from).toList());
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

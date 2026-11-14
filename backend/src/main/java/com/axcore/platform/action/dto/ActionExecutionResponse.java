package com.axcore.platform.action.dto;

import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API 091 응답. 실행됨(SUCCEEDED·FAILED)과 차단됨(BLOCKED) 모두 200이고 status로 구분한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActionExecutionResponse(
        Long id,
        UUID requestId,
        EventKind eventKind,
        Long actionDefinitionId,
        ExecutionStatus status,
        ActorType actorType,
        Long actorId,
        String actorRoleCode,
        String checkedPermissionCode,
        EntryPoint entryPoint,
        String targetType,
        Long targetId,
        Long agentMessageId,
        Map<String, Object> input,
        Map<String, Object> result,
        String blockedReason,
        Instant requestedAt,
        Instant finishedAt) {

    /** 결과와 차단 이유만 바꾼 응답. 실행기가 보는 사람에 맞춰 가린 값을 담을 때 쓴다(404). */
    public ActionExecutionResponse masked(
            Map<String, Object> maskedResult, String maskedBlockedReason) {
        return new ActionExecutionResponse(
                id,
                requestId,
                eventKind,
                actionDefinitionId,
                status,
                actorType,
                actorId,
                actorRoleCode,
                checkedPermissionCode,
                entryPoint,
                targetType,
                targetId,
                agentMessageId,
                input,
                maskedResult,
                maskedBlockedReason,
                requestedAt,
                finishedAt);
    }

    public static ActionExecutionResponse from(ActionExecution execution) {
        return new ActionExecutionResponse(
                execution.getId(),
                execution.getRequestId(),
                execution.getEventKind(),
                execution.getActionDefinitionId(),
                execution.getStatus(),
                execution.getActorType(),
                execution.getActorId(),
                execution.getActorRoleCode(),
                execution.getCheckedPermissionCode(),
                execution.getEntryPoint(),
                execution.getTargetType(),
                execution.getTargetId(),
                execution.getAgentMessageId(),
                execution.getInput(),
                execution.getResult(),
                execution.getBlockedReason(),
                execution.getRequestedAt(),
                execution.getFinishedAt());
    }
}

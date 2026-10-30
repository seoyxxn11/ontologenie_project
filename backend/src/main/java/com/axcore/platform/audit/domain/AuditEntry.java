package com.axcore.platform.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

/**
 * 남길 감사 기록 한 줄과 그 실행이 바꾼 행들. {@code AuditEntry.builder()}로 만들어 {@code AuditRecorder}에 넘긴다.
 *
 * <p>필수: eventKind, actor, entryPoint, status. 감사 기록은 끝난 결과만 남기므로 RUNNING은 받지 않는다. requestId를 비우면
 * 새로 만들고, requestedAt을 비우면 기록하는 시각을 쓴다. 나머지는 해당할 때만 채운다.
 */
public record AuditEntry(
        EventKind eventKind,
        Actor actor,
        EntryPoint entryPoint,
        ExecutionStatus status,
        UUID requestId,
        Long actionDefinitionId,
        String checkedPermissionCode,
        String targetType,
        Long targetId,
        Map<String, Object> input,
        Map<String, Object> result,
        String blockedReason,
        Instant requestedAt,
        AgentResultCategory resultCategory,
        Instant dataAsOf,
        Long agentMessageId,
        String toolCode,
        Integer sequenceNo,
        List<ChangeEntry> changes) {

    public AuditEntry {
        Objects.requireNonNull(eventKind, "eventKind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(status, "status");
        if (status == ExecutionStatus.RUNNING) {
            throw new IllegalArgumentException("감사 기록은 끝난 결과만 남깁니다(RUNNING 불가).");
        }
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType과 targetId는 함께 채웁니다.");
        }
        if (eventKind == EventKind.ACTION && actionDefinitionId == null) {
            throw new IllegalArgumentException("ACTION 기록에는 actionDefinitionId가 필요합니다.");
        }
        if (status == ExecutionStatus.BLOCKED
                && (eventKind != EventKind.ACTION
                        || blockedReason == null
                        || blockedReason.isBlank())) {
            throw new IllegalArgumentException("BLOCKED는 ACTION에만 쓰고 차단 이유가 필요합니다.");
        }
        input = input == null ? Map.of() : input;
        result = result == null ? Map.of() : result;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    @Builder
    private static AuditEntry create(
            EventKind eventKind,
            Actor actor,
            EntryPoint entryPoint,
            ExecutionStatus status,
            UUID requestId,
            Long actionDefinitionId,
            String checkedPermissionCode,
            String targetType,
            Long targetId,
            Map<String, Object> input,
            Map<String, Object> result,
            String blockedReason,
            Instant requestedAt,
            AgentResultCategory resultCategory,
            Instant dataAsOf,
            Long agentMessageId,
            String toolCode,
            Integer sequenceNo,
            List<ChangeEntry> changes) {
        return new AuditEntry(
                eventKind,
                actor,
                entryPoint,
                status,
                requestId,
                actionDefinitionId,
                checkedPermissionCode,
                targetType,
                targetId,
                input,
                result,
                blockedReason,
                requestedAt,
                resultCategory,
                dataAsOf,
                agentMessageId,
                toolCode,
                sequenceNo,
                changes);
    }
}

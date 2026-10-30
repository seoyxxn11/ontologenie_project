package com.axcore.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 로그 한 줄 (FN-LOG-01). 모든 도메인의 주요 동작이 이 표 하나에 남는다. 한 번 남긴 줄은 고치지 않는다. 직접 만들지 말고 {@code
 * AuditRecorder}로 남긴다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID requestId;

    @Enumerated(EnumType.STRING)
    private EventKind eventKind;

    private Long actionDefinitionId;

    @Enumerated(EnumType.STRING)
    private ActorType actorType;

    private Long actorId;

    private String actorRoleCode;

    private String checkedPermissionCode;

    @Enumerated(EnumType.STRING)
    private EntryPoint entryPoint;

    private String targetType;

    private Long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> input;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus status;

    @Enumerated(EnumType.STRING)
    private AgentResultCategory resultCategory;

    @Column(columnDefinition = "text")
    private String blockedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    private Instant dataAsOf;

    private Long agentMessageId;

    private String toolCode;

    private Integer sequenceNo;

    private Integer durationMs;

    private Instant requestedAt;

    private Instant finishedAt;

    /** 비밀값을 뺀 입력·결과와 끝난 시각으로 만든다. 요청 시각이 없으면 끝난 시각과 같다. */
    public static ActionExecution of(
            AuditEntry entry,
            Map<String, Object> input,
            Map<String, Object> result,
            Instant finishedAt) {
        ActionExecution execution = new ActionExecution();
        execution.requestId = entry.requestId() == null ? UUID.randomUUID() : entry.requestId();
        execution.eventKind = entry.eventKind();
        execution.actionDefinitionId = entry.actionDefinitionId();
        execution.actorType = entry.actor().type();
        execution.actorId = entry.actor().accountId();
        execution.actorRoleCode = entry.actor().roleCode();
        execution.checkedPermissionCode = entry.checkedPermissionCode();
        execution.entryPoint = entry.entryPoint();
        execution.targetType = entry.targetType();
        execution.targetId = entry.targetId();
        execution.input = input;
        execution.status = entry.status();
        execution.resultCategory = entry.resultCategory();
        execution.blockedReason = entry.blockedReason();
        execution.result = result;
        execution.dataAsOf = entry.dataAsOf();
        execution.agentMessageId = entry.agentMessageId();
        execution.toolCode = entry.toolCode();
        execution.sequenceNo = entry.sequenceNo();
        execution.requestedAt = entry.requestedAt() == null ? finishedAt : entry.requestedAt();
        execution.finishedAt = finishedAt;
        if (entry.requestedAt() != null) {
            execution.durationMs =
                    (int) Math.max(0, Duration.between(entry.requestedAt(), finishedAt).toMillis());
        }
        return execution;
    }
}

package com.axcore.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 감사 기록 한 줄이 바꾼 행의 전후 내용 (FN-LOG-03). {@code AuditRecorder}가 함께 남긴다. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntityChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actionExecutionId;

    private String tableName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> rowKey;

    @Enumerated(EnumType.STRING)
    private ChangeKind changeKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> afterData;

    /** 비밀값을 뺀 전후 내용으로 만든다. */
    public static EntityChange of(
            Long actionExecutionId,
            ChangeEntry entry,
            Map<String, Object> before,
            Map<String, Object> after) {
        EntityChange change = new EntityChange();
        change.actionExecutionId = actionExecutionId;
        change.tableName = entry.tableName();
        change.rowKey = entry.rowKey();
        change.changeKind = entry.kind();
        change.beforeData = before;
        change.afterData = after;
        return change;
    }
}

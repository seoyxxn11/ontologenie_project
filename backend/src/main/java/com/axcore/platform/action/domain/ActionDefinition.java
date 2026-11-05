package com.axcore.platform.action.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 실행 가능한 기능의 계약 (FN-ACT-01, Action 정의서). 업무 Action(EXECUTE)은 API 088로 등록하고 090으로 고친다. 관리
 * Action(RECORD)은 Flyway 초기 데이터로만 넣고 고치지 않는다. code와 actionMode는 등록한 뒤 바꾸지 않는다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionDefinition extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private ActionTargetKind targetKind;

    /** OBJECT Action이 받는 객체 유형. 비우면 정하지 않은 것이고 입력 객체의 유형은 Handler가 검사한다. SYSTEM이면 늘 null. */
    private Long targetObjectTypeId;

    @Enumerated(EnumType.STRING)
    private ActionMode actionMode;

    /** EXECUTE일 때만 있다. */
    private String handlerKey;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> inputSchema;

    @Enumerated(EnumType.STRING)
    private ActionKind actionKind;

    private boolean requiresConfirmation;

    private String requiredPermission;

    private boolean isActive;

    @Version private int rowVersion;

    private ActionDefinition(
            String code,
            String name,
            String description,
            ActionTargetKind targetKind,
            Long targetObjectTypeId,
            String handlerKey,
            Map<String, Object> inputSchema,
            ActionKind actionKind,
            boolean requiresConfirmation,
            String requiredPermission,
            boolean isActive) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.targetKind = targetKind;
        this.targetObjectTypeId = targetObjectTypeId;
        this.actionMode = ActionMode.EXECUTE;
        this.handlerKey = handlerKey;
        this.inputSchema = new LinkedHashMap<>(inputSchema);
        this.actionKind = actionKind;
        this.requiresConfirmation = requiresConfirmation;
        this.requiredPermission = requiredPermission;
        this.isActive = isActive;
    }

    /** API 088. API로는 업무 Action만 등록한다. */
    public static ActionDefinition registerExecutable(
            String code,
            String name,
            String description,
            ActionTargetKind targetKind,
            Long targetObjectTypeId,
            String handlerKey,
            Map<String, Object> inputSchema,
            ActionKind actionKind,
            boolean requiresConfirmation,
            String requiredPermission,
            boolean isActive) {
        return new ActionDefinition(
                code,
                name,
                description,
                targetKind,
                targetObjectTypeId,
                handlerKey,
                inputSchema,
                actionKind,
                requiresConfirmation,
                requiredPermission,
                isActive);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void describe(String description) {
        this.description = description;
    }

    public void changeTarget(ActionTargetKind targetKind, Long targetObjectTypeId) {
        this.targetKind = targetKind;
        this.targetObjectTypeId = targetObjectTypeId;
    }

    public void changeHandler(String handlerKey) {
        this.handlerKey = handlerKey;
    }

    public void changeInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = new LinkedHashMap<>(inputSchema);
    }

    public void changeActionKind(ActionKind actionKind) {
        this.actionKind = actionKind;
    }

    public void changeConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public void changePermission(String requiredPermission) {
        this.requiredPermission = requiredPermission;
    }

    public void changeActive(boolean isActive) {
        this.isActive = isActive;
    }
}

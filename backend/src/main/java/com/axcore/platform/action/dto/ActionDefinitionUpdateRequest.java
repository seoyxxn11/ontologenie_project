package com.axcore.platform.action.dto;

import com.axcore.platform.action.domain.ActionKind;
import com.axcore.platform.action.domain.ActionTargetKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * API 090. 보낸 필드만 바꾼다. targetObjectTypeId는 "보내지 않음"과 "null을 보내 비움"을 구분해야 해서 record 대신 setter가 받은 칸을
 * 기록한다(ObjectTypeUpdateRequest와 같은 방식). code·actionMode는 바꿀 수 없어 보냈는지만 기록하고 Service가 400으로 막는다.
 */
@Getter
public class ActionDefinitionUpdateRequest {

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
    private String name;

    @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
    private String description;

    private ActionTargetKind targetKind;

    @Min(1)
    private Long targetObjectTypeId;

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
    private String handlerKey;

    private Map<String, Object> inputSchema;

    private ActionKind actionKind;

    private Boolean requiresConfirmation;

    @Size(max = 100)
    @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
    private String requiredPermission;

    private Boolean isActive;

    @NotNull @PositiveOrZero private Long rowVersion;

    // getter를 만들면 Jackson이 이 칸도 요청 JSON으로 채울 수 있어 bean 이름이 아닌 has* 메서드로만 읽는다.
    @Getter(AccessLevel.NONE)
    private boolean targetObjectTypeIdSent;

    @Getter(AccessLevel.NONE)
    private boolean codeSent;

    @Getter(AccessLevel.NONE)
    private boolean actionModeSent;

    public boolean hasTargetObjectTypeId() {
        return targetObjectTypeIdSent;
    }

    public boolean hasCode() {
        return codeSent;
    }

    public boolean hasActionMode() {
        return actionModeSent;
    }

    public boolean hasChange() {
        return name != null
                || description != null
                || targetKind != null
                || targetObjectTypeIdSent
                || handlerKey != null
                || inputSchema != null
                || actionKind != null
                || requiresConfirmation != null
                || requiredPermission != null
                || isActive != null;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setTargetKind(ActionTargetKind targetKind) {
        this.targetKind = targetKind;
    }

    public void setTargetObjectTypeId(Long targetObjectTypeId) {
        this.targetObjectTypeId = targetObjectTypeId;
        this.targetObjectTypeIdSent = true;
    }

    public void setHandlerKey(String handlerKey) {
        this.handlerKey = handlerKey;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public void setActionKind(ActionKind actionKind) {
        this.actionKind = actionKind;
    }

    public void setRequiresConfirmation(Boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public void setRequiredPermission(String requiredPermission) {
        this.requiredPermission = requiredPermission;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }

    public void setCode(Object ignored) {
        this.codeSent = true;
    }

    public void setActionMode(Object ignored) {
        this.actionModeSent = true;
    }
}

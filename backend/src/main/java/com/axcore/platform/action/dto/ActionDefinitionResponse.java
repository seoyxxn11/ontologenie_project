package com.axcore.platform.action.dto;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionKind;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.domain.ActionTargetKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * API 087~090의 ActionDefinition. 관리 Action(RECORD)은 handlerKey가 null이다.
 *
 * <p>handlerReady는 저장하지 않는 계산값이다. 서버에 그 handlerKey의 Handler가 있으면 true다. 관리 Action은 Handler가 없어
 * false다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActionDefinitionResponse(
        Long id,
        String code,
        String name,
        String description,
        ActionTargetKind targetKind,
        Long targetObjectTypeId,
        ActionMode actionMode,
        String handlerKey,
        Map<String, Object> inputSchema,
        ActionKind actionKind,
        boolean requiresConfirmation,
        String requiredPermission,
        boolean isActive,
        boolean handlerReady,
        int rowVersion) {

    public static ActionDefinitionResponse from(ActionDefinition definition, boolean handlerReady) {
        return new ActionDefinitionResponse(
                definition.getId(),
                definition.getCode(),
                definition.getName(),
                definition.getDescription(),
                definition.getTargetKind(),
                definition.getTargetObjectTypeId(),
                definition.getActionMode(),
                definition.getHandlerKey(),
                definition.getInputSchema(),
                definition.getActionKind(),
                definition.isRequiresConfirmation(),
                definition.getRequiredPermission(),
                definition.isActive(),
                handlerReady,
                definition.getRowVersion());
    }
}

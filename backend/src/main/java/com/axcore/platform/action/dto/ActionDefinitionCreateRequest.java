package com.axcore.platform.action.dto;

import com.axcore.platform.action.domain.ActionKind;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.domain.ActionTargetKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * API 088. API로는 업무 Action(actionMode=EXECUTE)만 등록한다. actionMode는 생략하거나 EXECUTE만 보낼 수 있다. isActive를
 * 생략하면 true다. code는 089·090의 경로로 쓰이므로 객체 유형 코드와 같은 형식만 받는다(경로에 쓸 수 없는 문자·공백을 막는다).
 */
public record ActionDefinitionCreateRequest(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "영문 대문자로 시작하고 대문자·숫자·밑줄만 쓸 수 있습니다.")
                String code,
        @NotBlank @Size(max = 200) String name,
        @NotBlank String description,
        @NotNull ActionTargetKind targetKind,
        @Min(1) Long targetObjectTypeId,
        ActionMode actionMode,
        @NotBlank @Size(max = 200) String handlerKey,
        @NotNull Map<String, Object> inputSchema,
        @NotNull ActionKind actionKind,
        @NotNull Boolean requiresConfirmation,
        @NotBlank @Size(max = 100) String requiredPermission,
        Boolean isActive) {}

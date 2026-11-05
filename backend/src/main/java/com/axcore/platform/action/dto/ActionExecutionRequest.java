package com.axcore.platform.action.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * API 091. 같은 requestId로 다시 보내면 처음 결과를 돌려준다. targetType·targetId는 함께 보내거나 함께 생략한다. agentMessageId는
 * 화면에서 보내지 않는다(Agent는 서버 안에서 실행 서비스를 직접 부른다, 2026-11-04 결정).
 */
public record ActionExecutionRequest(
        @NotNull UUID requestId,
        @Size(max = 50) String targetType,
        @Min(1) Long targetId,
        @NotNull Map<String, Object> input,
        Boolean confirmed,
        Long agentMessageId) {}

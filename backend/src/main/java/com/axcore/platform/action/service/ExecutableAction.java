package com.axcore.platform.action.service;

import com.axcore.platform.action.domain.ActionDefinition;
import java.util.Map;

/**
 * 사용자가 지금 실행할 수 있는 업무 Action 한 건. Agent가 Action Tool을 만들 때 쓰는 값만 담는다(Action 정의서 7절, Jira 303).
 *
 * <p>requiresConfirmation이 true이면 Agent는 실행 전에 사용자 확인을 받고 confirmed=true로 실행해야 한다.
 */
public record ExecutableAction(
        String code,
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean requiresConfirmation) {

    static ExecutableAction from(ActionDefinition definition) {
        return new ExecutableAction(
                definition.getCode(),
                definition.getName(),
                definition.getDescription(),
                definition.getInputSchema(),
                definition.isRequiresConfirmation());
    }
}

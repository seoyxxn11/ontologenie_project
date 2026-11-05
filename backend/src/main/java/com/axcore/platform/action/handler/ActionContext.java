package com.axcore.platform.action.handler;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;

/** Handler에 넘기는 실행 요청. input은 inputSchema 검사를 통과한 값이다. agentMessageId는 Agent 경유일 때만 있다. */
public record ActionContext(
        ActionDefinition definition,
        LoginAccount actor,
        EntryPoint entryPoint,
        Map<String, Object> input,
        Long agentMessageId) {}

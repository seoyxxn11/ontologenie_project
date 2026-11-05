package com.axcore.platform.action.service;

import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;
import java.util.UUID;

/**
 * Action 실행 요청 한 건. 화면(API 091)은 WEB, Agent는 AGENT와 agentMessageId(질문 메시지 ID, Action 정의서 7절)로 만든다.
 * 진입 경로는 서버가 정하고 요청 본문으로 받지 않는다.
 */
public record ActionExecutionCommand(
        LoginAccount actor,
        String code,
        UUID requestId,
        String targetType,
        Long targetId,
        Map<String, Object> input,
        boolean confirmed,
        EntryPoint entryPoint,
        Long agentMessageId) {}

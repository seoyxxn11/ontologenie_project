package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.MessageSender;
import java.util.Map;

/** 대화 메시지에서 권한 범위 판단에 필요한 것만(S15P31C106-413). 답변 본문·조회 결과를 읽지 않으려고 따로 둔다. */
public record MessageScopeMark(Long id, MessageSender sender, Map<String, Object> context) {}

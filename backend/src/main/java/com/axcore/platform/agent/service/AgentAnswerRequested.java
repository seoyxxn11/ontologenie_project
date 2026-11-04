package com.axcore.platform.agent.service;

/** 085에서 질문을 접수했다. 커밋 뒤에 이 답변 메시지를 처리한다. */
public record AgentAnswerRequested(long answerId) {}

package com.axcore.platform.agent.dto;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import com.axcore.platform.agent.service.AgentAccessScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentMessageResponse(
        long id,
        long conversationId,
        MessageSender sender,
        String content,
        Map<String, Object> context,
        MessageStatus status,
        String errorMessage,
        Map<String, Object> answerMeta,
        Instant createdAt) {

    /**
     * 범위가 바뀌어 다시 보여 주지 않는 답변(413). 문장을 안내로 바꾸고 조회 결과·조건·Action 같은 사본은 비운다. {@code accessChanged}로
     * 화면이 구별할 수 있게 한다.
     */
    public static AgentMessageResponse withdrawn(AgentMessage message, String content) {
        return new AgentMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSender(),
                content,
                publicContext(message.getContext()),
                message.getStatus(),
                null,
                Map.of(
                        "syncStatus",
                        "NONE",
                        "results",
                        java.util.List.of(),
                        "actions",
                        java.util.List.of(),
                        "accessChanged",
                        true),
                message.getCreatedAt());
    }

    public static AgentMessageResponse from(AgentMessage message) {
        return new AgentMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSender(),
                message.getContent(),
                publicContext(message.getContext()),
                message.getStatus(),
                message.getErrorMessage(),
                message.getAnswerMeta(),
                message.getCreatedAt());
    }

    /** 질문 context에서 서버만 쓰는 열람 범위(413, 볼 수 있는 유형·민감 속성 목록)를 뺀다. 화면은 쓰지 않는다. */
    private static Map<String, Object> publicContext(Map<String, Object> context) {
        if (context == null || !context.containsKey(AgentAccessScope.CONTEXT_KEY)) {
            return context;
        }
        Map<String, Object> copy = new LinkedHashMap<>(context);
        copy.remove(AgentAccessScope.CONTEXT_KEY);
        return copy;
    }
}

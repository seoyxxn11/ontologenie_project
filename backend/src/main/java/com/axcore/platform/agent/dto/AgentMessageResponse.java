package com.axcore.platform.agent.dto;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import java.time.Instant;
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

    public static AgentMessageResponse from(AgentMessage message) {
        return new AgentMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSender(),
                message.getContent(),
                message.getContext(),
                message.getStatus(),
                message.getErrorMessage(),
                message.getAnswerMeta(),
                message.getCreatedAt());
    }
}

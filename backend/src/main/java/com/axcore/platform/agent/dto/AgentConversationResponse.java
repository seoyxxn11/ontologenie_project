package com.axcore.platform.agent.dto;

import com.axcore.platform.agent.domain.AgentConversation;
import java.time.Instant;

public record AgentConversationResponse(
        long id, String title, Instant startedAt, Instant lastMessageAt) {

    public static AgentConversationResponse from(AgentConversation conversation) {
        return new AgentConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getStartedAt(),
                conversation.getLastMessageAt());
    }
}

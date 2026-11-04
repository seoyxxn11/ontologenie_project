package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    Page<AgentMessage> findByConversationId(Long conversationId, Pageable pageable);

    boolean existsByConversationIdAndStatus(Long conversationId, MessageStatus status);

    List<AgentMessage> findByConversationIdAndStatusAndCreatedAtBefore(
            Long conversationId, MessageStatus status, Instant before);
}

package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.AgentConversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    Page<AgentConversation> findByAccountId(Long accountId, Pageable pageable);

    Optional<AgentConversation> findByIdAndAccountId(Long id, Long accountId);
}

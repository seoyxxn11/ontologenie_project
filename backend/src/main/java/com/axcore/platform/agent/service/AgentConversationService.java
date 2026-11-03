package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.dto.AgentConversationResponse;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentConversationService {
    private final AgentConversationRepository repository;
    private final Clock clock;

    @Transactional
    public AgentConversationResponse create(long accountId) {
        // DB(timestamptz)는 마이크로초까지 저장하므로 응답과 다시 읽은 값이 같도록 맞춘다.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return AgentConversationResponse.from(
                repository.save(AgentConversation.start(accountId, now)));
    }

    public Page<AgentConversationResponse> search(long accountId, Pageable pageable) {
        return repository.findByAccountId(accountId, pageable).map(AgentConversationResponse::from);
    }

    /** 다른 사용자의 대화는 없는 대화와 같게 404로 답한다(존재 여부를 알리지 않는다). */
    public AgentConversationResponse get(long accountId, long id) {
        return repository
                .findByIdAndAccountId(id, accountId)
                .map(AgentConversationResponse::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "agent_conversation")));
    }
}

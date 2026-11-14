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
    private final AgentMessageService messageService;
    private final Clock clock;

    @Transactional
    public AgentConversationResponse create(long accountId) {
        // DB(timestamptz)는 마이크로초까지 저장하므로 응답과 다시 읽은 값이 같도록 맞춘다.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return AgentConversationResponse.from(
                repository.save(AgentConversation.start(accountId, now)));
    }

    public Page<AgentConversationResponse> search(long accountId, Pageable pageable) {
        return repository
                .findByAccountIdAndDeletedAtIsNull(accountId, pageable)
                .map(AgentConversationResponse::from);
    }

    /**
     * 106. 내 대화를 지운다. 감사 기록이 메시지를 가리킬 수 있어 행은 남기고 지운 시각만 적는다. 없는 대화, 남의 대화, 이미 지운 대화는 404, 처리 중인
     * 답변이 있으면 409로 답한다.
     */
    @Transactional
    public void delete(long accountId, long id) {
        ownedConversation(accountId, id);
        messageService.rejectIfAnswering(id);
        if (repository.markDeleted(id, accountId, Instant.now(clock)) == 0) {
            throw notFound();
        }
    }

    /** 다른 사용자의 대화와 지운 대화는 없는 대화와 같게 404로 답한다(존재 여부를 알리지 않는다). */
    public AgentConversationResponse get(long accountId, long id) {
        return AgentConversationResponse.from(ownedConversation(accountId, id));
    }

    private AgentConversation ownedConversation(long accountId, long id) {
        return repository
                .findByIdAndAccountIdAndDeletedAtIsNull(id, accountId)
                .orElseThrow(AgentConversationService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "agent_conversation"));
    }
}

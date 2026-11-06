package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageStatus;
import com.axcore.platform.agent.dto.AgentMessageResponse;
import com.axcore.platform.agent.dto.QuestionRequest;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentMessageService {
    /** 이 시간보다 오래 처리 중인 답변은 서버가 멈춘 것으로 보고 실패로 정리한다(정책 문서 3.4). */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    static final String STALE_CONTENT = "답변을 끝내지 못했습니다. 다시 질문해 주세요.";
    static final String STALE_REASON = "처리 시간 초과";

    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** 084. 시간순으로 돌려준다. 그 전에 멈춘 답변을 실패로 정리한다. */
    @Transactional
    public Page<AgentMessageResponse> search(
            long accountId, long conversationId, Pageable pageable) {
        ownedConversation(accountId, conversationId);
        failStaleAnswers(conversationId);
        return messages.findByConversationId(conversationId, pageable)
                .map(AgentMessageResponse::from);
    }

    /** 085. 질문과 처리 중 답변을 저장해 돌려준다. 답변 처리는 커밋 뒤에 이어서 한다. */
    @Transactional
    public List<AgentMessageResponse> ask(
            long accountId, long conversationId, QuestionRequest request) {
        AgentConversation conversation = ownedConversation(accountId, conversationId);
        failStaleAnswers(conversationId);
        if (messages.existsByConversationIdAndStatus(conversationId, MessageStatus.RUNNING)) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT, "이 대화에서 처리 중인 답변이 있습니다.", Map.of());
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        AgentMessage question =
                messages.save(
                        AgentMessage.question(
                                conversationId, request.content(), request.messageContext(), now));
        AgentMessage answer = messages.save(AgentMessage.runningAnswer(conversationId, now));
        conversation.recordMessage(request.content(), now);
        events.publishEvent(
                new AgentAnswerRequested(
                        answer.getId(), request.content(), request.messageContext()));
        return List.of(AgentMessageResponse.from(question), AgentMessageResponse.from(answer));
    }

    private AgentConversation ownedConversation(long accountId, long conversationId) {
        return conversations
                .findByIdAndAccountId(conversationId, accountId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "agent_conversation")));
    }

    private void failStaleAnswers(long conversationId) {
        Instant limit = Instant.now(clock).minus(STALE_AFTER);
        messages.findByConversationIdAndStatusAndCreatedAtBefore(
                        conversationId, MessageStatus.RUNNING, limit)
                .forEach(answer -> answer.fail(STALE_CONTENT, STALE_REASON));
    }
}

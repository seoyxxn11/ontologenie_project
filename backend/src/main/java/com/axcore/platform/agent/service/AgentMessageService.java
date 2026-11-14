package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import com.axcore.platform.agent.dto.AgentMessageResponse;
import com.axcore.platform.agent.dto.QuestionRequest;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /** 되묻기를 찾을 때 읽는 최근 메시지 수(이번 질문 저장 전). 질문·답변이 번갈아 있어 업무 외 안내를 네 번까지 건너뛴다(361). */
    private static final int RECENT_MESSAGES = 10;

    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final ApplicationEventPublisher events;
    private final RecentTurns recentTurns;
    private final Clock clock;
    private final AgentAccessScope accessScope;

    /**
     * 084. 시간순으로 돌려준다. 그 전에 멈춘 답변을 실패로 정리한다. 질문할 때보다 지금 열람 범위가 줄어든 답변은 문장과 조회 결과를 다시 보여 주지
     * 않는다(413). 질문은 사용자가 쓴 문장이라 그대로 둔다.
     */
    @Transactional
    public Page<AgentMessageResponse> search(
            LoginAccount viewer, long conversationId, Pageable pageable) {
        ownedConversation(viewer.id(), conversationId);
        failStaleAnswers(conversationId);
        Set<Long> withdrawn =
                AgentAccessScope.withdrawnAnswers(
                        messages.findScopeMarks(conversationId), accessScope.current(viewer));
        return messages.findByConversationId(conversationId, pageable)
                .map(
                        message ->
                                withdrawn.contains(message.getId())
                                        ? AgentMessageResponse.withdrawn(
                                                message, AgentAccessScope.WITHDRAWN_CONTENT)
                                        : AgentMessageResponse.from(message));
    }

    /** 085. 질문과 처리 중 답변을 저장해 돌려준다. 답변 처리는 커밋 뒤에 질문한 사용자의 권한으로 이어서 한다. */
    @Transactional
    public List<AgentMessageResponse> ask(
            LoginAccount asker, long conversationId, QuestionRequest request) {
        AgentConversation conversation = ownedConversation(asker.id(), conversationId);
        rejectIfAnswering(conversationId);
        // 질문할 때보다 지금 범위가 모델 기준으로 줄어든 답변은 모델 문맥에 다시 넣지 않는다(413, 426). 화면에서 거두는 기준(084)과 달리
        // 민감 값을 볼 수 있는 사용자도 민감 속성이 늘었으면 뺀다. 민감 값은 누구의 질문이든 모델에 보내지 않기 때문이다.
        Map<String, Object> scope = accessScope.current(asker);
        Set<Long> withdrawn =
                AgentAccessScope.withheldFromModel(messages.findScopeMarks(conversationId), scope);
        PendingClarification pending = pendingClarification(conversationId, withdrawn);
        QuestionInterpretation previous = previousConditions(conversationId, withdrawn);
        List<ConversationTurn> turns = recentTurns(conversationId, withdrawn);
        String summary = usableSummary(conversation, scope);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        AgentMessage question =
                messages.save(
                        AgentMessage.question(
                                conversationId,
                                request.content(),
                                AgentAccessScope.stamped(request.messageContext(), scope),
                                now));
        AgentMessage answer = messages.save(AgentMessage.runningAnswer(conversationId, now));
        conversation.recordMessage(request.content(), now);
        events.publishEvent(
                new AgentAnswerRequested(
                        answer.getId(),
                        question.getId(),
                        asker,
                        request.content(),
                        request.messageContext(),
                        pending,
                        previous,
                        turns,
                        // 앞 답변 뒤에 저장한 오래된 대화 요약(333). 그 답변 직후 요약을 만드는 중이면 이전 요약이다.
                        summary));
        return List.of(AgentMessageResponse.from(question), AgentMessageResponse.from(answer));
    }

    /**
     * 이번 질문 앞의 최근 대화 원문(147). 이 대화의 메시지만 읽으므로 다른 사용자·다른 대화 내용은 섞이지 않는다. 이번 질문을 저장하기 전에 읽어 이번 질문은
     * 들어가지 않는다. 설정으로 껐으면 읽지 않는다. 턴마다 대화 전체 기준 순번을 붙여 오래된 대화 요약과 같은 기준으로 질문 순서를 세게 한다(333).
     */
    private List<ConversationTurn> recentTurns(long conversationId, Set<Long> withdrawn) {
        if (!recentTurns.enabled()) {
            return List.of();
        }
        List<AgentMessage> newestFirst =
                messages.findByConversationIdOrderByIdDesc(
                        conversationId, PageRequest.of(0, recentTurns.messagesToRead()));
        List<AgentMessage> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        oldestFirst = AgentAccessScope.withoutWithdrawnTurns(oldestFirst, withdrawn);
        Long firstQuestionId = recentTurns.firstPickedMessageId(oldestFirst);
        if (firstQuestionId == null) {
            return List.of();
        }
        // 첫 턴까지 센 질문 수가 첫 턴의 순번이다. 첫 턴이 되묻기에 대한 답이면 되물은 질문의 순번과 같다.
        long firstOrder = messages.countQuestionsUpTo(conversationId, firstQuestionId);
        return recentTurns.pick(oldestFirst, (int) firstOrder);
    }

    /**
     * 마지막 답변이 되묻기였으면 이번 질문을 그 답으로 본다(143). 아니면 null. 업무 외·사용 안내 답변은 대화 중 "잠깐 딴 얘기"라 건너뛴다. 그래서 되묻기
     * 뒤에 업무 외 질문을 한 번 끼워도 다음 말을 그 되묻기의 답으로 받는다(361). 최근 답변 몇 건이 모두 업무 외 안내면 되묻기를 이어 가지 않는다.
     */
    private PendingClarification pendingClarification(long conversationId, Set<Long> withdrawn) {
        return messages
                .findByConversationIdOrderByIdDesc(
                        conversationId, PageRequest.of(0, RECENT_MESSAGES))
                .stream()
                .filter(message -> message.getSender() == MessageSender.AGENT)
                .filter(answer -> !QuestionScope.answeredOutsideBusiness(answer.getAnswerMeta()))
                .findFirst()
                // 마지막 답변이 범위가 줄어 다시 쓰지 않는 답변이면 그 앞 되묻기로 돌아가지 않는다(이미 답한 되묻기일 수 있다, 413)
                .filter(last -> !withdrawn.contains(last.getId()))
                .filter(last -> last.getAnswerMeta().containsKey("clarification"))
                .map(
                        last ->
                                PendingClarification.fromAnswer(
                                        last.getContent(), last.getAnswerMeta()))
                .orElse(null);
    }

    /**
     * 마지막으로 끝까지 답한 답변에서 확정한 조건(147). 후속 질문은 여기서 대상·기간·목적을 이어 쓴다. 실패한 답변과 되묻기 답변은 조건을 다 확정하지 못했으므로
     * 건너뛴다. 최근 답변 몇 건 안에 없으면 null.
     */
    private QuestionInterpretation previousConditions(long conversationId, Set<Long> withdrawn) {
        return messages
                .findTop10ByConversationIdAndSenderAndStatusOrderByIdDesc(
                        conversationId, MessageSender.AGENT, MessageStatus.SUCCEEDED)
                .stream()
                .filter(answer -> !withdrawn.contains(answer.getId()))
                .map(AgentMessage::getAnswerMeta)
                .filter(meta -> meta.containsKey("conditions"))
                .filter(meta -> !meta.containsKey("clarification"))
                .findFirst()
                .map(QuestionInterpretation::fromAnswerMeta)
                .orElse(null);
    }

    /** 요약을 만든 범위보다 지금 범위가 줄었으면 그 요약은 넘기지 않는다(413). 다음 요약을 만들 때 버리고 다시 쓴다. 빈 요약도 넘기지 않는다. */
    private static String usableSummary(AgentConversation conversation, Map<String, Object> scope) {
        String summary = conversation.getSummary();
        if (summary == null
                || summary.isBlank()
                || !AgentAccessScope.summaryUsable(conversation.getSummaryAccessScope(), scope)) {
            return null;
        }
        return summary;
    }

    private AgentConversation ownedConversation(long accountId, long conversationId) {
        return conversations
                .findByIdAndAccountIdAndDeletedAtIsNull(conversationId, accountId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "agent_conversation")));
    }

    /** 085·106. 멈춘 답변을 실패로 정리한 뒤에도 처리 중인 답변이 있으면 409로 거절한다. 호출자가 대화 소유자를 먼저 확인한다. */
    void rejectIfAnswering(long conversationId) {
        failStaleAnswers(conversationId);
        if (messages.existsByConversationIdAndStatus(conversationId, MessageStatus.RUNNING)) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT, "이 대화에서 처리 중인 답변이 있습니다.", Map.of());
        }
    }

    private void failStaleAnswers(long conversationId) {
        Instant limit = Instant.now(clock).minus(STALE_AFTER);
        messages.findByConversationIdAndStatusAndCreatedAtBeforeOrderByIdAsc(
                        conversationId, MessageStatus.RUNNING, limit)
                .forEach(answer -> answer.fail(STALE_CONTENT, STALE_REASON));
    }
}

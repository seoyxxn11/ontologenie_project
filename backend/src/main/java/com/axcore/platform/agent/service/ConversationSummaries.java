package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 오래된 대화 요약(333). 답변이 끝난 뒤, 다음 질문 때 최근 대화 원문({@link RecentTurns})으로 넘기지 않을 턴이 새로 생겼으면 이전 요약에 합쳐 새
 * 요약을 저장한다. 질문을 받을 때는 저장된 요약을 읽어 질문 해석에 넘기기만 해서 답변이 늦어지지 않는다.
 *
 * <ul>
 *   <li>요약 범위는 다음 질문이 원문으로 받을 가장 오래된 턴의 바로 앞까지다. 원문과 요약 사이에 빠지는 턴이 없다. 원문을 껐으면 직전 턴까지 요약한다.
 *   <li>요약 길이는 maxLength 글자 이하다(기본 500). 원문 상한(기본 2000)과 따로 센다. 0이면 요약을 만들지도 넘기지도 않는다.
 *   <li>요약을 만들다 실패하거나 상한을 넘으면 이전 요약을 그대로 두고 답변에는 영향을 주지 않는다.
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationSummaries {
    /** 한 번에 새로 요약할 메시지 수 상한. 요약이 없던 긴 대화도 최근 20턴까지만 요약해 AI 입력이 커지지 않게 한다. */
    static final int MAX_MESSAGES = 40;

    /** 요약에 넘기는 답변 하나의 글자 수 상한. 표·목록이 든 긴 답변도 무엇을 물었는지만 알면 된다. */
    static final int MAX_ANSWER_LENGTH = 500;

    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final RecentTurns recentTurns;
    private final ConversationSummarizer summarizer;
    private final int maxLength;

    public ConversationSummaries(
            AgentConversationRepository conversations,
            AgentMessageRepository messages,
            RecentTurns recentTurns,
            ConversationSummarizer summarizer,
            @Value("${axcore.agent.history.summary-max-tokens:500}") int maxLength) {
        this.conversations = conversations;
        this.messages = messages;
        this.recentTurns = recentTurns;
        this.summarizer = summarizer;
        this.maxLength = Math.max(0, maxLength);
    }

    /** 요약을 만들고 질문 해석에 넘기는가. 상한을 0으로 두면 끈다. */
    public boolean enabled() {
        return maxLength > 0;
    }

    /** 답변(answerId)이 끝난 뒤 부른다. 실패해도 예외를 던지지 않는다. */
    public void refreshAfter(long answerId) {
        if (!enabled() || !summarizer.isConfigured()) {
            return;
        }
        try {
            refresh(answerId);
        } catch (RuntimeException ex) {
            log.warn("답변 {} 뒤 오래된 대화 요약을 만들지 못했습니다. 이전 요약을 그대로 둡니다.", answerId, ex);
        }
    }

    void refresh(long answerId) {
        Long conversationId = messages.findConversationIdById(answerId).orElse(null);
        if (conversationId == null) {
            return;
        }
        AgentConversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        List<AgentMessage> recent =
                oldestFirst(
                        messages.findByConversationIdOrderByIdDesc(
                                conversationId, PageRequest.of(0, recentTurns.messagesToRead())));
        if (recent.isEmpty()) {
            return;
        }
        // 다음 질문은 지금까지의 메시지에서 원문을 고른다. 그 원문의 첫 턴 앞까지가 요약 범위다. 원문으로 넘길 턴이 없으면(원문을 껐으면) 모두
        // 요약한다. 다만 그사이 다음 질문이 들어와 처리 중인 답변이 있으면 그 질문부터는 요약하지 않는다. 그 답변이 끝난 뒤 다음 요약이 다룬다.
        Long firstPicked = recentTurns.firstPickedMessageId(recent);
        long before = firstPicked != null ? firstPicked : recent.getLast().getId() + 1;
        before = Math.min(before, firstUnfinishedQuestionId(recent));
        Long summarizedUntil = conversation.getSummarizedUntilMessageId();
        long after = summarizedUntil == null ? 0 : summarizedUntil;
        List<AgentMessage> read = toSummarize(conversationId, after, before);
        // 요약은 가장 최근 질문의 범위로 만든다. 그 범위보다 넓은 범위에서 만든 답변은 넣지 않고, 이전 요약을 만든 범위보다 줄었으면 이전 요약도
        // 버린다(413). 이 기능 전 질문(범위 없음)이면 예전처럼 모두 합친다.
        Map<String, Object> scope = AgentAccessScope.latestScope(recent);
        String previousSummary = conversation.getSummary();
        List<AgentMessage> added = read;
        if (scope != null) {
            added =
                    AgentAccessScope.withoutWithdrawnTurns(
                            read, AgentAccessScope.withdrawnAnswersIn(read, scope));
            if (!AgentAccessScope.summaryUsable(conversation.getSummaryAccessScope(), scope)) {
                previousSummary = null;
            }
        }
        if (previousSummary != null && previousSummary.isBlank()) {
            previousSummary = null;
        }
        Long firstQuestionId = RecentTurns.firstQuestionId(added);
        if (firstQuestionId == null) {
            if (!read.isEmpty() && added.size() < read.size()) {
                // 읽은 턴이 모두 범위가 줄기 전 것이면 요약은 그대로(없으면 빈 채로) 두고 그 턴을 다시 읽지 않게 범위만 넘긴다.
                // 요약을 그대로 두면 그 요약을 만든 범위도 그대로 둔다.
                conversations.saveSummary(
                        conversationId,
                        after,
                        previousSummary == null ? "" : previousSummary,
                        read.getLast().getId(),
                        previousSummary == null
                                ? AgentAccessScope.toText(scope)
                                : conversation.getSummaryAccessScope());
            }
            return;
        }
        // 요약에 "N번째 질문"으로 남겨, 최근 대화 원문과 같은 기준으로 질문 순서를 세게 한다.
        int firstOrder = (int) messages.countQuestionsUpTo(conversationId, firstQuestionId);
        List<ConversationTurn> turns =
                RecentTurns.turnsOf(added, firstOrder).stream()
                        .map(ConversationSummaries::shortened)
                        .toList();
        String summary = summarized(previousSummary, turns);
        if (summary == null) {
            log.warn("대화 {}의 요약이 비었거나 상한을 넘어 이전 요약을 그대로 둡니다. 다음 답변 뒤에 다시 만듭니다.", conversationId);
            return;
        }
        // 범위가 줄기 전 턴을 뺐어도 읽은 데까지 넘긴다. 그 턴은 다음 요약에서 다시 읽지 않는다.
        long until = read.getLast().getId();
        if (conversations.saveSummary(
                        conversationId, after, summary, until, AgentAccessScope.toText(scope))
                == 0) {
            // 같은 대화의 다른 요약이 먼저 저장했다. 그 요약이 이 범위를 이미 다뤘거나 다음 답변 뒤에 이어서 다룬다.
            log.info("대화 {}의 요약을 다른 처리가 먼저 저장해 이번 요약은 버립니다.", conversationId);
        }
    }

    /**
     * 이번에 요약에 합칠 메시지를 오래된 것부터. 한 번에 MAX_MESSAGES개까지다.
     *
     * <ul>
     *   <li>요약이 없던 대화는 범위 안의 최근 메시지만 읽는다. 기능을 켜기 전부터 길었던 대화의 그 앞부분은 요약에 넣지 않는다.
     *   <li>이전 요약이 있으면 그 뒤부터 오래된 순으로 읽는다. 요약을 저장하지 못해 밀린 메시지가 많아도 빠뜨리지 않고 답변마다 차례로 따라잡는다. 한 번에 다 읽지
     *       못했으면 턴이 둘로 나뉘지 않게 끝에 걸린 질문은 다음으로 넘긴다.
     * </ul>
     */
    private List<AgentMessage> toSummarize(long conversationId, long after, long before) {
        PageRequest page = PageRequest.of(0, MAX_MESSAGES);
        if (after == 0) {
            return oldestFirst(
                    messages.findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(
                            conversationId, after, before, page));
        }
        List<AgentMessage> added =
                new ArrayList<>(
                        messages.findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
                                conversationId, after, before, page));
        if (added.size() == MAX_MESSAGES && added.getLast().getSender() == MessageSender.USER) {
            added.removeLast();
        }
        return added;
    }

    /**
     * 상한의 80%를 목표로 요약을 만든다. AI는 글자 수 지시를 조금 넘기기 쉬워 여유를 둔다. 그래도 상한을 넘으면 한 번 더 줄여 달라고 하고, 또 넘으면 저장하지
     * 않는다(null). 잘라서 저장하면 방금 합친 턴이 빠진 채 요약한 것으로 표시돼 다시 요약되지 않기 때문이다.
     */
    private String summarized(String previousSummary, List<ConversationTurn> turns) {
        int target = Math.max(1, maxLength * 4 / 5);
        String summary = stripped(summarizer.summarize(previousSummary, turns, target));
        if (summary != null && length(summary) > maxLength) {
            summary = stripped(summarizer.shorten(summary, target));
        }
        return summary == null || length(summary) > maxLength ? null : summary;
    }

    /** 처리 중인 답변이 붙은 질문 중 가장 앞의 ID. 없으면 Long.MAX_VALUE. */
    private static long firstUnfinishedQuestionId(List<AgentMessage> oldestFirst) {
        Long question = null;
        for (AgentMessage message : oldestFirst) {
            if (message.getSender() == MessageSender.USER) {
                question = message.getId();
            } else if (message.getStatus() == MessageStatus.RUNNING) {
                return question != null ? question : message.getId();
            }
        }
        return Long.MAX_VALUE;
    }

    private static String stripped(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static List<AgentMessage> oldestFirst(List<AgentMessage> newestFirst) {
        List<AgentMessage> reversed = new ArrayList<>(newestFirst);
        Collections.reverse(reversed);
        return reversed;
    }

    private static ConversationTurn shortened(ConversationTurn turn) {
        return new ConversationTurn(
                turn.question(), limit(turn.answer(), MAX_ANSWER_LENGTH), turn.order());
    }

    /** 요약에 넘기는 답변을 앞에서 maxLength 글자까지 남기고 생략 표시를 붙인다. 비었으면 null. */
    static String limit(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.codePointCount(0, stripped.length()) <= maxLength) {
            return stripped;
        }
        int keep = Math.max(0, maxLength - RecentTurns.CUT_MARK.length());
        return stripped.substring(0, stripped.offsetByCodePoints(0, keep)) + RecentTurns.CUT_MARK;
    }
}

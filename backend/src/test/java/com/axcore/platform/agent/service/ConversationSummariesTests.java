package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 오래된 대화 요약을 언제·어디까지 만들고 어떻게 저장하는지 본다(333). 메시지 ID는 11부터 질문·답변 순서로 매긴다. 기본은 질문 6턴(메시지 11~22)이고, 상한
 * 500이면 AI에게는 400자를 목표로 준다.
 */
class ConversationSummariesTests {
    private static final long CONVERSATION = 5L;
    private static final long ANSWER = 22L;
    private static final int TARGET = 400;
    private static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");

    private final AgentConversationRepository conversations =
            mock(AgentConversationRepository.class);
    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private final List<AgentMessage> conversationMessages = new ArrayList<>();
    private AgentConversation conversation;

    @BeforeEach
    void sixAnsweredTurns() {
        answeredTurns(1, 6);
        conversation = AgentConversation.start(7L, NOW);
        when(messages.findConversationIdById(anyLong())).thenReturn(Optional.of(CONVERSATION));
        when(conversations.findById(CONVERSATION)).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByIdDesc(eq(CONVERSATION), any()))
                .thenAnswer(invocation -> newest(Long.MAX_VALUE, invocation.getArgument(1)));
        when(messages.findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(
                        eq(CONVERSATION), anyLong(), anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            long after = invocation.getArgument(1);
                            long before = invocation.getArgument(2);
                            return newest(before, invocation.getArgument(3)).stream()
                                    .filter(message -> message.getId() > after)
                                    .toList();
                        });
        when(messages.findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
                        eq(CONVERSATION), anyLong(), anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            long after = invocation.getArgument(1);
                            long before = invocation.getArgument(2);
                            Pageable page = invocation.getArgument(3);
                            return conversationMessages.stream()
                                    .filter(m -> m.getId() > after && m.getId() < before)
                                    .limit(page.getPageSize())
                                    .toList();
                        });
        // 저장소의 native 질의와 같은 기준: id까지의 사용자 말 중 되묻기 바로 다음 말은 세지 않는다.
        when(messages.countQuestionsUpTo(eq(CONVERSATION), anyLong()))
                .thenAnswer(
                        invocation -> {
                            long id = invocation.getArgument(1);
                            long count = 0;
                            AgentMessage previous = null;
                            for (AgentMessage m : conversationMessages) {
                                if (m.getId() <= id
                                        && m.getSender() == MessageSender.USER
                                        && !(previous != null
                                                && previous.getAnswerMeta()
                                                        .containsKey("clarification"))) {
                                    count++;
                                }
                                previous = m;
                            }
                            return count;
                        });
        when(summarizer.isConfigured()).thenReturn(true);
        when(conversations.saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(1);
    }

    @Test
    void summarizesTurnsThatTheNextQuestionWillNotSeeAsRawTurns() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1·2에서 물은 것");

        summaries(500).refreshAfter(ANSWER);

        // 다음 질문은 질문 3~6을 원문으로 받으므로 질문 1·2(메시지 11~14)만 요약한다.
        verify(summarizer)
                .summarize(
                        null,
                        List.of(
                                new ConversationTurn("질문 1", "답변 1", 1),
                                new ConversationTurn("질문 2", "답변 2", 2)),
                        TARGET);
        verify(conversations).saveSummary(CONVERSATION, 0L, "질문 1·2에서 물은 것", 14L, null);
    }

    @Test
    void mergesOnlyNewlyPushedOutTurnsIntoPreviousSummary() {
        summarizedUntil("질문 1에서 물은 것", 12L);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1·2에서 물은 것");

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer)
                .summarize("질문 1에서 물은 것", List.of(new ConversationTurn("질문 2", "답변 2", 2)), TARGET);
        verify(conversations).saveSummary(CONVERSATION, 12L, "질문 1·2에서 물은 것", 14L, null);
    }

    @Test
    void followsBoundaryOfRawTurnsCutByTokenLimit() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1~4에서 물은 것");

        // 원문 상한에 걸려 2턴(질문 5·6)만 원문이면 질문 1~4까지 요약해 빠지는 턴이 없다.
        new ConversationSummaries(
                        conversations,
                        messages,
                        new RecentTurns(4, 2 * (RecentTurns.TURN_OVERHEAD + 8)),
                        summarizer,
                        500)
                .refreshAfter(ANSWER);

        verify(conversations).saveSummary(CONVERSATION, 0L, "질문 1~4에서 물은 것", 18L, null);
    }

    @Test
    void summarizesUpToLastTurnWhenRawTurnsAreTurnedOff() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1~6에서 물은 것");

        // 원문을 꺼도 요약은 자기 설정으로 켜진다. 원문으로 넘길 턴이 없으므로 직전 턴까지 요약한다.
        new ConversationSummaries(
                        conversations, messages, new RecentTurns(0, 2000), summarizer, 500)
                .refreshAfter(ANSWER);

        verify(conversations).saveSummary(CONVERSATION, 0L, "질문 1~6에서 물은 것", 22L, null);
    }

    @Test
    void stopsBeforeQuestionWhoseAnswerIsStillRunning() {
        // 요약하는 사이 다음 질문(메시지 23)이 들어와 답변(24)을 처리 중이다.
        AgentMessage next = AgentMessage.question(CONVERSATION, "질문 7", Map.of(), NOW);
        AgentMessage running = AgentMessage.runningAnswer(CONVERSATION, NOW);
        add(next);
        add(running);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1~6에서 물은 것");

        new ConversationSummaries(
                        conversations, messages, new RecentTurns(0, 2000), summarizer, 500)
                .refreshAfter(ANSWER);

        // 처리 중인 답변의 질문은 요약하지 않는다. 그 답변이 끝난 뒤 다음 요약이 다룬다.
        verify(conversations).saveSummary(CONVERSATION, 0L, "질문 1~6에서 물은 것", 22L, null);
    }

    @Test
    void catchesUpFromOldestBacklogWhenEarlierSummariesWereNotSaved() {
        conversationMessages.clear();
        answeredTurns(1, 30);
        // 질문 1까지 요약한 뒤 저장하지 못한 일이 이어져 질문 2~26이 밀렸다. 원문은 질문 27~30이다.
        summarizedUntil("질문 1에서 물은 것", 12L);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1~21에서 물은 것");

        summaries(500).refreshAfter(70L);

        // 최근 것이 아니라 밀린 것 중 오래된 20턴(질문 2~21)부터 합쳐 빠지는 턴이 없다. 나머지는 다음 답변 뒤에 이어서 합친다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(summarizer).summarize(eq("질문 1에서 물은 것"), turns.capture(), anyInt());
        assertThat(turns.getValue()).hasSize(ConversationSummaries.MAX_MESSAGES / 2);
        assertThat(turns.getValue().getFirst().question()).isEqualTo("질문 2");
        assertThat(turns.getValue().getLast().question()).isEqualTo("질문 21");
        // 요약에 남길 순번은 대화 전체 기준이다(질문 2는 2번째 질문).
        assertThat(turns.getValue()).extracting(ConversationTurn::order).startsWith(2).endsWith(21);
        verify(conversations).saveSummary(CONVERSATION, 12L, "질문 1~21에서 물은 것", 52L, null);
    }

    @Test
    void leavesQuestionCutAtEndOfBatchForNextSummary() {
        conversationMessages.clear();
        answeredTurns(1, 30);
        // 요약이 질문 1(메시지 11)에서 끝나 그 답변(12)부터 읽으면, 40개째인 메시지 51은 질문 21이다.
        summarizedUntil("질문 1을 물었다.", 11L);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 1~20을 물었다.");

        summaries(500).refreshAfter(70L);

        // 질문 21은 답변과 함께 다음 요약에서 합치도록 남긴다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(summarizer).summarize(any(), turns.capture(), anyInt());
        assertThat(turns.getValue().getLast())
                .isEqualTo(new ConversationTurn("질문 20", "답변 20", 20));
        verify(conversations).saveSummary(CONVERSATION, 11L, "질문 1~20을 물었다.", 50L, null);
    }

    @Test
    void summarizesAtMostRecentTwentyTurnsAtOnce() {
        conversationMessages.clear();
        answeredTurns(1, 30);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("요약");

        summaries(500).refreshAfter(70L);

        // 원문은 질문 27~30이다. 그 앞 26턴 중 최근 20턴(질문 7~26)만 요약한다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(summarizer).summarize(any(), turns.capture(), anyInt());
        assertThat(turns.getValue()).hasSize(ConversationSummaries.MAX_MESSAGES / 2);
        assertThat(turns.getValue().getFirst().question()).isEqualTo("질문 7");
        assertThat(turns.getValue().getLast().question()).isEqualTo("질문 26");
    }

    @Test
    void doesNothingWhenNoTurnWasPushedOut() {
        summarizedUntil("질문 1·2에서 물은 것", 14L);

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer, never()).summarize(any(), any(), anyInt());
        verify(conversations, never())
                .saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void asksToShortenOnceWhenSummaryExceedsLimit() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("가".repeat(510));
        when(summarizer.shorten("가".repeat(510), TARGET)).thenReturn("가".repeat(390));

        summaries(500).refreshAfter(ANSWER);

        verify(conversations).saveSummary(CONVERSATION, 0L, "가".repeat(390), 14L, null);
    }

    @Test
    void keepsPreviousSummaryWhenItStillExceedsLimitInsteadOfCuttingNewTurns() {
        summarizedUntil("질문 1에서 물은 것", 12L);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("가".repeat(510));
        when(summarizer.shorten(anyString(), anyInt())).thenReturn("가".repeat(505));

        summaries(500).refreshAfter(ANSWER);

        // 잘라서 저장하면 방금 합친 질문 2가 빠진 채 요약한 것으로 표시된다. 저장하지 않으면 다음 답변 뒤에 다시 다룬다.
        verify(conversations, never())
                .saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void savesSummaryWithinLimitWithoutAskingToShorten() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("가".repeat(500));

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer, never()).shorten(anyString(), anyInt());
        verify(conversations).saveSummary(CONVERSATION, 0L, "가".repeat(500), 14L, null);
    }

    @Test
    void keepsPreviousSummaryWhenSummarizingFails() {
        summarizedUntil("질문 1에서 물은 것", 12L);
        when(summarizer.summarize(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("GMS 시간 초과"));

        // 예외를 밖으로 던지지 않아 답변 처리에 영향을 주지 않는다.
        summaries(500).refreshAfter(ANSWER);

        verify(conversations, never())
                .saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void keepsPreviousSummaryWhenAiReturnsBlank() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("  ");

        summaries(500).refreshAfter(ANSWER);

        verify(conversations, never())
                .saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void dropsItsSummaryQuietlyWhenAnotherOneWasSavedFirst() {
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("늦게 끝난 요약");
        when(conversations.saveSummary(anyLong(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(0);

        // 같은 대화의 다른 요약이 먼저 저장했으면 버린다. 예외를 던지지 않는다.
        summaries(500).refreshAfter(ANSWER);

        verify(conversations).saveSummary(CONVERSATION, 0L, "늦게 끝난 요약", 14L, null);
    }

    @Test
    void shortensLongAnswersBeforeSummarizing() {
        ReflectionTestUtils.setField(
                conversationMessages.get(1),
                "content",
                "표".repeat(ConversationSummaries.MAX_ANSWER_LENGTH + 100));
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("요약");

        summaries(500).refreshAfter(ANSWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(summarizer).summarize(any(), turns.capture(), anyInt());
        assertThat(turns.getValue().getFirst().answer())
                .hasSize(ConversationSummaries.MAX_ANSWER_LENGTH)
                .endsWith(RecentTurns.CUT_MARK);
    }

    @Test
    void doesNothingWhenTurnedOffOrModelIsMissing() {
        ConversationSummaries off = summaries(0);
        off.refreshAfter(ANSWER);
        assertThat(off.enabled()).isFalse();

        when(summarizer.isConfigured()).thenReturn(false);
        summaries(500).refreshAfter(ANSWER);

        verify(summarizer, never()).summarize(any(), any(), anyInt());
        verifyNoInteractions(conversations);
    }

    // 413: 유형 1·2가 보이던 범위(WIDE)와, 유형 2가 빠진 지금 범위(NARROW)
    private static final Map<String, Object> WIDE =
            Map.of(
                    "policy",
                    "p",
                    "types",
                    Map.of(
                            "1", Map.of("restrictedView", false, "restricted", List.of()),
                            "2", Map.of("restrictedView", false, "restricted", List.of())));
    private static final Map<String, Object> NARROW =
            Map.of(
                    "policy",
                    "p",
                    "types",
                    Map.of("1", Map.of("restrictedView", false, "restricted", List.of())));

    @Test
    void leavesOutTurnsAskedBeforeTheScopeNarrowed() {
        askedUnder(WIDE, 1);
        askedUnder(NARROW, 2, 3, 4, 5, 6);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 2에서 물은 것");

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer)
                .summarize(null, List.of(new ConversationTurn("질문 2", "답변 2", 2)), TARGET);
        verify(conversations)
                .saveSummary(CONVERSATION, 0L, "질문 2에서 물은 것", 14L, AgentAccessScope.toText(NARROW));
    }

    @Test
    void dropsPreviousSummaryMadeUnderAWiderScope() {
        summarizedUntil("질문 1에서 물은 것", 12L);
        ReflectionTestUtils.setField(
                conversation, "summaryAccessScope", AgentAccessScope.toText(WIDE));
        askedUnder(NARROW, 1, 2, 3, 4, 5, 6);
        when(summarizer.summarize(any(), any(), anyInt())).thenReturn("질문 2에서 물은 것");

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer)
                .summarize(null, List.of(new ConversationTurn("질문 2", "답변 2", 2)), TARGET);
    }

    @Test
    void movesPastTurnsThatWereAllAskedBeforeTheScopeNarrowed() {
        askedUnder(WIDE, 1, 2);
        askedUnder(NARROW, 3, 4, 5, 6);

        summaries(500).refreshAfter(ANSWER);

        // 요약할 턴이 없어도 그 턴을 다시 읽지 않게 범위만 넘긴다
        verify(summarizer, never()).summarize(any(), any(), anyInt());
        verify(conversations)
                .saveSummary(CONVERSATION, 0L, "", 14L, AgentAccessScope.toText(NARROW));
    }

    @Test
    void keepsPreviousSummaryAndItsScopeWhenOnlyWithdrawnTurnsWereRead() {
        // 이전 요약은 유형 1의 민감 속성이 하나 더 있던 때(지금보다 넓지 않은 범위) 만든 것이라 그대로 쓴다
        String madeUnder =
                AgentAccessScope.toText(
                        Map.of(
                                "policy",
                                "p",
                                "types",
                                Map.of(
                                        "1",
                                        Map.of(
                                                "restrictedView",
                                                false,
                                                "restricted",
                                                List.of("technician")))));
        summarizedUntil("질문 1에서 물은 것", 12L);
        ReflectionTestUtils.setField(conversation, "summaryAccessScope", madeUnder);
        askedUnder(NARROW, 1);
        askedUnder(WIDE, 2);
        askedUnder(NARROW, 3, 4, 5, 6);

        summaries(500).refreshAfter(ANSWER);

        verify(summarizer, never()).summarize(any(), any(), anyInt());
        verify(conversations).saveSummary(CONVERSATION, 12L, "질문 1에서 물은 것", 14L, madeUnder);
    }

    /** 질문 번호(1부터)들을 그 범위에서 물은 것으로 표시한다. */
    private void askedUnder(Map<String, Object> scope, int... questions) {
        for (int question : questions) {
            ReflectionTestUtils.setField(
                    conversationMessages.get((question - 1) * 2),
                    "context",
                    Map.of(AgentAccessScope.CONTEXT_KEY, scope));
        }
    }

    private ConversationSummaries summaries(int maxLength) {
        return new ConversationSummaries(
                conversations, messages, new RecentTurns(4, 2000), summarizer, maxLength);
    }

    private void summarizedUntil(String summary, long messageId) {
        ReflectionTestUtils.setField(conversation, "summary", summary);
        ReflectionTestUtils.setField(conversation, "summarizedUntilMessageId", messageId);
    }

    private void answeredTurns(int from, int to) {
        for (int i = from; i <= to; i++) {
            AgentMessage answer = AgentMessage.runningAnswer(CONVERSATION, NOW);
            answer.succeed("답변 " + i, Map.of());
            add(AgentMessage.question(CONVERSATION, "질문 " + i, Map.of(), NOW));
            add(answer);
        }
    }

    private void add(AgentMessage message) {
        ReflectionTestUtils.setField(message, "id", 11L + conversationMessages.size());
        conversationMessages.add(message);
    }

    /** beforeId 앞의 메시지를 최신 순으로 page 크기만큼. */
    private List<AgentMessage> newest(long beforeId, Pageable page) {
        List<AgentMessage> newestFirst = new ArrayList<>(conversationMessages);
        Collections.reverse(newestFirst);
        return newestFirst.stream()
                .filter(message -> message.getId() < beforeId)
                .limit(page.getPageSize())
                .toList();
    }
}

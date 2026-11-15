package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.service.CompletionEstimator.Estimate;
import com.axcore.platform.agent.service.CompletionEstimator.Result;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class AgentCompletionEstimateToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Instant NOW = Instant.parse("2026-10-30T05:00:00Z");

    private final CompletionEstimator estimator = mock(CompletionEstimator.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final DataAsOfResolver dataAsOf = mock(DataAsOfResolver.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.fixed(NOW, ZoneOffset.UTC), dataAsOf);
    private final AgentCompletionEstimateTools tools =
            new AgentCompletionEstimateTools(session, estimator);

    @Test
    void estimatesWithNormalizedInputAndRecordsReadObjects() {
        Result result = result();
        when(estimator.estimate(eq(ASKER), any(), any(), any(), any()))
                .thenReturn(
                        new Estimate(
                                AgentResultCategory.FOUND,
                                result,
                                new LinkedHashSet<>(List.of(141L, 1012L, 5001L))));
        Instant asOf = Instant.parse("2026-10-30T04:50:00Z");
        when(dataAsOf.forObjects(Set.of(141L, 1012L, 5001L))).thenReturn(asOf);

        Map<String, Object> reply =
                tools.estimateCompletion(
                        List.of(141L), List.of("20.0", "30"), null, "2026-10-30T15:00+09:00");

        verify(estimator)
                .estimate(
                        ASKER,
                        List.of(141L),
                        Set.of("20", "30"),
                        null,
                        Instant.parse("2026-10-30T06:00:00Z"));
        assertThat(reply)
                .containsEntry("resultCategory", "FOUND")
                .containsEntry("data", result)
                .containsEntry("dataAsOf", "2026-10-30T13:50+09:00");
        AgentQueryResult recorded = session.results().getFirst();
        assertThat(recorded.toolCode()).isEqualTo(AgentCompletionEstimateTools.ESTIMATE_COMPLETION);
        assertThat(recorded.sources())
                .containsExactly(
                        Map.of("objectId", 141L),
                        Map.of("objectId", 1012L),
                        Map.of("objectId", 5001L));
        assertThat(recorded.estimated()).isFalse();
        // 작업지시가 하나면 그 객체를 기록 대상으로 남긴다.
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(
                                Map.of(
                                        "workOrderIds",
                                        List.of(141L),
                                        "operationSequences",
                                        List.of("20.0", "30"),
                                        "startAt",
                                        "2026-10-30T15:00+09:00")),
                        eq(AgentQuerySession.OBJECT_INSTANCE),
                        eq(141L),
                        any(),
                        eq(NOW));
    }

    @Test
    void startsFromNowAndTakesAllOperationsWhenNotGiven() {
        when(estimator.estimate(any(), any(), any(), any(), any()))
                .thenReturn(new Estimate(AgentResultCategory.FOUND, result(), Set.of(141L)));

        tools.estimateCompletion(List.of(141L), null, null, null);

        verify(estimator).estimate(ASKER, List.of(141L), Set.of(), null, NOW);
    }

    /** 사용자가 오프셋 없이 말한 시각은 한국 시간이다. */
    @Test
    void readsStartWithoutOffsetAsKoreanTime() {
        when(estimator.estimate(any(), any(), any(), any(), any()))
                .thenReturn(new Estimate(AgentResultCategory.FOUND, result(), Set.of(141L)));

        tools.estimateCompletion(List.of(141L), null, null, "2026-10-30T14:00");

        verify(estimator).estimate(ASKER, List.of(141L), Set.of(), null, NOW);
    }

    @Test
    void recordsSeveralWorkOrdersWithoutSingleTarget() {
        when(estimator.estimate(any(), any(), any(), any(), any()))
                .thenReturn(new Estimate(AgentResultCategory.FOUND, result(), Set.of(138L, 141L)));

        tools.estimateCompletion(List.of(138L, 141L), null, null, null);

        verify(estimator).estimate(ASKER, List.of(138L, 141L), Set.of(), null, NOW);
        verify(recorder).record(eq(ASKER), eq(11L), eq(1), any(), isNull(), isNull(), any(), any());
    }

    /** 계산하지 못했는데 읽은 객체가 없으면 출처·기준 시점이 없다. */
    @Test
    void returnsNoDataWithoutSourcesWhenNothingWasRead() {
        Result missing =
                new Result(
                        "2026-10-30T14:00+09:00",
                        null,
                        null,
                        null,
                        List.of(),
                        CompletionEstimator.ASSUMPTIONS,
                        List.of("작업지시(id=404)를 찾을 수 없습니다."),
                        List.of());
        when(estimator.estimate(any(), any(), any(), any(), any()))
                .thenReturn(new Estimate(AgentResultCategory.NO_DATA, missing, Set.of()));

        Map<String, Object> reply = tools.estimateCompletion(List.of(404L), null, null, null);

        assertThat(reply)
                .containsEntry("resultCategory", "NO_DATA")
                .containsEntry("data", missing)
                .containsEntry("dataAsOf", null);
        assertThat(session.results().getFirst().sources()).isEmpty();
        verifyNoInteractions(dataAsOf);
    }

    @Test
    void passesEquipmentToEstimate() {
        when(estimator.estimate(any(), any(), any(), any(), any()))
                .thenReturn(new Estimate(AgentResultCategory.FOUND, result(), Set.of(141L, 2210L)));

        tools.estimateCompletion(List.of(141L), null, 2210L, null);

        verify(estimator).estimate(ASKER, List.of(141L), Set.of(), 2210L, NOW);
    }

    /** 설비도 이번 답변에서 조회한 객체만 쓴다(147). */
    @Test
    void refusesEquipmentOutsideThisAnswer() {
        session.allowObjects(List.of(141L));

        Map<String, Object> reply = tools.estimateCompletion(List.of(141L), null, 2210L, null);

        assertThat(reply).containsEntry("error", AgentQuerySession.NOT_TARGET);
        verifyNoInteractions(estimator);
    }

    /** 이번 답변의 대상이 아닌 작업지시가 하나라도 있으면 계산하지 않고 기록도 남기지 않는다(147). */
    @Test
    void refusesWorkOrdersOutsideThisAnswer() {
        session.allowObjects(List.of(141L));

        Map<String, Object> reply = tools.estimateCompletion(List.of(141L, 999L), null, null, null);

        assertThat(reply).containsEntry("error", AgentQuerySession.NOT_TARGET);
        verifyNoInteractions(estimator);
        verify(recorder, never())
                .record(any(), anyLong(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void recordsFailureForInvalidInput() {
        List<Long> tooMany = LongStream.rangeClosed(1, 21).boxed().toList();

        assertThat(tools.estimateCompletion(null, null, null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(List.of(), null, null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(List.of(141L, 141L), null, null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(tooMany, null, null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(Arrays.asList(141L, null), null, null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(List.of(141L), List.of(" "), null, null))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(tools.estimateCompletion(List.of(141L), null, null, "내일 아침"))
                .containsEntry("error", AgentQuerySession.QUERY_FAILED);

        verifyNoInteractions(estimator);
        assertThat(session.results())
                .hasSize(7)
                .allSatisfy(result -> assertThat(result.succeeded()).isFalse());
    }

    @Test
    void exposesToolToAi() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(AgentCompletionEstimateTools.ESTIMATE_COMPLETION);
        String schema = callbacks[0].getToolDefinition().inputSchema();
        assertThat(schema).contains("workOrderIds", "operationSequences", "equipmentId", "startAt");
    }

    private static Result result() {
        return new Result(
                "2026-10-30T14:00+09:00",
                null,
                "2026-10-31T01:23:20+09:00",
                new BigDecimal("11.4"),
                List.of(),
                CompletionEstimator.ASSUMPTIONS,
                List.of(),
                List.of());
    }
}

package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.service.AgentModelSummaryTools.LinkTypeItem;
import com.axcore.platform.agent.service.AgentModelSummaryTools.ModelSummary;
import com.axcore.platform.agent.service.AgentModelSummaryTools.ObjectTypeItem;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.PropertySummary;
import com.axcore.platform.ontology.service.ModelSummaryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentModelSummaryToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final ModelSummaryService models = mock(ModelSummaryService.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final DataAccess access = mock(DataAccess.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.systemUTC());
    private final AgentModelSummaryTools tools =
            new AgentModelSummaryTools(session, models, policy);

    @Test
    void returnsVisibleTypesAndLinkTypesWithEndNamesInAskersScope() {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(models.summarize(access))
                .thenReturn(
                        new ModelSummaryResponse(
                                List.of(
                                        new ObjectTypeSummary(1L, "WORK_ORDER", "작업지시", "생산 작업"),
                                        new ObjectTypeSummary(2L, "EQUIPMENT", "설비", null)),
                                List.of(
                                        new PropertySummary(
                                                10L,
                                                1L,
                                                "work_order_no",
                                                "작업지시 번호",
                                                PropertyDataType.STRING,
                                                null,
                                                PropertySensitivity.NORMAL,
                                                null)),
                                List.of(
                                        new LinkTypeSummary(
                                                5L,
                                                "ASSIGNED_TO",
                                                "배정 설비",
                                                1L,
                                                2L,
                                                LinkCardinality.MANY_TO_ONE,
                                                "작업지시가 배정된 설비")),
                                List.of()));

        Map<String, Object> reply = tools.modelSummary();

        verify(models).summarize(access);
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        ModelSummary data = (ModelSummary) reply.get("data");
        assertThat(data.objectTypes())
                .containsExactly(
                        new ObjectTypeItem(1L, "WORK_ORDER", "작업지시", "생산 작업"),
                        new ObjectTypeItem(2L, "EQUIPMENT", "설비", null));
        assertThat(data.linkTypes())
                .containsExactly(
                        new LinkTypeItem(
                                5L,
                                "ASSIGNED_TO",
                                "배정 설비",
                                "작업지시가 배정된 설비",
                                1L,
                                "작업지시",
                                2L,
                                "설비",
                                LinkCardinality.MANY_TO_ONE));
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentModelSummaryTools.MODEL_SUMMARY);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.resultCategory()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(result.sources()).isEmpty();
        assertThat(result.dataAsOf()).isNull();
        // 호출마다 질문 메시지(11)에 이어 AGENT_QUERY 기록을 남긴다. 대상 객체는 없다.
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(Map.of()),
                        isNull(),
                        isNull(),
                        eq(result),
                        any(Instant.class));
    }

    @Test
    void reportsNoDataWhenNoTypeIsVisible() {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(models.summarize(access))
                .thenReturn(new ModelSummaryResponse(List.of(), List.of(), List.of(), List.of()));

        Map<String, Object> reply = tools.modelSummary();

        assertThat(reply).containsEntry("resultCategory", "NO_DATA");
        assertThat(session.results().getFirst().resultCategory())
                .isEqualTo(AgentResultCategory.NO_DATA);
    }

    @Test
    void recordsFailureWhenSummaryCannotBeRead() {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(models.summarize(access)).thenThrow(new IllegalStateException("DB 연결 끊김"));

        Map<String, Object> reply = tools.modelSummary();

        assertThat(reply).containsKey("error");
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }
}

package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchAroundResult;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** DB로 만들기 어려운 경합 경로를 본다(380). */
class AgentObjectSetToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final long OPERATION = 3L;
    private static final long OBJECT = 11L;

    private final ObjectSearchQuery objects = mock(ObjectSearchQuery.class);
    private final ObjectDetailQuery details = mock(ObjectDetailQuery.class);
    private final ModelSummaryService models = mock(ModelSummaryService.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final PropertyValueSampleService values = mock(PropertyValueSampleService.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 21L, recorder, Clock.systemUTC());
    private final AgentObjectSetTools tools =
            new AgentObjectSetTools(session, objects, details, models, policy, values);

    /**
     * 출발 객체를 확인한 뒤 건너기 전에 또 사라지면 379가 RESOURCE_NOT_FOUND를 던진다. "데이터 없음"으로 바꾸지 않고 조회 실패로 기록해 AI가
     * 확인하지 못했다고 말하게 한다(380 리뷰).
     */
    @Test
    void recordsAFailureWhenABaseObjectDisappearsRightBeforeTheHop() {
        when(objects.findByIds(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(List.of(object(OBJECT)));
        when(policy.forModel(ASKER)).thenReturn(mock(DataAccess.class));
        when(models.summarize(any()))
                .thenReturn(new ModelSummaryResponse(List.of(), List.of(), List.of(), List.of()));
        when(objects.findAvailableIds(eq(ASKER), any())).thenReturn(List.of(OBJECT));
        when(details.searchAround(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenThrow(
                        new BusinessException(
                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                Map.of("resource", "object_instance", "id", OBJECT)));
        tools.objectSetStart(List.of(OBJECT), null, null, null, null, null);

        Map<String, Object> reply =
                tools.objectSetSearchAround("S1", 5L, "OUT", null, null, null, null);

        assertThat(reply)
                .containsEntry("error", AgentQuerySession.QUERY_FAILED)
                .doesNotContainKey("resultCategory");
        ArgumentCaptor<AgentQueryResult> results = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, times(2))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), results.capture(), any());
        assertThat(results.getValue().succeeded()).isFalse();
        assertThat(session.searchArounds()).as("실패한 건너기는 세지 않는다").isZero();
    }

    /** 건너기는 됐지만 결과를 만드는 뒤 조회가 실패하면 답은 실패이므로 건너기 횟수를 세지 않는다(380 4차 리뷰). */
    @Test
    void doesNotCountAHopWhoseFollowUpLookupFails() {
        when(objects.findByIds(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(List.of(object(OBJECT)));
        when(policy.forModel(ASKER)).thenReturn(mock(DataAccess.class));
        when(models.summarize(any()))
                .thenReturn(new ModelSummaryResponse(List.of(), List.of(), List.of(), List.of()));
        when(objects.findAvailableIds(eq(ASKER), any())).thenReturn(List.of(OBJECT));
        when(details.searchAround(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(ObjectSearchAroundResult.of(Direction.OUT, 9L, List.of(12L)));
        when(values.stringValues(eq(ASKER), eq(DataPurpose.MODEL), eq(9L), anyInt()))
                .thenThrow(new IllegalStateException("DB 오류"));
        tools.objectSetStart(List.of(OBJECT), null, null, null, null, null);

        Map<String, Object> reply =
                tools.objectSetSearchAround("S1", 5L, "OUT", null, null, null, null);

        assertThat(reply).containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(session.searchArounds()).isZero();
        assertThat(tools.objectSetList("S2", null))
                .as("실패한 건너기의 Object Set은 만들지 않는다")
                .containsKey("error");
    }

    private static ObjectSearchResponse object(long id) {
        return new ObjectSearchResponse(
                id,
                OPERATION,
                "작업지시 공정",
                "OP-" + id,
                Map.of(),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                null,
                Instant.parse("2026-11-01T00:00:00Z"),
                null,
                null);
    }
}

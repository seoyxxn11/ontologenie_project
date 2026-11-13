package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AgentQueryToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final AuditExecutionQueryService executions = mock(AuditExecutionQueryService.class);
    private final ObjectSearchQuery objects = mock(ObjectSearchQuery.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());
    private final AgentQueryTools tools = new AgentQueryTools(session, executions, objects);

    @Test
    void returnsExecutionsAsFoundWithoutDataAsOf() {
        AuditExecutionResponse row = mock(AuditExecutionResponse.class);
        when(row.id()).thenReturn(5L);
        when(row.actionName()).thenReturn("재배정 계획 생성");
        when(row.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(row.requestedAt()).thenReturn(Instant.parse("2026-11-01T00:00:00Z"));
        when(executions.objectExecutions(eq(ASKER), eq(DataPurpose.MODEL), eq(301L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        Map<String, Object> reply = tools.objectExecutions(301L, null, null);

        assertThat(reply).containsEntry("resultCategory", "FOUND");
        assertThat((List<?>) reply.get("data"))
                .singleElement()
                .extracting("requestedAt")
                .as("AI에게는 한국 시간 오프셋으로 넘긴다")
                .isEqualTo("2026-11-01T09:00+09:00");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentQueryTools.OBJECT_EXECUTIONS);
        assertThat(result.sources()).containsExactly(Map.of("objectId", 301L));
        assertThat(result.dataAsOf()).isNull();
    }

    @Test
    void filtersExecutionsByPeriodIncludingBothDaysInUserZone() {
        when(executions.objectExecutions(eq(ASKER), eq(DataPurpose.MODEL), eq(301L), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        tools.objectExecutions(301L, "2026-10-01", "2026-10-31");

        ArgumentCaptor<ObjectExecutionSearchRequest> request =
                ArgumentCaptor.forClass(ObjectExecutionSearchRequest.class);
        verify(executions)
                .objectExecutions(
                        eq(ASKER), eq(DataPurpose.MODEL), eq(301L), request.capture(), any());
        // 080은 to를 포함하지 않으므로 종료일 다음 날 0시(한국 시간)까지 넘긴다
        assertThat(request.getValue().from()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(request.getValue().to()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    }

    @Test
    void reportsUnreadablePeriodAsFailedWithoutQuerying() {
        assertThat(tools.objectExecutions(301L, "지난달", null)).containsKey("error");

        assertThat(session.results().getFirst().succeeded()).isFalse();
        verifyNoInteractions(executions);
    }

    @Test
    void treatsMissingObjectAsNoData() {
        when(executions.objectExecutions(eq(ASKER), eq(DataPurpose.MODEL), eq(999L), any(), any()))
                .thenThrow(
                        new BusinessException(
                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                Map.of("resource", "object_instance")));

        assertThat(tools.objectExecutions(999L, null, null))
                .containsEntry("resultCategory", "NO_DATA");
        assertThat(session.results().getFirst().resultCategory())
                .isEqualTo(AgentResultCategory.NO_DATA);
    }

    @Test
    void reportsOtherErrorsAsFailed() {
        when(executions.objectExecutions(eq(ASKER), eq(DataPurpose.MODEL), eq(301L), any(), any()))
                .thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThat(tools.objectExecutions(301L, null, null)).containsKey("error");
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }

    @Test
    void searchesObjectsAsAskerAndListsFoundObjectsAsSources() {
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(object(301L, "PR-03"), object(302L, "PR-03B")),
                                PageRequest.of(0, 20),
                                2));

        Map<String, Object> reply = tools.objectSearch("PR-03", 4L, null);

        ArgumentCaptor<ObjectSearchRequest> request =
                ArgumentCaptor.forClass(ObjectSearchRequest.class);
        verify(objects).search(eq(ASKER), eq(DataPurpose.MODEL), request.capture());
        assertThat(request.getValue())
                .isEqualTo(new ObjectSearchRequest("PR-03", 4L, null, 0, AgentQueryTools.MAX_ROWS));
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        Map<?, ?> data = (Map<?, ?>) reply.get("data");
        assertThat(data.get("total")).isEqualTo(2L);
        assertThat((List<?>) data.get("objects"))
                .extracting(o -> ((AgentQueryTools.ObjectSummary) o).displayName())
                .containsExactly("PR-03", "PR-03B");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentQueryTools.OBJECT_SEARCH);
        assertThat(result.sources())
                .containsExactly(Map.of("objectId", 301L), Map.of("objectId", 302L));
        assertThat(result.dataAsOf()).isNull();
    }

    @Test
    void tellsWhichPropertiesWereMaskedSoAiCanTellThemFromEmptyValues() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("equipment_code", "PR-03");
        properties.put("acquisition_cost", null);
        properties.put("note", null);
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(
                                        new ObjectSearchResponse(
                                                301L,
                                                4L,
                                                "설비",
                                                "PR-03",
                                                properties,
                                                List.of("acquisition_cost"),
                                                ObjectSearchRequest.Status.ACTIVE,
                                                null,
                                                Map.of("equipment_code", "PR-03"),
                                                null,
                                                null,
                                                null))));

        Map<String, Object> reply = tools.objectSearch("PR-03", null, null);

        AgentQueryTools.ObjectSummary found =
                (AgentQueryTools.ObjectSummary)
                        ((List<?>) ((Map<?, ?>) reply.get("data")).get("objects")).getFirst();
        // 가린 취득가와 원래 비어 있는 비고는 둘 다 null이다. 목록에 있는 것만 "볼 권한 없음"이다
        assertThat(found.properties())
                .containsEntry("acquisition_cost", null)
                .containsEntry("note", null);
        assertThat(found.restrictedPropertyCodes()).containsExactly("acquisition_cost");
    }

    @Test
    void acceptsLowerCaseStatusAndReportsUnknownStatusAsFailed() {
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        tools.objectSearch("PR-03", null, "active");
        Map<String, Object> unknown = tools.objectSearch("PR-03", null, "archived");

        ArgumentCaptor<ObjectSearchRequest> request =
                ArgumentCaptor.forClass(ObjectSearchRequest.class);
        verify(objects).search(eq(ASKER), eq(DataPurpose.MODEL), request.capture());
        assertThat(request.getValue().status()).isEqualTo(ObjectSearchRequest.Status.ACTIVE);
        assertThat(unknown).containsKey("error");
        assertThat(session.results())
                .extracting(AgentQueryResult::succeeded)
                .containsExactly(true, false);
    }

    @Test
    void answersEmptySearchAsNoData() {
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(tools.objectSearch("없는 설비", null, null))
                .containsEntry("resultCategory", "NO_DATA");
        assertThat(session.results().getFirst().sources()).isEmpty();
    }

    @Test
    void answersForbiddenSearchAsDeniedWithoutDataAsOf() {
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenThrow(
                        new BusinessException(
                                CommonErrorCode.FORBIDDEN,
                                "원천에서 삭제된 객체는 관리자만 조회할 수 있습니다.",
                                Map.of("field", "status")));

        Map<String, Object> reply = tools.objectSearch(null, null, "DELETED_AT_SOURCE");

        assertThat(reply).containsEntry("resultCategory", "DENIED");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.resultCategory()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(result.sources()).isEmpty();
        assertThat(result.dataAsOf()).isNull();
    }

    private static ObjectSearchResponse object(long id, String name) {
        return new ObjectSearchResponse(
                id,
                4L,
                "설비",
                name,
                Map.of("equipment_code", name),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                Map.of("equipment_code", name),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:00Z"),
                null);
    }
}

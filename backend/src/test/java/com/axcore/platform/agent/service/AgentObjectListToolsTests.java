package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.service.AgentObjectListTools.ObjectList;
import com.axcore.platform.agent.service.AgentObjectListTools.PropertyItem;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.PropertySummary;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentObjectListToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final long EQUIPMENT = 4L;

    private final ObjectSearchQuery objects = mock(ObjectSearchQuery.class);
    private final ModelSummaryService models = mock(ModelSummaryService.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final DataAccess access = mock(DataAccess.class);
    private final PropertyValueSampleService values = mock(PropertyValueSampleService.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.systemUTC());
    private final AgentObjectListTools tools =
            new AgentObjectListTools(session, objects, models, policy, values);

    @Test
    void listsActiveObjectsOfTypeByConditionsAsAskerWithTotalAndProperties() {
        givenProperties(
                property(EQUIPMENT, "status", "상태", PropertyDataType.STRING),
                property(EQUIPMENT, "line", "라인", PropertyDataType.STRING),
                property(EQUIPMENT, "capacity_ton", "능력", PropertyDataType.INTEGER),
                property(9L, "wo_no", "작업지시 번호", PropertyDataType.STRING));
        // 값 종류가 20개를 넘는 라인은 ontology가 빼고 준다.
        when(values.stringValues(
                        ASKER, DataPurpose.MODEL, EQUIPMENT, AgentObjectListTools.MAX_VALUES))
                .thenReturn(Map.of("status", List.of("가동", "정비중")));
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(object(301L, "P101"), object(302L, "P102")),
                                PageRequest.of(0, 20),
                                2));

        Map<String, Object> reply =
                tools.objectList(EQUIPMENT, Map.of("status", "가동"), null, null, null, null);

        ArgumentCaptor<ObjectSearchRequest> request =
                ArgumentCaptor.forClass(ObjectSearchRequest.class);
        verify(objects).search(eq(ASKER), eq(DataPurpose.MODEL), request.capture());
        // 사용 중인 객체만, 076의 속성 조건 그대로, 20건씩 질문한 사용자로 조회한다.
        assertThat(request.getValue())
                .isEqualTo(
                        new ObjectSearchRequest(
                                null,
                                EQUIPMENT,
                                ObjectSearchRequest.Status.ACTIVE,
                                0,
                                AgentQueryTools.MAX_ROWS,
                                Map.of("status", "가동"),
                                null,
                                null,
                                null));
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        ObjectList data = (ObjectList) reply.get("data");
        assertThat(data.total()).isEqualTo(2L);
        assertThat(data.hasNext()).isFalse();
        assertThat(data.objects())
                .extracting(AgentQueryTools.ObjectSummary::displayName)
                .containsExactly("P101", "P102");
        // 조건에 쓸 속성은 이 유형 것만 준다. 값 종류가 없는 속성(문자 속성이 아니거나 종류가 많음)은 null이다.
        assertThat(data.properties())
                .containsExactly(
                        new PropertyItem(
                                "status", "상태", PropertyDataType.STRING, List.of("가동", "정비중")),
                        new PropertyItem("line", "라인", PropertyDataType.STRING, null),
                        new PropertyItem("capacity_ton", "능력", PropertyDataType.INTEGER, null));
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentObjectListTools.OBJECT_LIST);
        assertThat(result.sources())
                .containsExactly(Map.of("objectId", 301L), Map.of("objectId", 302L));
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(
                                Map.of(
                                        "objectTypeId",
                                        EQUIPMENT,
                                        "propertyValues",
                                        Map.of("status", "가동"))),
                        eq(null),
                        eq(null),
                        any(),
                        any());
    }

    @Test
    void tellsTotalAndNextPageWhenOnlyPartIsReturned() {
        givenProperties();
        List<ObjectSearchResponse> rows =
                LongStream.rangeClosed(1, 20).mapToObj(id -> object(id, "WO-" + id)).toList();
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(1, 20), 45));

        ObjectList data =
                (ObjectList) tools.objectList(EQUIPMENT, null, null, null, null, 1).get("data");

        assertThat(data.total()).isEqualTo(45L);
        assertThat(data.page()).isEqualTo(1);
        assertThat(data.hasNext()).isTrue();
        assertThat(data.objects()).hasSize(20);
    }

    @Test
    void turnsUserDatesIntoKoreanDayBoundsOnlyForDatetimeProperty() {
        givenProperties(
                property(EQUIPMENT, "started_at", "시작 시각", PropertyDataType.DATETIME),
                property(EQUIPMENT, "planned_start", "계획 시작일", PropertyDataType.DATE));
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        tools.objectList(EQUIPMENT, null, "started_at", "2026-10-01", "2026-10-31", null);
        tools.objectList(EQUIPMENT, null, "planned_start", "2026-10-01", "2026-10-31", null);

        ArgumentCaptor<ObjectSearchRequest> request =
                ArgumentCaptor.forClass(ObjectSearchRequest.class);
        verify(objects, times(2)).search(eq(ASKER), eq(DataPurpose.MODEL), request.capture());
        // 076의 끝은 포함이므로 끝 날 한국 시간 마지막 마이크로초까지 넣는다(DB 시각은 마이크로초까지).
        assertThat(request.getAllValues().getFirst().from()).isEqualTo("2026-10-01T00:00+09:00");
        assertThat(request.getAllValues().getFirst().to())
                .isEqualTo("2026-10-31T23:59:59.999999+09:00");
        assertThat(request.getAllValues().get(1).from()).isEqualTo("2026-10-01");
        assertThat(request.getAllValues().get(1).to()).isEqualTo("2026-10-31");
    }

    @Test
    void marksConditionValuesThatNoStoredValueMatches() {
        givenProperties(
                property(EQUIPMENT, "status", "상태", PropertyDataType.STRING),
                property(EQUIPMENT, "line", "라인", PropertyDataType.STRING),
                property(EQUIPMENT, "zone", "구역", PropertyDataType.STRING));
        when(values.stringValues(
                        ASKER, DataPurpose.MODEL, EQUIPMENT, AgentObjectListTools.MAX_VALUES))
                .thenReturn(
                        Map.of(
                                "status", List.of("가동", "정비중"),
                                "zone", List.of("A 구역", "A구 역")));
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        // 띄어쓰기만 다른 저장 값이 하나면 076이 맞추므로 표시하지 않는다.
        ObjectList spaced =
                (ObjectList)
                        tools.objectList(
                                        EQUIPMENT, Map.of("status", "정비 중"), null, null, null, null)
                                .get("data");
        assertThat(spaced.unmatchedValues()).isEmpty();

        // 저장 값에 없는 값, 띄어쓰기만 다른 값이 여럿인 값은 표시한다. 값 종류를 모르는 속성(line)은 판단하지 않는다.
        ObjectList unmatched =
                (ObjectList)
                        tools.objectList(
                                        EQUIPMENT,
                                        Map.of("status", "정지", "zone", "A구역", "line", "9라인"),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(unmatched.unmatchedValues())
                .containsOnly(Map.entry("status", "정지"), Map.entry("zone", "A구역"));
    }

    @Test
    void answersNoDataWithoutDataAsOfWhenNothingMatches() {
        givenProperties();
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> reply =
                tools.objectList(EQUIPMENT, Map.of("status", "정지"), null, null, null, null);

        assertThat(reply).containsEntry("resultCategory", "NO_DATA");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.sources()).isEmpty();
        assertThat(result.dataAsOf()).isNull();
    }

    @Test
    void answersDeniedWhenFilteringByRestrictedPropertyTheAskerCannotView() {
        givenProperties();
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenThrow(
                        new BusinessException(
                                CommonErrorCode.FORBIDDEN,
                                "이 속성으로 검색할 권한이 없습니다.",
                                Map.of("field", "propertyValues[cost]")));

        Map<String, Object> reply =
                tools.objectList(EQUIPMENT, Map.of("cost", "1200"), null, null, null, null);

        assertThat(reply).containsEntry("resultCategory", "DENIED");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.resultCategory()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(result.sources()).isEmpty();
    }

    @Test
    void tellsAiWhyConditionIsInvalidButKeepsCommonMessageInResults() {
        givenProperties();
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenThrow(
                        BusinessException.invalidField(
                                "propertyValues[colour]", "선택한 객체 유형에 정의된 속성 코드를 지정해 주세요."));

        Map<String, Object> reply =
                tools.objectList(EQUIPMENT, Map.of("colour", "red"), null, null, null, null);

        assertThat((String) reply.get("error"))
                .startsWith(AgentQuerySession.QUERY_FAILED)
                .contains("propertyValues[colour]: 선택한 객체 유형에 정의된 속성 코드를 지정해 주세요.");
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage()).isEqualTo(AgentQuerySession.QUERY_FAILED);
    }

    @Test
    void requiresObjectTypeAndNonNegativePageWithoutQuerying() {
        Map<String, Object> withoutType = tools.objectList(null, null, null, null, null, null);
        Map<String, Object> negativePage = tools.objectList(EQUIPMENT, null, null, null, null, -1);

        assertThat((String) withoutType.get("error")).contains("objectTypeId");
        assertThat((String) negativePage.get("error")).contains("page");
        assertThat(session.results()).noneMatch(AgentQueryResult::succeeded).hasSize(2);
        verifyNoInteractions(objects);
    }

    @Test
    void reportsOtherErrorsAsFailedWithoutDetails() {
        givenProperties();
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenThrow(new IllegalStateException("DB 연결 끊김"));

        Map<String, Object> reply = tools.objectList(EQUIPMENT, null, null, null, null, null);

        assertThat(reply).containsEntry("error", AgentQuerySession.QUERY_FAILED);
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }

    @Test
    void givesNoPropertiesOfTypeOutsideScope() {
        // 043은 볼 수 없는 유형의 속성을 빼고, 076은 그 유형을 0건으로 돌려준다.
        givenProperties(property(9L, "wo_no", "작업지시 번호", PropertyDataType.STRING));
        when(objects.search(eq(ASKER), eq(DataPurpose.MODEL), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> reply = tools.objectList(EQUIPMENT, null, null, null, null, null);

        assertThat(reply).containsEntry("resultCategory", "NO_DATA");
        assertThat(((ObjectList) reply.get("data")).properties()).isEmpty();
    }

    @Test
    void describesPropertyValuesAsObjectAndOnlyObjectTypeAsRequired() {
        String schema = ToolCallbacks.from(tools)[0].getToolDefinition().inputSchema();

        // AI가 {"status": "가동"}처럼 보낼 수 있게 객체로 알려 준다.
        JsonNode root = JsonMapper.builder().build().readTree(schema);
        assertThat(root.at("/properties/propertyValues/type").asText()).isEqualTo("object");
        assertThat(root.get("required"))
                .extracting(JsonNode::asText)
                .containsExactly("objectTypeId");
    }

    private void givenProperties(PropertySummary... properties) {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(models.summarize(access))
                .thenReturn(
                        new ModelSummaryResponse(
                                List.of(new ObjectTypeSummary(EQUIPMENT, "EQUIPMENT", "설비", null)),
                                List.of(properties),
                                List.of(),
                                List.of()));
    }

    private static PropertySummary property(
            long typeId, String code, String name, PropertyDataType dataType) {
        return new PropertySummary(
                (long) code.hashCode(),
                typeId,
                code,
                name,
                dataType,
                null,
                PropertySensitivity.NORMAL,
                null);
    }

    private static ObjectSearchResponse object(long id, String name) {
        return new ObjectSearchResponse(
                id,
                EQUIPMENT,
                "설비",
                name,
                Map.of("code", name, "status", "가동"),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                Map.of("code", name),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:00Z"),
                null);
    }
}

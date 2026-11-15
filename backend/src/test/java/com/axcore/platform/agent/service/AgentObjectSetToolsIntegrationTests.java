package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Object Set 도구(380)가 실제 076·379와 DB로 시작 → 관계 따라가기 → 목록 보기를 하는지, 목록으로 받은 객체만 허용 객체가 되는지, 상한·잘못된
 * 입력·권한을 지키는지 본다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentObjectSetToolsIntegrationTests {
    private static final LoginAccount VIEWER = new LoginAccount(1L, "tester", "VIEWER", false);
    private static final Instant AS_OF = Instant.parse("2026-11-13T00:00:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired ObjectSearchQuery objects;
    @Autowired ObjectDetailQuery details;
    @Autowired ModelSummaryService models;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired PropertyValueSampleService values;

    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final DataAsOfResolver dataAsOf = mock(DataAsOfResolver.class);

    private String tag;
    private long equipment;
    private long operation;
    private long workOrder;
    private long operationEquipment;
    private long operationOrder;
    private long p103;
    private long o1;
    private long o2;
    private long o3;
    private long w1;
    private long w2;
    private long w3;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString();
        equipment = type(tag + "-equipment", tag);
        operation = type(tag + "-operation", tag);
        workOrder = type(tag + "-order", tag);
        allowViewer(tag);
        operationEquipment = linkType(tag + "-op-equipment", operation, equipment);
        operationOrder = linkType(tag + "-op-order", operation, workOrder);
        property(workOrder, "state", "STRING", "NORMAL");
        property(workOrder, "cost", "INTEGER", "RESTRICTED");
        p103 = object(equipment, "P103", "{}");
        o1 = object(operation, "OP-1", "{}");
        o2 = object(operation, "OP-2", "{}");
        o3 = object(operation, "OP-3", "{}");
        w1 = object(workOrder, "WO-1", "{\"state\":\"진행\",\"cost\":100}");
        w2 = object(workOrder, "WO-2", "{\"state\":\"완료\",\"cost\":200}");
        w3 = object(workOrder, "WO-3", "{\"state\":\"진행\",\"cost\":300}");
        for (long op : new long[] {o1, o2, o3}) {
            link(operationEquipment, op, p103);
        }
        link(operationOrder, o1, w1);
        link(operationOrder, o2, w1);
        link(operationOrder, o3, w2);
        link(operationOrder, o3, w3);
        when(dataAsOf.forObjects(any())).thenReturn(AS_OF);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsSearchesAroundTwoHopsAndListsOnlyTheFinalObjects() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        Map<String, Object> start =
                tools.objectSetStart(List.of(p103), null, null, null, null, null);
        assertThat(start).containsEntry("resultCategory", "FOUND");
        Map<String, Object> started = (Map<String, Object>) start.get("data");
        assertThat(started)
                .containsEntry("objectSetId", "S1")
                .containsEntry("objectTypeId", equipment)
                .containsEntry("count", 1)
                .containsEntry("sample", List.of("P103"));
        assertThat((List<AgentObjectSetTools.NextLinkType>) started.get("linkTypes"))
                .as("시작 유형에서 갈 수 있는 관계 유형을 준다(382)")
                .extracting(
                        AgentObjectSetTools.NextLinkType::linkTypeId,
                        AgentObjectSetTools.NextLinkType::direction,
                        AgentObjectSetTools.NextLinkType::objectTypeId)
                .containsExactly(tuple(operationEquipment, "IN", operation));
        assertThat(start.get("dataAsOf")).isNotNull();

        Map<String, Object> hop1 =
                tools.objectSetSearchAround("S1", operationEquipment, null, null, null, null, null);
        Map<String, Object> operations = (Map<String, Object>) hop1.get("data");
        assertThat(operations)
                .containsEntry("objectSetId", "S2")
                .containsEntry("direction", Direction.IN)
                .containsEntry("objectTypeId", operation)
                .containsEntry("count", 3)
                .containsEntry("remainingSearchArounds", 4)
                .doesNotContainKey("unavailableBaseObjects");
        assertThat(session.objectsAllowed(List.of(o1))).as("관계 따라가기 결과는 허용 객체가 되지 않는다").isFalse();
        assertThat((List<AgentObjectSetTools.NextLinkType>) operations.get("linkTypes"))
                .extracting(
                        AgentObjectSetTools.NextLinkType::linkTypeId,
                        AgentObjectSetTools.NextLinkType::direction)
                .containsExactlyInAnyOrder(
                        tuple(operationEquipment, "OUT"), tuple(operationOrder, "OUT"));

        Map<String, Object> hop2 =
                tools.objectSetSearchAround(
                        "S2", operationOrder, "OUT", Map.of("state", "진행"), null, null, null);
        Map<String, Object> orders = (Map<String, Object>) hop2.get("data");
        assertThat(orders)
                .containsEntry("objectSetId", "S3")
                .containsEntry("count", 2)
                .containsEntry("sample", List.of("WO-1", "WO-3"));
        assertThat((List<AgentObjectListTools.PropertyItem>) orders.get("properties"))
                .as("볼 수 없는 민감 속성은 조건에 쓸 속성으로 알려 주지 않는다")
                .extracting(AgentObjectListTools.PropertyItem::code)
                .containsExactly("state");

        Map<String, Object> list = tools.objectSetList("S3", null);
        Map<String, Object> page = (Map<String, Object>) list.get("data");
        assertThat(page).containsEntry("total", 2).containsEntry("hasNext", false);
        List<AgentQueryTools.ObjectSummary> listed =
                (List<AgentQueryTools.ObjectSummary>) page.get("objects");
        assertThat(listed).extracting(AgentQueryTools.ObjectSummary::id).containsExactly(w1, w3);
        assertThat(listed.getFirst().properties()).containsEntry("state", "진행");
        assertThat(session.objectsAllowed(List.of(w1, w3))).isTrue();
        assertThat(session.objectsAllowed(List.of(w2))).isFalse();

        ArgumentCaptor<AgentQueryResult> results = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, times(4))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), results.capture(), any());
        assertThat(results.getAllValues())
                .extracting(AgentQueryResult::toolCode)
                .containsExactly(
                        AgentObjectSetTools.OBJECT_SET_START,
                        AgentObjectSetTools.OBJECT_SET_SEARCH_AROUND,
                        AgentObjectSetTools.OBJECT_SET_SEARCH_AROUND,
                        AgentObjectSetTools.OBJECT_SET_LIST);
        assertThat(results.getAllValues())
                .allSatisfy(r -> assertThat(r.dataAsOf()).isEqualTo(AS_OF));
        assertThat(results.getAllValues().get(1).sources())
                .as("건너기 기록에는 객체 ID를 넣지 않는다")
                .allSatisfy(source -> assertThat(source).doesNotContainKey("objectId"));
        assertThat(results.getAllValues().get(3).sources())
                .containsExactly(Map.of("objectId", w1), Map.of("objectId", w3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsBaseObjectsThatDisappearDuringTheAnswer() {
        AgentObjectSetTools tools = tools(session());
        tools.objectSetStart(List.of(p103), null, null, null, null, null);
        tools.objectSetSearchAround("S1", operationEquipment, null, null, null, null, null);
        deleteAtSource(o2, operation);

        Map<String, Object> hop =
                tools.objectSetSearchAround("S2", operationOrder, null, null, null, null, null);
        assertThat(hop).as("남은 출발 객체로 건넌다").containsEntry("resultCategory", "FOUND");
        Map<String, Object> orders = (Map<String, Object>) hop.get("data");
        assertThat(orders).containsEntry("count", 3).containsEntry("unavailableBaseObjects", 1);
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, times(3))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), recorded.capture(), any());
        assertThat(recorded.getValue().sources())
                .as("일부 객체로만 건넌 사실을 조회 기록에도 남긴다")
                .singleElement()
                .satisfies(source -> assertThat(source).containsEntry("unavailableBaseObjects", 1));

        Map<String, Object> list =
                (Map<String, Object>) tools.objectSetList("S2", null).get("data");
        assertThat(list).containsEntry("total", 3).containsEntry("unavailableOnPage", 1);
        assertThat((List<AgentQueryTools.ObjectSummary>) list.get("objects"))
                .extracting(AgentQueryTools.ObjectSummary::id)
                .containsExactly(o1, o3);

        deleteAtSource(o1, operation);
        deleteAtSource(o3, operation);
        Map<String, Object> gone =
                tools.objectSetSearchAround("S2", operationOrder, null, null, null, null, null);
        assertThat(gone).containsEntry("resultCategory", AgentResultCategory.NO_DATA.name());
        assertThat((Map<String, Object>) gone.get("data"))
                .containsEntry("unavailableBaseObjects", 3);
        assertThat(gone.get("dataAsOf")).isNull();
        ArgumentCaptor<AgentQueryResult> goneRecord =
                ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, atLeastOnce())
                .record(
                        any(),
                        anyLong(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        goneRecord.capture(),
                        any());
        assertThat(goneRecord.getValue().sources())
                .as("출발 객체가 모두 사라져도 어느 Object Set에서 몇 개가 빠졌는지 기록한다")
                .singleElement()
                .satisfies(
                        source ->
                                assertThat(source)
                                        .containsEntry("fromObjectSetId", "S2")
                                        .containsEntry("unavailableBaseObjects", 3));

        Map<String, Object> emptyPage = tools.objectSetList("S2", null);
        assertThat(emptyPage)
                .as("쪽의 객체가 모두 사라졌으면 보여 준 것이 없어 NO_DATA다")
                .containsEntry("resultCategory", AgentResultCategory.NO_DATA.name());
        assertThat((Map<String, Object>) emptyPage.get("data"))
                .containsEntry("unavailableOnPage", 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsObjectsTheAskerCanNoLongerSeeAndStartsWithTheRest() {
        long hiddenType = type(tag + "-hidden-op", null);
        AgentObjectSetTools tools = tools(session());
        tools.objectSetStart(List.of(o1, o2, o3), null, null, null, null, null);
        moveToType(o2, hiddenType);

        Map<String, Object> hop =
                (Map<String, Object>)
                        tools.objectSetSearchAround(
                                        "S1", operationOrder, null, null, null, null, null)
                                .get("data");
        assertThat(hop).containsEntry("count", 3).containsEntry("unavailableBaseObjects", 1);

        deleteAtSource(o3, operation);
        Map<String, Object> start =
                tools.objectSetStart(List.of(o1, o3), null, null, null, null, null);
        assertThat(start).as("시작 객체도 사라진 것은 빼고 만든다").containsEntry("resultCategory", "FOUND");
        assertThat((Map<String, Object>) start.get("data"))
                .containsEntry("count", 1)
                .containsEntry("unavailableObjects", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsFromConditionsAndMasksRestrictedValuesForTheViewer() {
        AgentObjectSetTools tools = tools(session());

        Map<String, Object> start =
                tools.objectSetStart(null, workOrder, Map.of("state", "진행 "), null, null, null);
        Map<String, Object> data = (Map<String, Object>) start.get("data");
        assertThat(data).containsEntry("count", 2).containsEntry("objectTypeId", workOrder);
        assertThat(start.get("dataAsOf")).isNotNull();

        Map<String, Object> list = tools.objectSetList((String) data.get("objectSetId"), 0);
        List<AgentQueryTools.ObjectSummary> listed =
                (List<AgentQueryTools.ObjectSummary>)
                        ((Map<String, Object>) list.get("data")).get("objects");
        assertThat(listed)
                .allSatisfy(
                        object -> {
                            assertThat(object.properties()).containsEntry("cost", null);
                            assertThat(object.restrictedPropertyCodes()).containsExactly("cost");
                        });

        Map<String, Object> denied =
                tools.objectSetStart(null, workOrder, Map.of("cost", "100"), null, null, null);
        assertThat(denied).containsEntry("resultCategory", AgentResultCategory.DENIED.name());
    }

    @Test
    void appliesTheSameScopeWhenSearchingAround() {
        long hiddenType = type(tag + "-hidden", null);
        long toHidden = linkType(tag + "-op-hidden", operation, hiddenType);
        link(toHidden, o1, object(hiddenType, "SECRET", "{}"));
        AgentObjectSetTools tools = tools(session());
        tools.objectSetStart(List.of(o1), null, null, null, null, null);

        assertThat(tools.objectSetSearchAround("S1", toHidden, null, null, null, null, null))
                .as("볼 수 없는 유형으로 가는 관계는 없는 관계 유형처럼 다룬다")
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("찾을 수 없습니다"));
        assertThat(
                        tools.objectSetSearchAround(
                                "S1",
                                operationOrder,
                                null,
                                Map.of("cost", "100"),
                                null,
                                null,
                                null))
                .containsEntry("resultCategory", AgentResultCategory.DENIED.name());
        assertThat(
                        tools.objectSetSearchAround(
                                "S1",
                                operationOrder,
                                null,
                                Map.of("state", "취소"),
                                null,
                                null,
                                null))
                .containsEntry("resultCategory", AgentResultCategory.NO_DATA.name())
                .as("조건을 건 상대 유형의 표를 알 수 없어 기준 시점을 비운다")
                .containsEntry("dataAsOf", null);
    }

    @Test
    void blankConditionsAreNotConditionsWhenNothingIsLinked() {
        long lonely = object(equipment, "P999", "{}");
        AgentObjectSetTools tools = tools(session());
        tools.objectSetStart(List.of(lonely), null, null, null, null, null);

        Map<String, Object> reply =
                tools.objectSetSearchAround("S1", operationEquipment, null, Map.of(), "", "", " ");

        assertThat(reply)
                .containsEntry("resultCategory", AgentResultCategory.UNLINKED.name())
                .containsEntry("dataAsOf", AgentQuerySession.inUserZone(AS_OF));
    }

    @Test
    void rejectsUnknownSetsWrongStartsAndObjectsOutsideTheQuestion() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        assertThat(tools.objectSetStart(List.of(o1), null, null, null, null, null))
                .containsEntry("error", AgentQuerySession.NOT_TARGET);
        assertThat(tools.objectSetStart(List.of(p103), equipment, null, null, null, null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("하나만"));
        assertThat(tools.objectSetStart(null, null, null, null, null, null)).containsKey("error");
        assertThat(tools.objectSetList("S9", null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("objectSetId"));
        assertThat(tools.objectSetSearchAround("S9", operationOrder, null, null, null, null, null))
                .containsKey("error");

        tools.objectSetStart(List.of(p103), null, null, null, null, null);
        assertThat(tools.objectSetSearchAround("S1", null, null, null, null, null, null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("linkTypeId"));
        assertThat(
                        tools.objectSetSearchAround(
                                "S1", operationEquipment, "UP", null, null, null, null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("direction"));
        assertThat(tools.objectSetList("S1", -1)).containsKey("error");
    }

    @Test
    void listedObjectsAreAllowedForQuestionsAboutAWholeKind() {
        // 종류 전체 질문도 목록 보기로 받은 객체는 조회할 수 있다. 시작만 하고 받지 않은 객체는 아직 허용하지 않는다(326, 380).
        AgentQuerySession session = session();
        session.allowObjects(List.of());
        AgentObjectSetTools tools = tools(session);

        tools.objectSetStart(null, workOrder, null, null, null, null);
        assertThat(session.objectsAllowed(List.of(w1))).isFalse();

        tools.objectSetList("S1", null);
        assertThat(session.objectsAllowed(List.of(w1))).isTrue();
    }

    @Test
    void limitsSearchAroundsPerQuestion() {
        long peer = linkType(tag + "-peer", equipment, equipment);
        long other = object(equipment, "P104", "{}");
        link(peer, p103, other);
        AgentObjectSetTools tools = tools(session());
        tools.objectSetStart(List.of(p103), null, null, null, null, null);

        String set = "S1";
        for (int hop = 1; hop <= AgentObjectSetTools.MAX_SEARCH_AROUNDS; hop++) {
            Map<String, Object> reply =
                    tools.objectSetSearchAround(set, peer, null, null, null, null, null);
            assertThat(reply).containsEntry("resultCategory", "FOUND");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) reply.get("data");
            set = (String) data.get("objectSetId");
        }
        assertThat(tools.objectSetSearchAround(set, peer, null, null, null, null, null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("5번까지"));
    }

    @Test
    void rejectsStartingFromMoreObjectsThanTheSetLimit() {
        jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        select :type, 'BULK-' || n, '{"state":"대기"}'::jsonb
                        from generate_series(1, 5001) n
                        """)
                .param("type", workOrder)
                .update();
        AgentObjectSetTools tools = tools(session());

        assertThat(tools.objectSetStart(null, workOrder, Map.of("state", "대기"), null, null, null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("5000건을 넘습니다"));
        assertThat(
                        tools.objectSetStart(
                                LongStream.rangeClosed(1, 21).boxed().toList(),
                                null,
                                null,
                                null,
                                null,
                                null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("20개"));
    }

    /** 목표 유형 찾기: 서버가 경로를 찾아 건너고, 도착 객체의 값별 건수와 끝난 값 후보를 준다. 민감 속성은 세지 않는다. */
    @Test
    @SuppressWarnings("unchecked")
    void findsTargetTypeThroughPathAndShowsValueCounts() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        Map<String, Object> found =
                tools.find(List.of(p103), tag + "-order", null, null, null, null, null);

        assertThat(found).containsEntry("resultCategory", "FOUND");
        Map<String, Object> data = (Map<String, Object>) found.get("data");
        assertThat(data)
                .containsEntry("targetTypeId", workOrder)
                .containsEntry("chosenPathIndex", 1)
                .containsEntry("objectSetId", "S1")
                .containsEntry("count", 3);
        List<Map<String, Object>> paths = (List<Map<String, Object>>) data.get("paths");
        assertThat(paths).hasSize(1);
        assertThat(paths.getFirst())
                .containsEntry(
                        "route", tag + "-equipment → " + tag + "-operation → " + tag + "-order")
                .containsEntry("count", 3)
                .containsEntry("valueCounts", Map.of("state", Map.of("진행", 2L, "완료", 1L)))
                .containsEntry("finishedLike", Map.of("state", List.of("완료")));
        assertThat(session.objectsAllowed(List.of(w1)))
                .as("찾은 객체는 목록으로 받기 전에는 허용 객체가 아니다")
                .isFalse();

        Map<String, Object> listed =
                (Map<String, Object>) tools.objectSetList("S1", null).get("data");
        assertThat(listed).containsEntry("total", 3);
        assertThat(session.objectsAllowed(List.of(w1, w2, w3))).isTrue();
    }

    /** 조건은 도착 유형에만 걸리고, 값이 여럿이면 그중 하나와 같으면 된다. 없는 값은 unmatchedValues로 알린다. */
    @Test
    @SuppressWarnings("unchecked")
    void appliesConditionsOnlyToTargetAndAcceptsSeveralValues() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        Map<String, Object> running =
                (Map<String, Object>)
                        tools.find(
                                        List.of(p103),
                                        tag + "-order",
                                        Map.of("state", List.of("진행")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(running).containsEntry("count", 2);

        Map<String, Object> both =
                (Map<String, Object>)
                        tools.find(
                                        List.of(p103),
                                        tag + "-order",
                                        Map.of("state", List.of("진행", "완료")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(both).containsEntry("count", 3);

        // 한 답변에서 찾기는 3번까지라 아래는 새 답변(세션)으로 본다.
        AgentQuerySession next = session();
        next.allowObjects(List.of(p103));
        AgentObjectSetTools fresh = tools(next);
        // 저장 값에 없는 값은 0건으로 끝내지 않고 저장 값을 알려 다시 부르게 한다("진행 중"으로 0건을 받고 "없다"고 답하던 실수, GMS M1).
        assertThat(
                        fresh.find(
                                List.of(p103),
                                tag + "-order",
                                Map.of("state", List.of("진행 중")),
                                null,
                                null,
                                null,
                                null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("저장된 값", "[완료, 진행]"));
        // 띄어쓰기만 다르면 저장 값으로 맞춘다(378과 같은 기준).
        Map<String, Object> spaced =
                (Map<String, Object>)
                        fresh.find(
                                        List.of(p103),
                                        tag + "-order",
                                        Map.of("state", List.of(" 진 행")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(spaced).containsEntry("count", 2);
        assertThat(
                        fresh.find(
                                List.of(p103),
                                tag + "-order",
                                Map.of("nothing", List.of("x")),
                                null,
                                null,
                                null,
                                null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("nothing"));
    }

    /** 민감 문자 속성은 값별 건수에 넣지 않는다. 숫자 속성에도 여러 값 조건을 건다(text[] → numeric[]). */
    @Test
    @SuppressWarnings("unchecked")
    void keepsRestrictedStringsOutOfCountsAndFiltersNumbersWithSeveralValues() {
        property(workOrder, "secret", "STRING", "RESTRICTED");
        property(workOrder, "qty", "INTEGER", "NORMAL");
        setProperties(w1, "{\"state\":\"진행\",\"secret\":\"A\",\"qty\":1}");
        setProperties(w2, "{\"state\":\"완료\",\"secret\":\"B\",\"qty\":2}");
        setProperties(w3, "{\"state\":\"진행\",\"secret\":\"A\",\"qty\":3}");
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        Map<String, Object> all =
                (Map<String, Object>)
                        tools.find(List.of(p103), tag + "-order", null, null, null, null, null)
                                .get("data");
        Map<String, Object> path = ((List<Map<String, Object>>) all.get("paths")).getFirst();
        assertThat((Map<String, Object>) path.get("valueCounts"))
                .containsKey("state")
                .doesNotContainKey("secret");

        Map<String, Object> some =
                (Map<String, Object>)
                        tools.find(
                                        List.of(p103),
                                        tag + "-order",
                                        Map.of("qty", List.of("1", "3")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(some).containsEntry("count", 2);
    }

    /** 가장 짧은 경로가 여럿이고 둘 다 결과가 있으면 고르지 않고 경로별 건수를 준다. pathIndex로 하나만 다시 본다. */
    @Test
    @SuppressWarnings("unchecked")
    void listsSeveralShortestPathsWithoutChoosingAndAllowsPathIndex() {
        long downtime = type(tag + "-downtime", tag);
        long downtimeEquipment = linkType(tag + "-dt-equipment", downtime, equipment);
        long downtimeOrder = linkType(tag + "-dt-order", downtime, workOrder);
        long d1 = object(downtime, "DT-1", "{}");
        link(downtimeEquipment, d1, p103);
        link(downtimeOrder, d1, w1);
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        Map<String, Object> both =
                (Map<String, Object>)
                        tools.find(List.of(p103), tag + "-order", null, null, null, null, null)
                                .get("data");
        assertThat(both).doesNotContainKey("chosenPathIndex").containsKey("next");
        assertThat((List<Map<String, Object>>) both.get("paths"))
                .extracting(item -> item.get("count"))
                .containsExactlyInAnyOrder(3, 1);

        Map<String, Object> one =
                (Map<String, Object>)
                        tools.find(List.of(p103), tag + "-order", null, null, null, null, 2)
                                .get("data");
        assertThat(one).containsEntry("chosenPathIndex", 2);
        assertThat((List<?>) one.get("paths")).hasSize(1);

        tools.find(List.of(p103), tag + "-order", null, null, null, null, null);
        assertThat(tools.find(List.of(p103), tag + "-order", null, null, null, null, null))
                .as("한 답변에서 목표 유형 찾기는 3번까지")
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("3번까지"));
    }

    @Test
    void rejectsStartObjectsThatAreNotTargetsAndSameTypeTarget() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        assertThat(tools.find(List.of(w1), tag + "-order", null, null, null, null, null))
                .containsEntry("error", AgentQuerySession.NOT_TARGET);
        assertThat(tools.find(List.of(p103), tag + "-equipment", null, null, null, null, null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("이미"));
        assertThat(tools.find(List.of(p103), tag + "-order", null, null, null, null, 2))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("pathIndex"));
    }

    /** 없는 유형 이름에는 비슷한 이름을 알리고, 찾기 결과를 관계 따라가기로 거르다 실패하면 찾기를 다시 부르라고 알린다. */
    @Test
    void suggestsSimilarTypesAndPointsFoundSetsBackToFind() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(p103));
        AgentObjectSetTools tools = tools(session);

        assertThat(tools.find(List.of(p103), "order", null, null, null, null, null))
                .hasEntrySatisfying(
                        "error",
                        error ->
                                assertThat((String) error)
                                        .contains(
                                                "이름이 비슷한 유형", tag + "-order(#" + workOrder + ")"));

        tools.find(List.of(p103), tag + "-order", null, null, null, null, null);
        assertThat(tools.objectSetSearchAround("S1", 999_999_999L, null, null, null, null, null))
                .hasEntrySatisfying(
                        "error",
                        error ->
                                assertThat((String) error)
                                        .contains("#999999999")
                                        .endsWith(AgentObjectSetTools.FOUND_SET_HINT));

        tools.objectSetStart(List.of(p103), null, null, null, null, null);
        String started = "S2";
        assertThat(tools.objectSetSearchAround(started, 999_999_999L, null, null, null, null, null))
                .as("찾기가 만들지 않은 집합에는 안내를 붙이지 않는다")
                .hasEntrySatisfying(
                        "error",
                        error ->
                                assertThat((String) error)
                                        .doesNotContain(AgentObjectSetTools.OBJECT_SET_FIND));
    }

    /**
     * 관계 따라가기도 값 목록을 받는다(그중 하나와 같으면 된다). 값 하나만 받던 때 목록을 넣어 입력을 읽지 못하고, 조건을 뺀 채 끝난 기록까지 모아 답을 확정하지
     * 못한 일이 있었다(2026-11-14 대화 103). 출발 유형의 속성을 조건에 넣으면 도착 유형의 속성을 알려 준다(대화 102).
     */
    @Test
    @SuppressWarnings("unchecked")
    void searchesAroundWithSeveralValuesAndExplainsConditionsOnTheWrongSide() {
        property(operation, "seq", "STRING", "NORMAL");
        AgentQuerySession session = session();
        session.allowObjects(List.of(o1, o2, o3));
        AgentObjectSetTools tools = tools(session);
        tools.objectSetStart(List.of(o1, o2, o3), null, null, null, null, null);

        Map<String, Object> both =
                (Map<String, Object>)
                        tools.objectSetSearchAround(
                                        "S1",
                                        operationOrder,
                                        null,
                                        Map.of("state", List.of("진행", "완료")),
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(both).containsEntry("count", 3);
        Map<String, Object> partly =
                (Map<String, Object>)
                        tools.objectSetSearchAround(
                                        "S1",
                                        operationOrder,
                                        null,
                                        Map.of("state", List.of("진행", "보류")),
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(partly)
                .containsEntry("count", 2)
                .containsEntry("unmatchedValues", Map.of("state", "보류"));
        Map<String, Object> one =
                (Map<String, Object>)
                        tools.objectSetSearchAround(
                                        "S1",
                                        operationOrder,
                                        null,
                                        Map.of("state", List.of("완료")),
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(one).as("값이 하나뿐인 목록은 값 하나 조건과 같다").containsEntry("count", 1);

        assertThat(
                        tools.objectSetSearchAround(
                                "S1", operationOrder, null, Map.of("seq", "10"), null, null, null))
                .hasEntrySatisfying(
                        "error",
                        error ->
                                assertThat((String) error)
                                        .contains("도착 유형", "[state]", "출발 유형", "[seq]"));
        assertThat(
                        tools.objectSetStart(
                                null,
                                workOrder,
                                Map.of("state", List.of("진행", "완료")),
                                null,
                                null,
                                null))
                .as("조건으로 시작할 때도 값 목록을 받는다")
                .hasEntrySatisfying(
                        "data",
                        data -> assertThat((Map<String, Object>) data).containsEntry("count", 3));
    }

    /**
     * 빈 값(null, 빈 목록, null만 든 목록)은 조건을 빼지 않고 입력 오류다. 조용히 빼면 조건 없는 결과가 조건을 건 결과처럼 나간다(!475 리뷰). 값
     * 목록은 20개까지, 목표 유형 찾기는 값 하나도 받는다. 방향 오류에는 조건 안내를 붙이지 않는다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsEmptyConditionsAndKeepsHintsToConditionErrors() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(o1, o2, o3, p103));
        AgentObjectSetTools tools = tools(session);
        tools.objectSetStart(List.of(o1, o2, o3), null, null, null, null, null);
        Map<String, Object> nullValue = new java.util.HashMap<>();
        nullValue.put("state", null);
        List<String> onlyNull = new java.util.ArrayList<>();
        onlyNull.add(null);

        for (Map<String, Object> empty :
                List.<Map<String, Object>>of(
                        Map.of("state", List.of()), nullValue, Map.of("state", onlyNull))) {
            assertThat(
                            tools.objectSetSearchAround(
                                    "S1", operationOrder, null, empty, null, null, null))
                    .hasEntrySatisfying(
                            "error", error -> assertThat((String) error).contains("값을 하나 이상"));
            assertThat(tools.objectSetStart(null, workOrder, empty, null, null, null))
                    .hasEntrySatisfying(
                            "error", error -> assertThat((String) error).contains("값을 하나 이상"));
        }
        List<String> many =
                java.util.stream.IntStream.rangeClosed(1, AgentConditions.MAX_VALUES + 1)
                        .mapToObj(i -> "v" + i)
                        .toList();
        assertThat(
                        tools.objectSetSearchAround(
                                "S1",
                                operationOrder,
                                null,
                                Map.of("state", many),
                                null,
                                null,
                                null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("20개까지"));
        assertThat(
                        tools.objectSetSearchAround(
                                "S1",
                                operationOrder,
                                "IN",
                                Map.of("state", "진행"),
                                null,
                                null,
                                null))
                .as("방향 오류에는 조건 안내를 붙이지 않는다")
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).doesNotContain("의 속성에만 겁니다"));

        AgentQuerySession findSession = session();
        findSession.allowObjects(List.of(p103));
        AgentObjectSetTools finder = tools(findSession);
        assertThat(
                        finder.find(
                                List.of(p103),
                                tag + "-order",
                                Map.of("state", List.of()),
                                null,
                                null,
                                null,
                                null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("값을 하나 이상"));
        Map<String, Object> single =
                (Map<String, Object>)
                        finder.find(
                                        List.of(p103),
                                        tag + "-order",
                                        Map.of("state", "진행"),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        assertThat(single).as("목표 유형 찾기도 값 하나를 받는다").containsEntry("count", 2);
    }

    private AgentQuerySession session() {
        return new AgentQuerySession(VIEWER, 11L, recorder, Clock.systemUTC(), dataAsOf);
    }

    private AgentObjectSetTools tools(AgentQuerySession session) {
        return new AgentObjectSetTools(session, objects, details, models, dataAccess, values);
    }

    private long type(String code, String accessGroup) {
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", code)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private void allowViewer(String accessGroup) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, false from role where code = 'VIEWER'
                        """)
                .param("group", accessGroup)
                .update();
    }

    private void property(long type, String code, String dataType, String sensitivity) {
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, :dataType, :sensitivity)
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .update();
    }

    private void setProperties(long id, String properties) {
        jdbc.sql(
                        "update object_instance set properties = cast(:properties as jsonb) where id = :id")
                .param("properties", properties)
                .param("id", id)
                .update();
    }

    private long object(long type, String name, String properties) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }

    /** 질문한 사용자가 볼 수 없는 유형으로 옮긴다. 답변 중에 권한이 바뀐 경우를 흉내 낸다. */
    private void moveToType(long objectId, long type) {
        jdbc.sql("update object_instance set object_type_id = :type where id = :id")
                .param("type", type)
                .param("id", objectId)
                .update();
    }

    /** 원천에서 삭제된 객체는 원천 매핑이 있어야 한다(ck_object_instance_platform). */
    private void deleteAtSource(long objectId, long type) {
        long mapping =
                jdbc.sql(
                                """
                                with source as (
                                    insert into data_source (code, name, source_kind)
                                    values (:code, :code, 'POSTGRES') returning id
                                ), resource as (
                                    insert into source_resource (data_source_id, resource_key, resource_kind)
                                    select id, 'public.item', 'TABLE' from source returning id
                                )
                                insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                                select id, :type, '["key"]'::jsonb from resource returning id
                                """)
                        .param("code", UUID.randomUUID().toString())
                        .param("type", type)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        update object_instance
                        set source_mapping_id = :mapping,
                            source_key = jsonb_build_object('key', id::text),
                            status = 'DELETED_AT_SOURCE'
                        where id = :id
                        """)
                .param("mapping", mapping)
                .param("id", objectId)
                .update();
    }

    private long linkType(String code, long source, long target) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, :code, :source, :target, 'MANY_TO_MANY', 'Test') returning id
                        """)
                .param("code", code)
                .param("source", source)
                .param("target", target)
                .query(Long.class)
                .single();
    }

    private void link(long linkType, long source, long target) {
        jdbc.sql(
                        """
                        with existing as (
                            select id from link_mapping where link_type_id = :type and status = 'ACTIVE'
                        ), created as (
                            insert into link_mapping (link_type_id, code, revision)
                            select :type, :code, 1 where not exists (select 1 from existing)
                            returning id
                        ), rule as (
                            select id from created union all select id from existing
                        )
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, link_mapping_id, status)
                        select :type, :source, :target, 'RULE', id, 'ACTIVE' from rule
                        """)
                .param("type", linkType)
                .param("code", UUID.randomUUID().toString())
                .param("source", source)
                .param("target", target)
                .update();
    }
}

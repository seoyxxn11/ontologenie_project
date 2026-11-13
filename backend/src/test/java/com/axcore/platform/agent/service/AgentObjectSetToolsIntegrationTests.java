package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
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
        session.allowObjects(List.of(p103), true);
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
        session.allowObjects(List.of(p103), true);
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
    void listedObjectsAreNotAllowedForQuestionsAboutAWholeKind() {
        AgentQuerySession session = session();
        session.allowObjects(List.of(), false);
        AgentObjectSetTools tools = tools(session);

        tools.objectSetStart(null, workOrder, null, null, null, null);
        tools.objectSetList("S1", null);

        assertThat(session.objectsAllowed(List.of(w1))).isFalse();
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

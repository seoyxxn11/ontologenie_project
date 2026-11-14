package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.service.AgentObjectListTools.ObjectList;
import com.axcore.platform.agent.service.AgentObjectListTools.PropertyItem;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** 목록 도구가 실제 076·043과 DB로 건수·일부·민감 속성 가림·숨긴 유형을 205 기준대로 다루는지 본다(359). */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AgentObjectListToolsIntegrationTests {
    private static final LoginAccount VIEWER = new LoginAccount(1L, "tester", "VIEWER", false);
    private static final LoginAccount ADMIN = new LoginAccount(1L, "tester", "ADMIN", false);
    private static final LoginAccount PLANNER = new LoginAccount(1L, "tester", "PLANNER", false);

    @Autowired JdbcClient jdbc;
    @Autowired ObjectSearchQuery objects;
    @Autowired ModelSummaryService models;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired PropertyValueSampleService values;

    private long visible;
    private long hidden;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString();
        visible = type(tag, tag);
        hidden = type(tag + "-hidden", tag + "-private");
        allowViewer(tag);
        for (long typeId : new long[] {visible, hidden}) {
            property(typeId, "status", "STRING", "NORMAL");
            property(typeId, "started_on", "DATE", "NORMAL");
            property(typeId, "cost", "INTEGER", "RESTRICTED");
        }
        for (int i = 1; i <= 25; i++) {
            String day = "2026-10-%02d".formatted(i);
            object(
                    visible,
                    "EQ-" + i,
                    "{\"status\":\"가동\",\"started_on\":\"%s\",\"cost\":1200}".formatted(day));
        }
        object(
                visible,
                "EQ-STOP",
                "{\"status\":\"정지\",\"started_on\":\"2026-11-02\",\"cost\":900}");
        object(hidden, "SECRET", "{\"status\":\"가동\",\"started_on\":\"2026-10-01\",\"cost\":1}");
    }

    @Test
    void countsAllMatchesButReturnsOnePageWithRestrictedValuesMasked() {
        Map<String, Object> reply =
                tools(VIEWER).objectList(visible, Map.of("status", "가동"), null, null, null, null);

        assertThat(reply).containsEntry("resultCategory", "FOUND");
        ObjectList data = (ObjectList) reply.get("data");
        assertThat(data.total()).isEqualTo(25L);
        assertThat(data.objects()).hasSize(AgentQueryTools.MAX_ROWS);
        assertThat(data.hasNext()).isTrue();
        assertThat(data.objects())
                .allSatisfy(
                        object -> {
                            assertThat(object.properties()).containsEntry("cost", null);
                            assertThat(object.restrictedPropertyCodes()).containsExactly("cost");
                        });
        // 볼 수 없는 민감 속성은 조건에 쓸 속성으로도 알려 주지 않는다.
        assertThat(data.properties())
                .extracting(PropertyItem::code)
                .containsExactly("status", "started_on");
        // 조건 값을 고를 수 있게 문자 속성의 저장 값 종류를 준다. 숨긴 유형의 값은 섞이지 않는다.
        assertThat(data.properties().getFirst().values()).containsExactly("가동", "정지");
        assertThat(data.properties().get(1).values()).isNull();
    }

    @Test
    void filtersByPeriodAndCombinesWithPropertyValues() {
        ObjectList october =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("status", "가동"),
                                        "started_on",
                                        "2026-10-20",
                                        "2026-10-31",
                                        null)
                                .get("data");
        ObjectList stopped =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(visible, Map.of("status", "정지"), null, null, null, null)
                                .get("data");

        assertThat(october.total()).isEqualTo(6L);
        assertThat(october.hasNext()).isFalse();
        assertThat(stopped.objects())
                .extracting(AgentQueryTools.ObjectSummary::displayName)
                .containsExactly("EQ-STOP");
    }

    @Test
    void datetimePeriodOfUserDatesIncludesWholeKoreanDaysButNotNextMidnight() {
        // 끝을 나노초로 넘기면 DB가 다음 날 0시로 반올림해 11-01 00:00이 10월에 들어갔다(359 리뷰 M1).
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, tag);
        allowViewer(tag);
        property(typeId, "started_at", "DATETIME", "NORMAL");
        object(typeId, "FIRST", "{\"started_at\":\"2026-10-01T00:00:00+09:00\"}");
        object(typeId, "LAST", "{\"started_at\":\"2026-10-31T23:59:59.999999+09:00\"}");
        object(typeId, "BEFORE", "{\"started_at\":\"2026-09-30T23:59:59+09:00\"}");
        object(typeId, "NEXT_MIDNIGHT", "{\"started_at\":\"2026-11-01T00:00:00+09:00\"}");

        ObjectList october =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        typeId,
                                        null,
                                        "started_at",
                                        "2026-10-01",
                                        "2026-10-31",
                                        null)
                                .get("data");

        assertThat(october.objects())
                .extracting(AgentQueryTools.ObjectSummary::displayName)
                .containsExactlyInAnyOrder("FIRST", "LAST");
        assertThat(october.total()).isEqualTo(2L);
    }

    @Test
    /**
     * Agent는 외부 모델에 값을 넘기므로 모델 용도로 읽는다. 민감 값을 볼 수 있는 역할이 물어도 민감 속성으로는 거르지 못하고, 속성 목록에도 넣지 않는다
     * (384).
     */
    void deniesFilteringByRestrictedPropertyEvenWhenAskerMayViewIt() {
        Map<String, Object> viewer =
                tools(VIEWER).objectList(visible, Map.of("cost", "1200"), null, null, null, null);
        // PLANNER는 이 그룹의 민감 값을 볼 수 있다(아래 type의 허용 행). 그래도 모델 용도라 거르지 못한다
        Map<String, Object> planner =
                tools(PLANNER).objectList(visible, Map.of("cost", "1200"), null, null, null, null);
        Map<String, Object> unfiltered =
                tools(PLANNER).objectList(visible, Map.of(), null, null, null, null);

        assertThat(viewer).containsEntry("resultCategory", "DENIED");
        assertThat(planner).containsEntry("resultCategory", "DENIED");
        assertThat(((ObjectList) unfiltered.get("data")).properties())
                .extracting(PropertyItem::code)
                .doesNotContain("cost");
    }

    @Test
    void hiddenTypeLooksEmptyAndRevealsNoProperties() {
        // 숨긴 유형에는 없는 속성 조건을 넣어도 400이 아니라 0건이다. 속성이 있는지 드러내지 않는다(076).
        Map<String, Object> reply =
                tools(VIEWER).objectList(hidden, Map.of("nothing", "x"), null, null, null, null);

        assertThat(reply).containsEntry("resultCategory", "NO_DATA");
        ObjectList data = (ObjectList) reply.get("data");
        assertThat(data.total()).isZero();
        assertThat(data.objects()).isEmpty();
        assertThat(data.properties()).isEmpty();
    }

    /**
     * 값 목록 조건은 그중 하나와 같은 객체를 한 번에 찾는다. 값 하나만 받을 때는 값마다 따로 부르거나 목록을 넣어 입력을 읽지 못했다(2026-11-14 대화
     * 103). 076 입력에는 값 목록이 없고 Agent 전용 입구로만 간다.
     */
    @Test
    void findsObjectsMatchingAnyOfSeveralValues() {
        ObjectList both =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("status", List.of("가동", "정지")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        ObjectList withMissing =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("status", List.of("정지", "점검")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");
        ObjectList oneInList =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("status", List.of("정지")),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");

        assertThat(both.total()).isEqualTo(26L);
        assertThat(withMissing.total()).isEqualTo(1L);
        assertThat(withMissing.unmatchedValues()).containsEntry("status", "점검");
        assertThat(oneInList.total()).as("값이 하나뿐인 목록은 값 하나 조건과 같다").isEqualTo(1L);
        assertThat(
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("cost", List.of("900", "1200")),
                                        null,
                                        null,
                                        null,
                                        null))
                .as("볼 수 없는 민감 속성은 값 목록이어도 DENIED다")
                .containsEntry("resultCategory", "DENIED");
    }

    /** 값 하나 조건과 값 목록 조건을 함께 건다. 빈 목록은 조건을 빼지 않고 입력 오류다(!475 리뷰). */
    @Test
    void combinesSingleAndSeveralValuesAndRejectsEmptyList() {
        ObjectList mixed =
                (ObjectList)
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of(
                                                "status",
                                                List.of("가동", "정지"),
                                                "started_on",
                                                "2026-10-01"),
                                        null,
                                        null,
                                        null,
                                        null)
                                .get("data");

        assertThat(mixed.objects())
                .extracting(AgentQueryTools.ObjectSummary::displayName)
                .containsExactly("EQ-1");
        assertThat(
                        tools(VIEWER)
                                .objectList(
                                        visible,
                                        Map.of("status", List.of()),
                                        null,
                                        null,
                                        null,
                                        null))
                .hasEntrySatisfying(
                        "error", error -> assertThat((String) error).contains("값을 하나 이상"));
    }

    @Test
    void tellsWhyUnknownPropertyIsRejected() {
        Map<String, Object> reply =
                tools(VIEWER).objectList(visible, Map.of("colour", "red"), null, null, null, null);

        assertThat((String) reply.get("error")).contains("propertyValues[colour]");
    }

    private AgentObjectListTools tools(LoginAccount asker) {
        AgentQuerySession session =
                new AgentQuerySession(
                        asker, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());
        return new AgentObjectListTools(session, objects, models, dataAccess, values);
    }

    /**
     * 시험용 유형. ADMIN·PLANNER에게 그 그룹을 열고, 민감 값은 PLANNER만 본다(V21과 같다). 384부터 ADMIN도 허용 행으로만 보고 그룹 없는
     * 유형은 아무도 못 보므로 행을 넣는다. accessGroup이 null이면 그 두 역할만 허용한 그룹을 따로 만든다.
     */
    private long type(String code, String accessGroup) {
        String group = accessGroup == null ? "admin-only-" + code : accessGroup;
        long id =
                jdbc.sql(
                                """
                                insert into object_type (code, name, access_group)
                                values (:code, :code, :group) returning id
                                """)
                        .param("code", code)
                        .param("group", group)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, code = 'PLANNER' from role where code in ('ADMIN', 'PLANNER')
                        on conflict (role_id, access_group) do nothing
                        """)
                .param("group", group)
                .update();
        return id;
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
                        insert into property_definition
                            (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, :dataType, :sensitivity)
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .update();
    }

    private void object(long type, String name, String properties) {
        jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb))
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .update();
    }
}

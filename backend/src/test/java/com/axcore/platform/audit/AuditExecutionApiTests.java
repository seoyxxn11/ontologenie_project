package com.axcore.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행·감사 이력 조회(API 096·097·098·080)를 실제 보안 설정과 DB로 확인한다. 다른 테스트와 DB를 같이 쓰므로 전체 범위 확인은 이 테스트가 시작한 시각
 * 이후 기록으로 거르고, 본인 범위는 새로 만든 계정으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AuditExecutionApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String AUDIT = "/api/v1/audit/actions";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** 096·080 한 줄의 필드. 입력·결과·막힌 이유는 097, 변경 내역은 098로 본다(S15P31C106-374). */
    private static final List<String> LIST_FIELDS =
            List.of(
                    "id",
                    "requestId",
                    "eventKind",
                    "category",
                    "actionDefinitionId",
                    "actionCode",
                    "actionName",
                    "actorType",
                    "actorId",
                    "actorName",
                    "actorRoleCode",
                    "checkedPermissionCode",
                    "entryPoint",
                    "targetType",
                    "targetId",
                    "targetLabel",
                    "status",
                    "requestedAt",
                    "finishedAt",
                    "changeCount");

    private static final List<String> DETAIL_FIELDS =
            List.of(
                    "id",
                    "status",
                    "input",
                    "result",
                    "blockedReason",
                    "failureReason",
                    "restrictedPropertyCodes");

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired AuditRecorder auditRecorder;

    @Autowired JsonMapper json;

    private Instant startedAt;
    private TestAccount admin;
    private TestAccount plannerA;
    private TestAccount plannerB;
    private long object;
    private long directRun;
    private long agentRun;
    private long blockedRun;
    private long analysisResultOfA;
    private long analysisResultOfB;
    private long systemWithoutTarget;
    private long agentQuery;
    private long recordAction;

    @BeforeEach
    void createExecutions() {
        // DB는 마이크로초까지 저장한다. 여유를 두면 앞선 테스트의 기록이 섞이므로 지금 시각을 그대로 쓴다.
        startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        admin = createAccount("ADMIN");
        plannerA = createAccount("PLANNER");
        plannerB = createAccount("PLANNER");
        String plannerGroup = "AUDIT_" + UUID.randomUUID().toString().substring(0, 8);
        allowGroup("PLANNER", plannerGroup);
        // 384부터 ADMIN도 허용 행으로만 본다.
        allowGroup("ADMIN", plannerGroup);
        // 404: Handler가 막은 차단 이유는 그 Handler가 읽는 유형을 모두 볼 수 있어야 보인다.
        for (String type : List.of("EQUIPMENT", "WORK_ORDER", "WORK_ORDER_OPERATION", "ROUTING")) {
            allowAdminToSeeType(type);
        }
        object = createObject("프레스 3호기", plannerGroup);
        long planDefinition = definitionId("CREATE_REASSIGNMENT_PLAN");

        directRun =
                record(
                        action(plannerA, EntryPoint.WEB, planDefinition, ExecutionStatus.SUCCEEDED)
                                .targetType("object_instance")
                                .targetId(object)
                                .result(Map.of("planId", 3))
                                .changes(
                                        List.of(
                                                ChangeEntry.insert(
                                                        "reassignment_plan",
                                                        Map.of("id", 3),
                                                        Map.of("status", "CREATED")))));
        agentRun =
                record(
                        AuditEntry.builder()
                                .eventKind(EventKind.ACTION)
                                .actor(Actor.agent(login(plannerA)))
                                .entryPoint(EntryPoint.AGENT)
                                .status(ExecutionStatus.SUCCEEDED)
                                .actionDefinitionId(planDefinition)
                                .agentMessageId(createAgentMessage(plannerA.id()))
                                .targetType("object_instance")
                                .targetId(object));
        blockedRun =
                record(
                        action(plannerB, EntryPoint.WEB, planDefinition, ExecutionStatus.BLOCKED)
                                .blockedReason("대체 설비 능력이 부족합니다.")
                                .result(Map.of("blockedBy", "HANDLER"))
                                .targetType("object_instance")
                                .targetId(object));
        analysisResultOfA = record(analysisResult(createAnalysisRun(plannerA.id())));
        analysisResultOfB = record(analysisResult(createAnalysisRun(plannerB.id())));
        systemWithoutTarget =
                record(
                        AuditEntry.builder()
                                .eventKind(EventKind.HOLD_AUTO_RESOLVE)
                                .actor(Actor.system())
                                .entryPoint(EntryPoint.BATCH)
                                .status(ExecutionStatus.SUCCEEDED));
        agentQuery =
                record(
                        AuditEntry.builder()
                                .eventKind(EventKind.AGENT_QUERY)
                                .actor(Actor.agent(login(plannerA)))
                                .entryPoint(EntryPoint.AGENT)
                                .status(ExecutionStatus.SUCCEEDED)
                                .resultCategory(AgentResultCategory.FOUND)
                                .agentMessageId(createAgentMessage(plannerA.id()))
                                .toolCode("OBJECT_SEARCH")
                                .sequenceNo(1)
                                .targetType("object_instance")
                                .targetId(object));
        recordAction =
                record(
                        action(
                                        admin,
                                        EntryPoint.WEB,
                                        definitionId("CREATE_ACCOUNT"),
                                        ExecutionStatus.SUCCEEDED)
                                .targetType("account")
                                .targetId(plannerB.id()));
    }

    // ---------------------------------------------------------------- 096 목록

    @Test
    void adminSeesAllExceptAgentQueryWithNamesAndCategories() throws Exception {
        String base = "$.data[?(@.id == %d)]";
        mockMvc.perform(
                        withSession(get(AUDIT), login(admin, true))
                                .param("from", startedAt.toString())
                                .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.scope").value("ALL"))
                .andExpect(jsonPath("$.data[*].id", hasItem(intValue(systemWithoutTarget))))
                .andExpect(jsonPath("$.data[*].id", hasItem(intValue(analysisResultOfB))))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(intValue(agentQuery)))))
                .andExpect(jsonPath("$.data[*].eventKind", everyItem(not(is("AGENT_QUERY")))))
                .andExpect(jsonPath(base.formatted(directRun) + ".category").value("OPERATION"))
                .andExpect(
                        jsonPath(base.formatted(directRun) + ".actionCode")
                                .value("CREATE_REASSIGNMENT_PLAN"))
                .andExpect(jsonPath(base.formatted(directRun) + ".actionName").value("재배정 계획 생성"))
                .andExpect(jsonPath(base.formatted(directRun) + ".actorName").value("테스트 사용자"))
                .andExpect(jsonPath(base.formatted(directRun) + ".targetLabel").value("프레스 3호기"))
                .andExpect(jsonPath(base.formatted(directRun) + ".changeCount").value(1))
                .andExpect(jsonPath(base.formatted(recordAction) + ".category").value("CHANGE"))
                .andExpect(jsonPath(base.formatted(recordAction) + ".targetLabel").value("테스트 사용자"))
                .andExpect(
                        jsonPath(base.formatted(analysisResultOfA) + ".category").value("ANALYSIS"))
                .andExpect(
                        jsonPath(base.formatted(systemWithoutTarget) + ".category")
                                .value("DATA_JOB"));
    }

    @Test
    void otherRolesSeeOnlyTheirOwnRunsAndRequestedJobResults() throws Exception {
        mockMvc.perform(withSession(get(AUDIT), login(plannerA, true)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.scope").value("MINE"))
                .andExpect(
                        jsonPath(
                                "$.data[*].id",
                                containsInAnyOrder(
                                        intValue(analysisResultOfA),
                                        intValue(agentRun),
                                        intValue(directRun))))
                .andExpect(jsonPath("$.data[0].id").value(analysisResultOfA))
                .andExpect(jsonPath("$.meta.totalElements").value(3));
    }

    @Test
    void forbidsOtherActorForNonAdmin() throws Exception {
        LoginSession session = login(plannerA, true);

        mockMvc.perform(
                        withSession(get(AUDIT), session)
                                .param("actorId", String.valueOf(plannerB.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(
                        withSession(get(AUDIT), session)
                                .param("actorId", String.valueOf(plannerA.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    @Test
    void filtersByConditions() throws Exception {
        LoginSession session = login(admin, true);
        String from = startedAt.toString();

        mockMvc.perform(
                        withSession(get(AUDIT), session)
                                .param("from", from)
                                .param("status", "BLOCKED")
                                .param("targetType", "object_instance")
                                .param("targetId", String.valueOf(object)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(intValue(blockedRun))));
        mockMvc.perform(
                        withSession(get(AUDIT), session)
                                .param("from", from)
                                .param("category", "OPERATION")
                                .param("entryPoint", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(intValue(agentRun))));
        mockMvc.perform(
                        withSession(get(AUDIT), session)
                                .param("from", from)
                                .param("actorId", String.valueOf(admin.id()))
                                .param("actionCode", "CREATE_ACCOUNT")
                                .param("eventKind", "ACTION"))
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(intValue(recordAction))));
        mockMvc.perform(withSession(get(AUDIT), session).param("to", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(hasItem(intValue(directRun)))));
    }

    @Test
    void rejectsInvalidConditions() throws Exception {
        LoginSession session = login(admin, true);

        mockMvc.perform(withSession(get(AUDIT), session).param("eventKind", "AGENT_QUERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("eventKind"));
        mockMvc.perform(withSession(get(AUDIT), session).param("targetId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetId"));
        mockMvc.perform(withSession(get(AUDIT), session).param("category", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withSession(get(AUDIT), session).param("from", "2026-11-05 10:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
    }

    @Test
    void listRowsHaveOnlySummaryFieldsAndCountChanges() throws Exception {
        Map<String, Object> direct =
                rowOf(
                        perform(
                                withSession(get(AUDIT), login(admin, true))
                                        .param("from", startedAt.toString())
                                        .param("size", "100")),
                        directRun);
        assertThat(direct.keySet()).containsExactlyInAnyOrderElementsOf(LIST_FIELDS);
        assertThat(direct).containsEntry("changeCount", 1);

        Map<String, Object> blocked =
                rowOf(
                        perform(
                                withSession(get(AUDIT), login(plannerB, true))
                                        .param("size", "100")),
                        blockedRun);
        assertThat(blocked).containsEntry("changeCount", 0);

        Map<String, Object> objectRow =
                rowOf(
                        perform(
                                withSession(
                                        get("/api/v1/objects/" + object + "/executions"),
                                        login(plannerA, true))),
                        directRun);
        assertThat(objectRow.keySet()).containsExactlyInAnyOrderElementsOf(LIST_FIELDS);
        assertThat(objectRow).containsEntry("changeCount", 1);
    }

    // ---------------------------------------------------------------- 097·098 상세

    @Test
    void adminOpensAnyDetailAndChanges() throws Exception {
        LoginSession session = login(admin, true);

        Map<String, Object> shown = dataOf(perform(withSession(get(detail(directRun)), session)));
        assertThat(shown.keySet()).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
        assertThat(shown)
                .containsEntry("id", intValue(directRun))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("result", Map.of("planId", 3))
                .containsEntry("blockedReason", null)
                .containsEntry("failureReason", null)
                .containsEntry("restrictedPropertyCodes", List.of());
        Map<String, Object> changed =
                dataOf(perform(withSession(get(changes(directRun)), session)));
        assertThat(changed.keySet())
                .containsExactlyInAnyOrder("changes", "restrictedPropertyCodes");
        assertThat(changed).containsEntry("restrictedPropertyCodes", List.of());
        mockMvc.perform(withSession(get(changes(directRun)), session))
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.changes[0].tableName").value("reassignment_plan"))
                .andExpect(jsonPath("$.data.changes[0].rowKey.id").value(3))
                .andExpect(jsonPath("$.data.changes[0].changeKind").value("INSERT"))
                .andExpect(jsonPath("$.data.changes[0].afterData.status").value("CREATED"));

        mockMvc.perform(withSession(get(detail(blockedRun)), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value("대체 설비 능력이 부족합니다."));
        for (long other : List.of(blockedRun, analysisResultOfB, systemWithoutTarget)) {
            mockMvc.perform(withSession(get(detail(other)), session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(other));
            mockMvc.perform(withSession(get(changes(other)), session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.changes", empty()));
        }
        expectHidden(session, agentQuery);
        expectHidden(session, 999_999_999L);
    }

    @Test
    void otherRolesOpenOnlyTheirOwnRunsAndRequestedJobResults() throws Exception {
        LoginSession session = login(plannerA, true);

        for (long own : List.of(directRun, agentRun, analysisResultOfA)) {
            mockMvc.perform(withSession(get(detail(own)), session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(own));
            mockMvc.perform(withSession(get(changes(own)), session)).andExpect(status().isOk());
        }
        mockMvc.perform(withSession(get(changes(directRun)), session))
                .andExpect(jsonPath("$.data.changes[0].afterData.status").value("CREATED"));
        for (long hidden :
                List.of(
                        blockedRun,
                        analysisResultOfB,
                        systemWithoutTarget,
                        recordAction,
                        agentQuery,
                        999_999_999L)) {
            expectHidden(session, hidden);
        }
    }

    @Test
    void failureReasonPrefersNonBlankErrorMessageOverReasonCode() throws Exception {
        String timeoutMessage = "30분 동안 진행 신호가 없어 실패로 처리했습니다.";
        // StaleJobCleaner가 남기는 시간 초과 결과 줄과 같은 모양이다.
        long timedOut =
                record(
                        analysisResult(createAnalysisRun(plannerA.id()))
                                .result(
                                        Map.of(
                                                "errorMessage",
                                                timeoutMessage,
                                                "reason",
                                                "TIMEOUT")));
        long reasonOnly =
                record(
                        analysisResult(createAnalysisRun(plannerA.id()))
                                .result(Map.of("reason", "TIMEOUT")));
        long blankMessage =
                record(
                        analysisResult(createAnalysisRun(plannerA.id()))
                                .result(Map.of("errorMessage", " ", "reason", "TIMEOUT")));
        long noReason =
                record(
                        analysisResult(createAnalysisRun(plannerA.id()))
                                .result(Map.of("errorMessage", "", "detail", "기타")));
        long emptyResult =
                record(analysisResult(createAnalysisRun(plannerA.id())).result(Map.of()));
        LoginSession session = login(plannerA, true);

        mockMvc.perform(withSession(get(detail(timedOut)), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value(timeoutMessage))
                .andExpect(jsonPath("$.data.result.reason").value("TIMEOUT"));
        mockMvc.perform(withSession(get(detail(reasonOnly)), session))
                .andExpect(jsonPath("$.data.failureReason").value("TIMEOUT"));
        mockMvc.perform(withSession(get(detail(blankMessage)), session))
                .andExpect(jsonPath("$.data.failureReason").value("TIMEOUT"));
        for (long withoutReason : List.of(noReason, emptyResult)) {
            Map<String, Object> failed =
                    dataOf(perform(withSession(get(detail(withoutReason)), session)));
            assertThat(failed)
                    .containsEntry("status", "FAILED")
                    .containsEntry("failureReason", null);
        }
        mockMvc.perform(withSession(get(detail(analysisResultOfA)), session))
                .andExpect(jsonPath("$.data.failureReason").value("분석 실패"));
        Map<String, Object> blocked =
                dataOf(perform(withSession(get(detail(blockedRun)), login(plannerB, true))));
        assertThat(blocked).containsEntry("failureReason", null);
    }

    // ---------------------------------------------------------------- 080 객체별

    @Test
    void objectHistoryFollowsRoleScope() throws Exception {
        String path = "/api/v1/objects/" + object + "/executions";

        mockMvc.perform(withSession(get(path), login(admin, true)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].id",
                                containsInAnyOrder(
                                        intValue(blockedRun),
                                        intValue(agentRun),
                                        intValue(directRun))))
                .andExpect(jsonPath("$.data[0].targetLabel").value("프레스 3호기"))
                .andExpect(jsonPath("$.meta.scope").doesNotExist());
        mockMvc.perform(withSession(get(path), login(plannerA, true)))
                .andExpect(
                        jsonPath(
                                "$.data[*].id",
                                containsInAnyOrder(intValue(agentRun), intValue(directRun))));
        mockMvc.perform(withSession(get(path), login(plannerB, true)).param("status", "BLOCKED"))
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(intValue(blockedRun))));
        mockMvc.perform(
                        withSession(
                                get("/api/v1/objects/999999999/executions"), login(admin, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("object_instance"));
    }

    @Test
    void objectHistoryHidesObjectsOutsideRoleScope() throws Exception {
        String adminOnly = "AUDIT_ADMIN_" + UUID.randomUUID().toString().substring(0, 8);
        allowGroup("ADMIN", adminOnly);
        long hidden = createObject("금형 보관대", adminOnly);
        long run =
                record(
                        action(
                                        plannerA,
                                        EntryPoint.WEB,
                                        definitionId("CREATE_REASSIGNMENT_PLAN"),
                                        ExecutionStatus.SUCCEEDED)
                                .targetType("object_instance")
                                .targetId(hidden));
        String path = "/api/v1/objects/" + hidden + "/executions";

        mockMvc.perform(withSession(get(path), login(admin, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", contains(intValue(run))));
        mockMvc.perform(withSession(get(path), login(plannerA, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("object_instance"))
                .andExpect(jsonPath("$.error.details.id").doesNotExist());
    }

    /** 대상 표시 이름도 객체 조회와 같은 규칙으로 가린다. 대표 속성이 민감 속성이면 민감 값을 볼 수 없는 사람에게 #id로 보인다(390). */
    @Test
    void objectHistoryLabelHidesRestrictedDisplayName() throws Exception {
        jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        select object_type_id, 'technician', 'technician', 'STRING', 'RESTRICTED'
                        from object_instance where id = :id
                        """)
                .param("id", object)
                .update();
        jdbcClient
                .sql(
                        """
                        update object_type t set display_property_id = p.id
                        from property_definition p, object_instance o
                        where o.id = :id and t.id = o.object_type_id
                          and p.object_type_id = t.id and p.code = 'technician'
                        """)
                .param("id", object)
                .update();

        mockMvc.perform(
                        withSession(
                                get("/api/v1/objects/" + object + "/executions"),
                                login(plannerA, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetLabel").value("#" + object));
    }

    // ---------------------------------------------------------------- 도우미

    private void expectHidden(LoginSession session, long id) throws Exception {
        for (String path : List.of(detail(id), changes(id))) {
            mockMvc.perform(withSession(get(path), session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.details.resource").value("action_execution"));
        }
    }

    private static String detail(long id) {
        return AUDIT + "/" + id;
    }

    private static String changes(long id) {
        return AUDIT + "/" + id + "/changes";
    }

    private String perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(String body) {
        return (Map<String, Object>) json.readValue(body, JSON_OBJECT).get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowOf(String body, long id) {
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) json.readValue(body, JSON_OBJECT).get("data");
        return rows.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow();
    }

    private AuditEntry.AuditEntryBuilder action(
            TestAccount actor, EntryPoint entryPoint, long definitionId, ExecutionStatus status) {
        return AuditEntry.builder()
                .eventKind(EventKind.ACTION)
                .actor(Actor.user(login(actor)))
                .entryPoint(entryPoint)
                .status(status)
                .actionDefinitionId(definitionId)
                .checkedPermissionCode("PLAN_CREATE");
    }

    private static AuditEntry.AuditEntryBuilder analysisResult(long runId) {
        return AuditEntry.builder()
                .eventKind(EventKind.ANALYSIS_RUN)
                .actor(Actor.system())
                .entryPoint(EntryPoint.INTERNAL)
                .status(ExecutionStatus.FAILED)
                .targetType("analysis_run")
                .targetId(runId)
                .result(Map.of("errorMessage", "분석 실패"));
    }

    private long record(AuditEntry.AuditEntryBuilder entry) {
        return auditRecorder.recordSeparately(entry.build());
    }

    private static int intValue(long id) {
        return Math.toIntExact(id);
    }

    /** 유형이 없으면 만들고, 그룹이 없으면 시험용 그룹을 붙여 ADMIN에게 연다. 다른 시험이 붙인 그룹이면 그 그룹을 연다. */
    private void allowAdminToSeeType(String code) {
        jdbcClient
                .sql(
                        "insert into object_type (code, name) values (:code, :code) on conflict (code) do nothing")
                .param("code", code)
                .update();
        jdbcClient
                .sql(
                        "update object_type set access_group = 'AUDIT_TEST_' || code where code = :code and access_group is null")
                .param("code", code)
                .update();
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group)
                        select r.id, t.access_group from role r, object_type t
                        where r.code = 'ADMIN' and t.code = :code
                        on conflict do nothing
                        """)
                .param("code", code)
                .update();
    }

    private void allowGroup(String roleCode, String accessGroup) {
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group)
                        values ((select id from role where code = :roleCode), :accessGroup)
                        """)
                .param("roleCode", roleCode)
                .param("accessGroup", accessGroup)
                .update();
    }

    private long createObject(String name, String accessGroup) {
        long typeId =
                jdbcClient
                        .sql(
                                """
                                insert into object_type (code, name, access_group)
                                values (:code, '테스트 설비', :accessGroup) returning id
                                """)
                        .param(
                                "code",
                                "EQ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .param("accessGroup", accessGroup)
                        .query(Long.class)
                        .single();
        return jdbcClient
                .sql(
                        "insert into object_instance (object_type_id, display_name) values (:typeId, :name) returning id")
                .param("typeId", typeId)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createAnalysisRun(long requestedBy) {
        return jdbcClient
                .sql(
                        """
                        insert into analysis_run (run_kind, work_key, status, requested_by, finished_at, error_message)
                        values ('INITIAL', :key, 'FAILED', :account, now(), '분석 실패') returning id
                        """)
                .param("key", "audit-" + UUID.randomUUID())
                .param("account", requestedBy)
                .query(Long.class)
                .single();
    }

    private long createAgentMessage(long accountId) {
        long conversationId =
                jdbcClient
                        .sql(
                                "insert into agent_conversation (account_id) values (:id) returning id")
                        .param("id", accountId)
                        .query(Long.class)
                        .single();
        return jdbcClient
                .sql(
                        """
                        insert into agent_message (conversation_id, sender, content)
                        values (:conversationId, 'USER', '질문') returning id
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
    }

    private long definitionId(String code) {
        return jdbcClient
                .sql("select id from action_definition where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private static LoginAccount login(TestAccount account) {
        return new LoginAccount(account.id(), account.loginId(), account.roleCode(), false);
    }

    private LoginSession login(TestAccount account, boolean viaHttp) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                        .formatted(account.loginId(), PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        return new LoginSession(result.getResponse().getCookie("SESSION"));
    }

    private static MockHttpServletRequestBuilder withSession(
            MockHttpServletRequestBuilder request, LoginSession session) {
        return request.cookie(session.session());
    }

    private TestAccount createAccount(String roleCode) {
        String loginId =
                roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long id =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '테스트 사용자', :hash,
                                        (select id from role where code = :roleCode), false)
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("hash", passwordEncoder.encode(PASSWORD))
                        .param("roleCode", roleCode)
                        .query(Long.class)
                        .single();
        return new TestAccount(id, loginId, roleCode);
    }

    private record TestAccount(Long id, String loginId, String roleCode) {}

    private record LoginSession(Cookie session) {}
}

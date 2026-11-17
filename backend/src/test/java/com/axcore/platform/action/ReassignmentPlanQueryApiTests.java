package com.axcore.platform.action;

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

/**
 * 재배정 계획·실행 결과 조회(API 092~095)를 실제 보안 설정과 DB로 확인한다. 계획은 091을 거치지 않고 감사 기록과 계획 행을 직접 넣어 만든다. 다른 테스트와
 * DB를 같이 쓰므로 목록 확인은 이 테스트가 만든 정지 설비로 거른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ReassignmentPlanQueryApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String PLANS = "/api/v1/reassignment-plans";
    private static final String EXECUTIONS = "/api/v1/action-executions";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired AuditRecorder auditRecorder;

    private TestAccount planner;
    private long fromEquipment;
    private long toEquipment;
    private long workOrder;
    private long executionId;
    private long planId;

    @BeforeEach
    void createPlan() {
        planner = createAccount("PLANNER");
        // 384·390: 계획이 가리키는 설비·작업지시·공정 유형을 보는 역할만 계획을 본다.
        for (String type : List.of("EQUIPMENT", "WORK_ORDER", "WORK_ORDER_OPERATION")) {
            allowRolesToSeeType(type);
        }
        long typeId = createObjectType();
        fromEquipment = createObject(typeId, "P103");
        toEquipment = createObject(typeId, "P101");
        workOrder = createObject(typeId, "JB2610-0141");
        executionId =
                auditRecorder.recordSeparately(
                        AuditEntry.builder()
                                .eventKind(EventKind.ACTION)
                                .actor(Actor.user(loginOf(planner)))
                                .entryPoint(EntryPoint.WEB)
                                .status(ExecutionStatus.SUCCEEDED)
                                .actionDefinitionId(definitionId("CREATE_REASSIGNMENT_PLAN"))
                                .checkedPermissionCode("PLAN_CREATE")
                                .targetType("object_instance")
                                .targetId(fromEquipment)
                                .input(Map.of("fromEquipmentId", fromEquipment))
                                .result(Map.of("itemCount", 2))
                                .changes(
                                        List.of(
                                                ChangeEntry.insert(
                                                        "reassignment_plan",
                                                        Map.of("id", 1),
                                                        Map.of("status", "CREATED"))))
                                .build());
        planId =
                jdbcClient
                        .sql(
                                """
                                insert into reassignment_plan (from_equipment_id, selection_basis, data_as_of,
                                                               action_execution_id, created_by)
                                values (:from, '{"reason": "톤수 충족"}'::jsonb,
                                        '2026-10-30T05:00:00Z', :executionId, :createdBy)
                                returning id
                                """)
                        .param("from", fromEquipment)
                        .param("executionId", executionId)
                        .param("createdBy", planner.id())
                        .query(Long.class)
                        .single();
        insertItem("20");
        insertItem("30");
    }

    // ---------------------------------------------------------------- 093 목록

    @Test
    void listsPlansWithItemsForAnyRole() throws Exception {
        LoginSession viewer = login(createAccount("VIEWER"));

        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .cookie(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(planId))
                .andExpect(jsonPath("$.data[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data[0].fromEquipmentId").value(fromEquipment))
                .andExpect(jsonPath("$.data[0].selectionBasis.reason").value("톤수 충족"))
                .andExpect(jsonPath("$.data[0].dataAsOf").value("2026-10-30T05:00:00Z"))
                .andExpect(jsonPath("$.data[0].actionExecutionId").value(executionId))
                .andExpect(jsonPath("$.data[0].createdBy").value(planner.id()))
                .andExpect(jsonPath("$.data[0].items.length()").value(2))
                .andExpect(jsonPath("$.data[0].items[0].operationCode").value("20"))
                .andExpect(jsonPath("$.data[0].items[1].toEquipmentId").value(toEquipment))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void filtersByCreatorAndPages() throws Exception {
        LoginSession session = login(planner);

        mockMvc.perform(
                        get(PLANS)
                                .param("createdBy", String.valueOf(planner.id()))
                                .param("size", "1")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(planId))
                .andExpect(jsonPath("$.meta.size").value(1));
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(toEquipment))
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void rejectsInvalidPagingAndAnonymousRequest() throws Exception {
        LoginSession session = login(planner);

        mockMvc.perform(get(PLANS).param("page", "-1").cookie(session.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(PLANS).param("size", "0").cookie(session.session()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PLANS)).andExpect(status().isUnauthorized());
    }

    @Test
    void filtersByCreatorNameForViewerAndCombinesExistingFilters() throws Exception {
        jdbcClient
                .sql("update account set display_name = :name where id = :id")
                .param("name", "Alpha 손주연")
                .param("id", planner.id())
                .update();
        TestAccount viewer = createAccount("VIEWER");
        LoginSession session = login(viewer);
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "  aLPHa 손  ")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(planId))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "손주연")
                                .param("createdBy", String.valueOf(viewer.id()))
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "없는 이름")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "   ")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(planId));
    }

    @Test
    void treatsCreatorNameWildcardsAsLiteralAndRejectsLongName() throws Exception {
        LoginSession session = login(planner);
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "%")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        jdbcClient
                .sql("update account set display_name = :name where id = :id")
                .param("name", "담당_%\\special")
                .param("id", planner.id())
                .update();
        mockMvc.perform(
                        get(PLANS)
                                .param("fromEquipmentId", String.valueOf(fromEquipment))
                                .param("createdByName", "_%\\")
                                .cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(planId));
        mockMvc.perform(
                        get(PLANS)
                                .param("createdByName", "가".repeat(101))
                                .cookie(session.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- 094·095 상세·항목

    @Test
    void getsPlanAndItems() throws Exception {
        LoginSession viewer = login(createAccount("VIEWER"));

        mockMvc.perform(get(PLANS + "/" + planId).cookie(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(planId))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].workOrderId").value(workOrder))
                .andExpect(jsonPath("$.data.items[0].fromEquipmentId").value(fromEquipment))
                .andExpect(
                        jsonPath("$.data.items[0].sourceSnapshot.operations[0].status").value("중단"))
                .andExpect(
                        jsonPath("$.data.items[0].sourceSnapshot.workOrderNo").value("JB2610-0141"))
                // 404: 정한 스냅샷 필드만 내보낸다.
                .andExpect(jsonPath("$.data.items[0].sourceSnapshot.note").doesNotExist())
                .andExpect(jsonPath("$.meta").doesNotExist());
        mockMvc.perform(get(PLANS + "/" + planId + "/items").cookie(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].operationCode").value("30"));
    }

    @Test
    void returnsNotFoundForUnknownPlan() throws Exception {
        LoginSession session = login(planner);

        mockMvc.perform(get(PLANS + "/999999999").cookie(session.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("reassignment_plan"));
        mockMvc.perform(get(PLANS + "/999999999/items").cookie(session.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(PLANS + "/0").cookie(session.session()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 092 실행 결과

    @Test
    void executorAndAdminSeeExecutionWithChanges() throws Exception {
        mockMvc.perform(get(EXECUTIONS + "/" + executionId).cookie(login(planner).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(executionId))
                .andExpect(jsonPath("$.data.eventKind").value("ACTION"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.actorId").value(planner.id()))
                .andExpect(jsonPath("$.data.targetType").value("object_instance"))
                .andExpect(jsonPath("$.data.targetId").value(fromEquipment))
                .andExpect(jsonPath("$.data.result.itemCount").value(2))
                .andExpect(jsonPath("$.data.entityChanges.length()").value(1))
                .andExpect(jsonPath("$.data.entityChanges[0].tableName").value("reassignment_plan"))
                .andExpect(jsonPath("$.data.entityChanges[0].changeKind").value("INSERT"))
                .andExpect(jsonPath("$.data.entityChanges[0].rowKey.id").value(1))
                .andExpect(jsonPath("$.data.entityChanges[0].afterData.status").value("CREATED"));
        mockMvc.perform(
                        get(EXECUTIONS + "/" + executionId)
                                .cookie(login(createAccount("ADMIN")).session()))
                .andExpect(status().isOk());
    }

    /** 실행기가 막은 기록(GATE)은 차단 이유를 그대로 보여 주고, 차단 주체 표시는 result에서 뺀다(404). */
    @Test
    void showsBlockedExecutionWithReason() throws Exception {
        TestAccount viewer = createAccount("VIEWER");
        long blocked = recordBlocked(viewer, "PLAN_CREATE 권한이 없습니다.", Map.of("blockedBy", "GATE"));

        mockMvc.perform(get(EXECUTIONS + "/" + blocked).cookie(login(viewer).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value("PLAN_CREATE 권한이 없습니다."))
                .andExpect(jsonPath("$.data.result").isEmpty())
                .andExpect(jsonPath("$.data.entityChanges").isEmpty());
    }

    /**
     * 404 이전 기록(차단 주체 없음)은 사람이 실행하면 민감 값이 문구에 들어갔을 수 있다. Handler가 읽는 유형의 민감 값까지 볼 수 있어야 보이고, 아니면 정한
     * 문구로 바꾼다.
     */
    @Test
    void hidesLegacyBlockedReasonFromViewersWithoutRestrictedView() throws Exception {
        TestAccount viewer = createAccount("VIEWER");
        long legacy = recordBlocked(viewer, "작업지시 JB-1의 공정 담당 김철수", null);

        mockMvc.perform(get(EXECUTIONS + "/" + legacy).cookie(login(viewer).session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("차단 이유에 볼 수 없는 객체 정보가 있을 수 있어 가렸습니다."));
    }

    private long recordBlocked(TestAccount actor, String reason, Map<String, Object> result) {
        return auditRecorder.recordSeparately(
                AuditEntry.builder()
                        .eventKind(EventKind.ACTION)
                        .actor(Actor.user(loginOf(actor)))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.BLOCKED)
                        .actionDefinitionId(definitionId("CREATE_REASSIGNMENT_PLAN"))
                        .checkedPermissionCode("PLAN_CREATE")
                        .blockedReason(reason)
                        .result(result)
                        .build());
    }

    @Test
    void forbidsOtherUsersExecution() throws Exception {
        LoginSession otherPlanner = login(createAccount("PLANNER"));

        mockMvc.perform(get(EXECUTIONS + "/" + executionId).cookie(otherPlanner.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void hidesAgentQueryAndUnknownExecution() throws Exception {
        long agentQuery =
                auditRecorder.recordSeparately(
                        AuditEntry.builder()
                                .eventKind(EventKind.AGENT_QUERY)
                                .actor(Actor.agent(loginOf(planner)))
                                .entryPoint(EntryPoint.AGENT)
                                .status(ExecutionStatus.SUCCEEDED)
                                .resultCategory(AgentResultCategory.FOUND)
                                .agentMessageId(createAgentMessage(planner.id()))
                                .toolCode("OBJECT_SEARCH")
                                .sequenceNo(1)
                                .build());
        LoginSession admin = login(createAccount("ADMIN"));

        mockMvc.perform(get(EXECUTIONS + "/" + agentQuery).cookie(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("action_execution"));
        mockMvc.perform(get(EXECUTIONS + "/999999999").cookie(admin.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(EXECUTIONS + "/" + executionId)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 도우미

    private void insertItem(String operationCode) {
        jdbcClient
                .sql(
                        """
                        insert into reassignment_plan_item (plan_id, work_order_id, operation_code,
                                                            from_equipment_id, to_equipment_id, source_snapshot)
                        values (:planId, :workOrder, :operationCode, :from, :to,
                                '{"workOrderNo": "JB2610-0141", "note": "저장만 된 필드",
                                  "operations": [{"objectId": 1, "sequence": "20",
                                                  "status": "중단", "equipmentCode": "P103"}]}'::jsonb)
                        """)
                .param("planId", planId)
                .param("workOrder", workOrder)
                .param("operationCode", operationCode)
                .param("from", fromEquipment)
                .param("to", toEquipment)
                .update();
    }

    private long createObjectType() {
        return jdbcClient
                .sql("insert into object_type (code, name) values (:code, '테스트 설비') returning id")
                .param("code", "EQ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .query(Long.class)
                .single();
    }

    private long createObject(long typeId, String name) {
        return jdbcClient
                .sql(
                        """
                        insert into object_instance (object_type_id, display_name)
                        values (:typeId, :name) returning id
                        """)
                .param("typeId", typeId)
                .param("name", name)
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
                        values (:conversationId, 'USER', 'P103 영향 알려줘') returning id
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

    private static LoginAccount loginOf(TestAccount account) {
        return new LoginAccount(account.id(), account.loginId(), account.roleCode(), false);
    }

    private LoginSession login(TestAccount account) throws Exception {
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

    /** 유형이 없으면 만들고, 그룹이 없으면 시험용 그룹을 붙여 ADMIN·PLANNER·VIEWER에게 연다. 다른 시험이 붙인 그룹이면 그 그룹을 연다. */
    private void allowRolesToSeeType(String code) {
        jdbcClient
                .sql(
                        "insert into object_type (code, name) values (:code, :code) on conflict (code) do nothing")
                .param("code", code)
                .update();
        jdbcClient
                .sql(
                        "update object_type set access_group = 'PLAN_TEST_' || code where code = :code and access_group is null")
                .param("code", code)
                .update();
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group)
                        select r.id, t.access_group from role r, object_type t
                        where r.code in ('ADMIN', 'PLANNER', 'VIEWER') and t.code = :code
                        on conflict do nothing
                        """)
                .param("code", code)
                .update();
    }

    private record TestAccount(Long id, String loginId, String roleCode) {}

    private record LoginSession(Cookie session) {}
}

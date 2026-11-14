package com.axcore.platform.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.handler.ActionContext;
import com.axcore.platform.action.handler.ActionHandler;
import com.axcore.platform.action.handler.ActionPreparation;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.service.RecordedPropertyPaths;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Action 실행 요청(API 091)과 재배정 계획 Handler를 실제 보안 설정과 DB로 확인한다. 객체는 시연 정답 온톨로지의 S4와 같은 모양으로 만든다: 정지 설비
 * P103(PRS 600톤)에 배정된 작업지시의 20·30 공정(중단)을 P101(PRS 400톤)로 옮기면 성공하고, P201(PRS 300톤)로 옮기면 공정 표준 최소
 * 400톤에 못 미쳐 막힌다. 다른 테스트와 DB를 같이 쓰므로 코드에 고유 접미사를 붙인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, ActionExecutionApiTests.FailingHandlerConfig.class})
class ActionExecutionApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String PLAN = "/api/v1/actions/CREATE_REASSIGNMENT_PLAN/executions";
    private static final String UNDEPLOYED = "UNDEPLOYED_HANDLER";
    private static final String HANDLER_NOT_READY_REASON =
            "Handler 구현 전인 Action입니다. 개발자가 Handler를 배포한 뒤 실행할 수 있습니다.";
    private static final String DATA_AS_OF = "2026-10-30T14:00:00+09:00";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired ActionExecutionService actionExecutionService;

    @Autowired JsonMapper json;

    private S4 s4;

    @BeforeEach
    void createS4Objects() {
        s4 = new S4(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // 실행 대상 객체는 실행자가 볼 수 있어야 한다(S15P31C106-205 결정 D4). 계획자는 생산 쪽 유형을 모두 본다(D1).
        // 384부터 ADMIN도 허용 행으로만 보므로 같은 그룹을 연다.
        for (String type : List.of("EQUIPMENT", "WORK_ORDER", "WORK_ORDER_OPERATION", "ROUTING")) {
            allowPlannerToSeeType(type);
        }
    }

    // ---------------------------------------------------------------- 성공

    @Test
    void createsPlanFromS4AndRecordsExecutionWithChanges() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        UUID requestId = UUID.randomUUID();
        String sourceBefore = sourceObjectsOf();

        MvcResult result =
                execute(
                                PLAN,
                                planner,
                                body(
                                        requestId,
                                        s4.p103,
                                        """
                                        [{"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d},
                                         {"workOrderId": %d, "operationCode": "30", "toEquipmentId": %d}]
                                        """
                                                .formatted(s4.order, s4.p101, s4.order, s4.p101)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                        .andExpect(jsonPath("$.data.eventKind").value("ACTION"))
                        .andExpect(jsonPath("$.data.entryPoint").value("WEB"))
                        .andExpect(jsonPath("$.data.actorType").value("USER"))
                        .andExpect(jsonPath("$.data.checkedPermissionCode").value("PLAN_CREATE"))
                        .andExpect(jsonPath("$.data.requestId").value(requestId.toString()))
                        .andExpect(jsonPath("$.data.result.itemCount").value(2))
                        .andExpect(jsonPath("$.data.blockedReason").doesNotExist())
                        .andExpect(jsonPath("$.data.targetType").value("object_instance"))
                        .andExpect(jsonPath("$.data.targetId").value(s4.p103))
                        .andReturn();
        long executionId = longAt(result, "id");
        long planId = longAt(result, "planId");

        Map<String, Object> plan = planOf(planId);
        assertThat(plan)
                .containsEntry("status", "CREATED")
                .containsEntry("from_equipment_id", s4.p103)
                .containsEntry("action_execution_id", executionId)
                .containsEntry("created_by", plannerAccount.id())
                .containsEntry("agent_conversation_id", null);
        assertThat(plan.get("selection_basis").toString()).contains("가동 중");
        List<Map<String, Object>> items = itemsOf(planId);
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("work_order_id", s4.order)
                .containsEntry("operation_code", "20")
                .containsEntry("from_equipment_id", s4.p103)
                .containsEntry("to_equipment_id", s4.p101);
        assertThat(items.get(0).get("source_snapshot").toString())
                .contains(s4.orderNo)
                .contains("진행")
                .contains("중단")
                .contains(s4.p103Code);

        List<Map<String, Object>> changes = changesOf(executionId);
        assertThat(changes).hasSize(3);
        assertThat(changes)
                .extracting(change -> change.get("table_name"))
                .containsExactly(
                        "reassignment_plan", "reassignment_plan_item", "reassignment_plan_item");
        assertThat(changes)
                .allSatisfy(change -> assertThat(change).containsEntry("change_kind", "INSERT"));
        assertThat(changes.get(0).get("row_key").toString()).contains(String.valueOf(planId));
        // 계획만 저장하고 원천에서 온 객체(설비·작업지시·공정)는 바꾸지 않는다(결정 O3).
        assertThat(sourceObjectsOf()).isEqualTo(sourceBefore);
    }

    @Test
    void movesAllRemainingOperationsWhenOperationIsOmitted() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));

        MvcResult result =
                execute(
                                PLAN,
                                planner,
                                body(
                                        UUID.randomUUID(),
                                        s4.p103,
                                        """
                                        [{"workOrderId": %d, "operationCode": null, "toEquipmentId": %d}]
                                        """
                                                .formatted(s4.order, s4.p101)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                        .andExpect(jsonPath("$.data.result.itemCount").value(1))
                        .andReturn();

        Map<String, Object> item = itemsOf(longAt(result, "planId")).get(0);
        assertThat(item).containsEntry("operation_code", null);
        // 정지 설비에 배정된 남은 공정(20·30)만 담고, 다른 설비의 끝난 공정(10)은 담지 않는다.
        assertThat(item.get("source_snapshot").toString())
                .contains("\"20\"")
                .contains("\"30\"")
                .doesNotContain("\"10\"");
    }

    @Test
    void agentExecutionLinksConversationAndRecordsAgentActor() {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());
        long conversationId = conversationOf(messageId);

        ActionExecutionResponse response =
                actionExecutionService.execute(
                        agentCommand(plannerAccount, UUID.randomUUID(), s4.p101, messageId));

        assertThat(response.status().name()).isEqualTo("SUCCEEDED");
        assertThat(response.entryPoint().name()).isEqualTo("AGENT");
        assertThat(response.actorType().name()).isEqualTo("AGENT");
        assertThat(response.agentMessageId()).isEqualTo(messageId);
        assertThat(response.targetType()).isEqualTo("object_instance");
        assertThat(response.targetId()).isEqualTo(s4.p103);
        long planId = ((Number) response.result().get("planId")).longValue();
        assertThat(planOf(planId)).containsEntry("agent_conversation_id", conversationId);
    }

    @Test
    void agentExecutionRecordsBlockedAsAgent() {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());

        ActionExecutionResponse response =
                actionExecutionService.execute(
                        agentCommand(plannerAccount, UUID.randomUUID(), s4.p201, messageId));

        assertThat(response.status().name()).isEqualTo("BLOCKED");
        assertThat(response.actorType().name()).isEqualTo("AGENT");
        assertThat(response.entryPoint().name()).isEqualTo("AGENT");
        assertThat(response.agentMessageId()).isEqualTo(messageId);
        assertThat(response.blockedReason()).contains("300톤");
    }

    @Test
    void agentExecutionRejectsMessageOfAnotherAccount() {
        TestAccount plannerAccount = createAccount("PLANNER");
        long othersMessage = createAgentMessage(createAccount("PLANNER").id());

        assertThatThrownBy(
                        () ->
                                actionExecutionService.execute(
                                        agentCommand(
                                                plannerAccount,
                                                UUID.randomUUID(),
                                                s4.p101,
                                                othersMessage)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(executionsOf(plannerAccount.id())).isZero();
    }

    @Test
    void concurrentSameRequestCreatesOnePlan() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());
        UUID requestId = UUID.randomUUID();
        long plansBefore = countPlans();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ActionExecutionResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return actionExecutionService.execute(
                                            agentCommand(
                                                    plannerAccount, requestId, s4.p101, messageId));
                                }));
            }
            start.countDown();
            ActionExecutionResponse first = futures.get(0).get();
            ActionExecutionResponse second = futures.get(1).get();

            assertThat(first.id()).isEqualTo(second.id());
            assertThat(first.status().name()).isEqualTo("SUCCEEDED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(countPlans()).isEqualTo(plansBefore + 1);
        assertThat(executionsOf(plannerAccount.id())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 업무 조건 차단

    @Test
    void blocksAlternativeBelowMinimumTonnageAndSavesNothing() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        long plansBefore = countPlans();

        MvcResult result =
                execute(PLAN, planner, singleItem(UUID.randomUUID(), s4.p103, "20", s4.p201))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                        .andExpect(jsonPath("$.data.result").isEmpty())
                        .andReturn();

        assertThat(stringAt(result, "blockedReason"))
                .contains(s4.p201Code)
                .contains("300톤")
                .contains("400톤");
        assertThat(countPlans()).isEqualTo(plansBefore);
        assertThat(changesOf(longAt(result, "id"))).isEmpty();
    }

    /**
     * 404: 검사에 쓰는 속성이 민감이어도, 민감 값을 늘 가리는 Agent(모델 용도) 실행이 원래 값으로 검사하고 스냅샷에 원래 값을 남긴다. 예전에는 가린 작업지시
     * 상태(null)로 막혔다.
     */
    @Test
    void agentChecksWithOriginalRestrictedValuesAndKeepsThemInSnapshot() {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());
        List<Runnable> restore =
                List.of(
                        restrict("WORK_ORDER", "status"),
                        restrict("WORK_ORDER_OPERATION", "equipment_code"),
                        restrict("WORK_ORDER_OPERATION", "status"));
        try {
            ActionExecutionResponse response =
                    actionExecutionService.execute(
                            agentCommand(plannerAccount, UUID.randomUUID(), s4.p101, messageId));

            assertThat(response.status().name()).isEqualTo("SUCCEEDED");
            long planId = ((Number) response.result().get("planId")).longValue();
            assertThat(itemsOf(planId).getFirst().get("source_snapshot").toString())
                    .contains("진행")
                    .contains("중단")
                    .contains(s4.p103Code);
        } finally {
            restore.forEach(Runnable::run);
        }
    }

    /**
     * 404: 차단 문구는 저장되어 다른 사람과 모델이 보므로, 웹(사람 용도)으로 실행해도 민감 속성 값을 넣지 않는다. 사용자가 입력한 공정 코드도 그 속성의 값이므로
     * 같게 다룬다. 예전에는 입력한 공정 코드를 그대로 넣었다.
     */
    @Test
    void webBlockedReasonLeavesOutRestrictedValuesEvenWhenTyped() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        Runnable restore = restrict("WORK_ORDER_OPERATION", "op_seq");
        try {
            MvcResult result =
                    execute(PLAN, planner, singleItem(UUID.randomUUID(), s4.p103, "40", s4.p101))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                            .andReturn();

            assertThat(stringAt(result, "blockedReason"))
                    .contains("(민감 값) 공정이 없습니다")
                    .doesNotContain("40 공정");
        } finally {
            restore.run();
        }
    }

    /** 404: 작업지시 번호가 민감이면 문구의 작업지시 이름은 표시 이름이고, 민감한 상태 값은 정한 말로 바꾼다. 검사는 원래 상태(완료)로 막는다. */
    @Test
    void blockedReasonUsesDisplayNameAndHidesRestrictedStatus() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        long order =
                s4.object(
                        s4.typeId("WORK_ORDER"),
                        "표시이름-" + s4.suffix,
                        Map.of(
                                "wo_no",
                                "JB-SECRET-" + s4.suffix,
                                "product_code",
                                "BRK-" + s4.suffix,
                                "status",
                                "완료"));
        List<Runnable> restore =
                List.of(restrict("WORK_ORDER", "wo_no"), restrict("WORK_ORDER", "status"));
        try {
            MvcResult result =
                    execute(
                                    PLAN,
                                    planner,
                                    singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101, order))
                            .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                            .andReturn();

            assertThat(stringAt(result, "blockedReason"))
                    .contains("작업지시 표시이름-" + s4.suffix + "는 (민감 값) 상태라")
                    .doesNotContain("JB-SECRET")
                    .doesNotContain("완료");
        } finally {
            restore.forEach(Runnable::run);
        }
    }

    /** 404: 설비 종류가 민감이면 대체 설비 종류 자리를 정한 말로 바꾸고, 공정 표준이 요구하는 종류(일반 속성)는 그대로 둔다. */
    @Test
    void blockedReasonHidesRestrictedEquipmentKind() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        Runnable restore = restrict("EQUIPMENT", "kind");
        try {
            MvcResult result =
                    execute(PLAN, planner, singleItem(UUID.randomUUID(), s4.p103, "20", s4.cnc))
                            .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                            .andReturn();

            assertThat(stringAt(result, "blockedReason"))
                    .contains("((민감 값))는")
                    .contains("(필요: PRS)")
                    .doesNotContain("CNC");
        } finally {
            restore.run();
        }
    }

    /**
     * 404: 최소 톤수와 설비 능력이 민감이어도 검사를 건너뛰지 않고 막는다. 차단 문구는 저장되어 모델과 다른 사람이 보므로 민감 값 대신 정한 말을 쓴다. 예전에는
     * 가린 최소 톤수(null)를 조건 없음으로 보고 계획을 만들었다.
     */
    @Test
    void agentStillBlocksBelowRestrictedMinimumWithoutNamingTheValues() {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());
        long plansBefore = countPlans();
        List<Runnable> restore =
                List.of(restrict("ROUTING", "min_tonnage"), restrict("EQUIPMENT", "capacity_ton"));
        try {
            ActionExecutionResponse response =
                    actionExecutionService.execute(
                            agentCommand(plannerAccount, UUID.randomUUID(), s4.p201, messageId));

            assertThat(response.status().name()).isEqualTo("BLOCKED");
            assertThat(response.blockedReason())
                    .contains(s4.p201Code)
                    .contains("(민감 값)톤")
                    .doesNotContain("300톤")
                    .doesNotContain("400톤");
            assertThat(countPlans()).isEqualTo(plansBefore);
        } finally {
            restore.forEach(Runnable::run);
        }
    }

    @Test
    void blocksBusinessRuleViolationsInOrder() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));

        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.order, "20", s4.p101),
                "정지 설비(id=%d)".formatted(s4.order));
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.order),
                "대체 설비(id=%d)".formatted(s4.order));
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.p103),
                "정지 설비 " + s4.p103Code + "와 같습니다");
        assertBlocked(
                planner,
                body(
                        UUID.randomUUID(),
                        s4.p103,
                        """
                        [{"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d},
                         {"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d}]
                        """
                                .formatted(s4.order, s4.p101, s4.order, s4.p101)),
                "20 공정이 두 번");
        assertBlocked(
                planner,
                body(
                        UUID.randomUUID(),
                        s4.p103,
                        """
                        [{"workOrderId": %d, "toEquipmentId": %d},
                         {"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d}]
                        """
                                .formatted(s4.order, s4.p101, s4.order, s4.p101)),
                "전체와 개별 공정");
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101, s4.finishedOrder),
                "완료 상태라 옮길 수 없습니다");
        assertBlocked(
                planner, singleItem(UUID.randomUUID(), s4.p103, "99", s4.p101), "99 공정이 없습니다");
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "10", s4.p201),
                "정지 설비 " + s4.p103Code + "에 배정돼 있지 않습니다");
        assertBlocked(
                planner, singleItem(UUID.randomUUID(), s4.p101, null, s4.p201), "배정된 남은 공정이 없습니다");
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.cnc),
                "쓸 수 없는 종류입니다(필요: PRS)");
    }

    @Test
    void blocksFinishedOperationOnStoppedEquipment() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        jdbcClient
                .sql(
                        """
                        update object_instance set properties = properties || '{"status": "완료"}'
                        where id = :id
                        """)
                .param("id", s4.operation30)
                .update();

        assertBlocked(
                planner, singleItem(UUID.randomUUID(), s4.p103, "30", s4.p101), "30 공정은 이미 끝났습니다");
    }

    // ---------------------------------------------------------------- 통합 검증(Jira 242)
    // 실제 091 실행 결과가 결과 조회(092)·계획 조회(094·095)·감사(096~098)·객체별 이력(080)에서 같은 기록으로 보이는지 확인한다.
    // 각 조회 API의 세부 규칙은 ReassignmentPlanQueryApiTests(SQL로 넣은 계획)와
    // AuditExecutionApiTests(AuditRecorder로 남긴 기록)가
    // 따로 확인한다.

    @Test
    void webPlanIsTheSameRecordAcrossResultPlanAuditAndObjectHistory() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        LoginSession admin = login(createAccount("ADMIN"));
        String sourceBefore = sourceObjectsOf();

        MvcResult result =
                execute(
                                PLAN,
                                planner,
                                body(
                                        UUID.randomUUID(),
                                        s4.p103,
                                        """
                                        [{"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d},
                                         {"workOrderId": %d, "operationCode": "30", "toEquipmentId": %d}]
                                        """
                                                .formatted(s4.order, s4.p101, s4.order, s4.p101)))
                        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                        .andReturn();
        long executionId = longAt(result, "id");
        long planId = longAt(result, "planId");

        // 092: 실행자 본인이 결과와 바뀐 행을 본다.
        mockMvc.perform(get("/api/v1/action-executions/" + executionId).cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.planId").value(planId))
                .andExpect(jsonPath("$.data.entityChanges.length()").value(3));
        // 094·095: 계획이 이 실행을 가리키고 항목이 둘이다.
        mockMvc.perform(get("/api/v1/reassignment-plans/" + planId).cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionExecutionId").value(executionId))
                .andExpect(jsonPath("$.data.fromEquipmentId").value(s4.p103))
                .andExpect(jsonPath("$.data.createdBy").value(plannerAccount.id()))
                .andExpect(jsonPath("$.data.agentConversationId").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(2));
        mockMvc.perform(
                        get("/api/v1/reassignment-plans/" + planId + "/items")
                                .cookie(planner.session()))
                .andExpect(jsonPath("$.data[*].toEquipmentId", hasItem((int) s4.p101)));
        // 096~098: 관리자가 같은 기록을 목록·상세·변경 전후로 본다.
        mockMvc.perform(
                        get("/api/v1/audit/actions")
                                .cookie(admin.session())
                                .param("actorId", String.valueOf(plannerAccount.id()))
                                .param("actionCode", "CREATE_REASSIGNMENT_PLAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(executionId))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].entryPoint").value("WEB"))
                .andExpect(jsonPath("$.data[0].checkedPermissionCode").value("PLAN_CREATE"))
                .andExpect(jsonPath("$.data[0].actorType").value("USER"))
                .andExpect(jsonPath("$.data[0].targetId").value(s4.p103))
                .andExpect(jsonPath("$.data[0].changeCount").value(3));
        mockMvc.perform(get("/api/v1/audit/actions/" + executionId).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.planId").value(planId))
                .andExpect(jsonPath("$.data.failureReason").value(nullValue()));
        mockMvc.perform(
                        get("/api/v1/audit/actions/" + executionId + "/changes")
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(3))
                .andExpect(jsonPath("$.data.changes[0].tableName").value("reassignment_plan"))
                .andExpect(jsonPath("$.data.changes[0].rowKey.id").value(planId))
                .andExpect(jsonPath("$.data.changes[0].afterData.status").value("CREATED"));
        // 097·098: 실행자 본인도 같은 기록의 내용과 변경 전후를 연다.
        mockMvc.perform(get("/api/v1/audit/actions/" + executionId).cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.planId").value(planId));
        mockMvc.perform(
                        get("/api/v1/audit/actions/" + executionId + "/changes")
                                .cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(3));
        // 080: 정지 설비의 실행 이력에서 이 계획 실행을 찾는다. 080은 역할의 객체 조회 범위를 따른다.
        allowPlannerToSeeType("EQUIPMENT");
        mockMvc.perform(get("/api/v1/objects/" + s4.p103 + "/executions").cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem((int) executionId)));
        // 원천에서 온 설비·작업지시·공정 객체는 그대로다(결정 O3).
        assertThat(sourceObjectsOf()).isEqualTo(sourceBefore);
    }

    @Test
    void historyHidesRestrictedSnapshotValuesFromRolesWithoutRestrictedView() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        LoginSession admin = login(createAccount("ADMIN"));
        execute(
                        PLAN,
                        planner,
                        body(
                                UUID.randomUUID(),
                                s4.p103,
                                """
                                [{"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d}]
                                """
                                        .formatted(s4.order, s4.p101)))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        long restricted = restrictProperty("WORK_ORDER", "wo_no");
        try {
            // 096 목록은 요약만 주므로 민감 값이 실릴 자리가 없다.
            JsonNode plannerRow = historyRowOf(planner);
            assertHasNoDetailFields(plannerRow);
            assertThat(plannerRow.get("changeCount").asInt()).isEqualTo(2);

            // 097은 입력값 기준이라 가린 것이 없고, 098은 변경 내역 기준으로 가린다.
            long executionId = plannerRow.get("id").asLong();
            JsonNode plannerDetail = auditData("/api/v1/audit/actions/" + executionId, planner);
            assertThat(plannerDetail.get("restrictedPropertyCodes").isEmpty()).isTrue();
            JsonNode plannerChanges =
                    auditData("/api/v1/audit/actions/" + executionId + "/changes", planner);
            assertThat(plannerChanges.get("restrictedPropertyCodes").toString())
                    .isEqualTo("[\"wo_no\"]");
            JsonNode changedSnapshot =
                    itemChangeIn(plannerChanges.get("changes")).get("source_snapshot");
            assertThat(changedSnapshot.has("workOrderNo")).isTrue();
            assertThat(changedSnapshot.get("workOrderNo").isNull()).isTrue();
            assertThat(changedSnapshot.get("workOrderStatus").asString()).isEqualTo("진행");
            // 384: ADMIN도 허용 행대로 가린다(정의·시스템 관리와 값 열람을 나눈다).
            JsonNode adminChanges =
                    auditData("/api/v1/audit/actions/" + executionId + "/changes", admin);
            assertThat(adminChanges.get("restrictedPropertyCodes").toString())
                    .isEqualTo("[\"wo_no\"]");
            assertThat(
                            itemChangeIn(adminChanges.get("changes"))
                                    .get("source_snapshot")
                                    .get("workOrderNo")
                                    .isNull())
                    .isTrue();
        } finally {
            jdbcClient
                    .sql("delete from property_definition where id = :id")
                    .param("id", restricted)
                    .update();
        }
    }

    @Test
    void objectHistoryHidesRestrictedOperationCodeEverywhereItWasRecorded() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        LoginSession admin = login(createAccount("ADMIN"));
        execute(
                        PLAN,
                        planner,
                        body(
                                UUID.randomUUID(),
                                s4.p103,
                                """
                                [{"workOrderId": %d, "operationCode": "20", "toEquipmentId": %d}]
                                """
                                        .formatted(s4.order, s4.p101)))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        allowPlannerToSeeType("EQUIPMENT");
        long restricted = restrictProperty("WORK_ORDER_OPERATION", "op_seq");
        try {
            MvcResult result =
                    mockMvc.perform(
                                    get("/api/v1/objects/" + s4.p103 + "/executions")
                                            .cookie(planner.session()))
                            .andExpect(status().isOk())
                            .andReturn();
            JsonNode rows = json.readTree(result.getResponse().getContentAsString()).get("data");
            assertThat(rows.size()).isEqualTo(1);
            JsonNode row = rows.get(0);

            // 080 목록은 요약만 주므로 민감 값이 실릴 자리가 없다.
            assertHasNoDetailFields(row);

            // 097은 입력값만, 098은 변경 내역만 가리고 각자 가린 코드를 준다.
            long executionId = row.get("id").asLong();
            JsonNode detail = auditData("/api/v1/audit/actions/" + executionId, planner);
            assertThat(detail.get("restrictedPropertyCodes").toString()).isEqualTo("[\"op_seq\"]");
            assertThat(detail.get("input").get("items").get(0).get("operationCode").isNull())
                    .isTrue();
            assertThat(detail.get("input").get("items").get(0).get("workOrderId").asLong())
                    .isEqualTo(s4.order);
            JsonNode changes =
                    auditData("/api/v1/audit/actions/" + executionId + "/changes", planner);
            assertThat(changes.get("restrictedPropertyCodes").toString()).isEqualTo("[\"op_seq\"]");
            JsonNode changedItem = itemChangeIn(changes.get("changes"));
            assertThat(changedItem.get("operation_code").isNull()).isTrue();
            for (JsonNode operation : changedItem.get("source_snapshot").get("operations")) {
                assertThat(operation.get("sequence").isNull()).isTrue();
                assertThat(operation.get("equipmentCode").asString()).isEqualTo(s4.p103Code);
            }

            // 384: ADMIN도 097에서 허용 행대로 가린 입력값을 받는다.
            JsonNode adminDetail = auditData("/api/v1/audit/actions/" + executionId, admin);
            assertThat(adminDetail.get("restrictedPropertyCodes").toString())
                    .isEqualTo("[\"op_seq\"]");
            assertThat(adminDetail.get("input").get("items").get(0).get("operationCode").isNull())
                    .isTrue();

            // 390: 092(Action 결과 조회)도 097·098과 같게 가린다.
            JsonNode actionResult = auditData("/api/v1/action-executions/" + executionId, planner);
            assertThat(actionResult.get("restrictedPropertyCodes").toString())
                    .isEqualTo("[\"op_seq\"]");
            assertThat(actionResult.get("input").get("items").get(0).get("operationCode").isNull())
                    .isTrue();
            JsonNode resultItem = itemChangeIn(actionResult.get("entityChanges"));
            assertThat(resultItem.get("operation_code").isNull()).isTrue();
        } finally {
            jdbcClient
                    .sql("delete from property_definition where id = :id")
                    .param("id", restricted)
                    .update();
        }
    }

    @Test
    void agentPlanIsRecordedAsAgentAndLinkedToConversation() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        long messageId = createAgentMessage(plannerAccount.id());
        long conversationId = conversationOf(messageId);
        LoginSession admin = login(createAccount("ADMIN"));

        ActionExecutionResponse response =
                actionExecutionService.execute(
                        agentCommand(plannerAccount, UUID.randomUUID(), s4.p101, messageId));
        long planId = ((Number) response.result().get("planId")).longValue();

        mockMvc.perform(get("/api/v1/reassignment-plans/" + planId).cookie(admin.session()))
                .andExpect(jsonPath("$.data.actionExecutionId").value(response.id()))
                .andExpect(jsonPath("$.data.agentConversationId").value(conversationId));
        mockMvc.perform(
                        get("/api/v1/audit/actions")
                                .cookie(admin.session())
                                .param("actorId", String.valueOf(plannerAccount.id()))
                                .param("entryPoint", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(response.id()))
                .andExpect(jsonPath("$.data[0].actorType").value("AGENT"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));
    }

    @Test
    void blockedRunsLeaveAuditOnlyAndReplayDoesNotRecordAgain() throws Exception {
        TestAccount viewerAccount = createAccount("VIEWER");
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession admin = login(createAccount("ADMIN"));
        UUID viewerRequest = UUID.randomUUID();
        String sourceBefore = sourceObjectsOf();

        // 권한 없음(관문)과 업무 조건(300톤 설비)으로 각각 막힌다.
        MvcResult byPermission =
                execute(
                                PLAN,
                                login(viewerAccount),
                                singleItem(viewerRequest, s4.p103, "20", s4.p101))
                        .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                        .andReturn();
        MvcResult byBusinessRule =
                execute(
                                PLAN,
                                login(plannerAccount),
                                singleItem(UUID.randomUUID(), s4.p103, "20", s4.p201))
                        .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                        .andReturn();
        long permissionRun = longAt(byPermission, "id");
        long businessRun = longAt(byBusinessRule, "id");
        // 같은 requestId로 다시 보내면 같은 기록을 돌려주고 새로 남기지 않는다.
        execute(PLAN, login(viewerAccount), singleItem(viewerRequest, s4.p103, "20", s4.p101))
                .andExpect(jsonPath("$.data.id").value(permissionRun));

        assertThat(executionsOf(viewerAccount.id())).isOne();
        // 정지 설비 P103은 이 테스트에서만 쓰므로, 막힌 실행이 계획을 하나라도 남겼다면 여기서 보인다.
        assertThat(plansFrom(s4.p103)).isZero();
        assertThat(sourceObjectsOf()).isEqualTo(sourceBefore);
        mockMvc.perform(get("/api/v1/audit/actions/" + permissionRun).cookie(admin.session()))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value("PLAN_CREATE 권한이 없습니다."));
        mockMvc.perform(get("/api/v1/audit/actions/" + businessRun).cookie(admin.session()))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"));
        for (long blocked : List.of(permissionRun, businessRun)) {
            mockMvc.perform(
                            get("/api/v1/audit/actions/" + blocked + "/changes")
                                    .cookie(admin.session()))
                    .andExpect(jsonPath("$.data.changes.length()").value(0));
        }
        mockMvc.perform(
                        get("/api/v1/objects/" + s4.p103 + "/executions")
                                .cookie(admin.session())
                                .param("status", "BLOCKED"))
                .andExpect(jsonPath("$.data[*].id", hasItem((int) permissionRun)))
                .andExpect(jsonPath("$.data[*].id", hasItem((int) businessRun)));
    }

    // ---------------------------------------------------------------- 실행 전 차단

    @Test
    void blocksRoleWithoutPermission() throws Exception {
        TestAccount viewerAccount = createAccount("VIEWER");
        LoginSession viewer = login(viewerAccount);

        execute(PLAN, viewer, singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.actorRoleCode").value("VIEWER"))
                .andExpect(jsonPath("$.data.blockedReason").value("PLAN_CREATE 권한이 없습니다."));
    }

    @Test
    void blocksTargetOutsideRoleScopeLikeMissingObjectAndRecordsIt() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        long hidden = ungroupedObject();
        long missing = Long.MAX_VALUE - 1;

        for (long target : new long[] {hidden, missing}) {
            long before = plansFrom(target);
            execute(PLAN, planner, singleItem(UUID.randomUUID(), target, "20", s4.p101))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                    .andExpect(jsonPath("$.data.targetId").value(target))
                    .andExpect(
                            jsonPath("$.data.blockedReason")
                                    .value("대상 객체(id=%d)를 찾을 수 없습니다.".formatted(target)));
            assertThat(plansFrom(target)).isEqualTo(before);
        }

        // ADMIN은 이 그룹을 허용받아 범위 검사를 지나 Handler의 업무 조건에서 막힌다.
        execute(
                        PLAN,
                        login(createAccount("ADMIN")),
                        singleItem(UUID.randomUUID(), hidden, "20", s4.p101))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("정지 설비(id=%d)를 찾을 수 없거나 설비 객체가 아닙니다.".formatted(hidden)));
    }

    @Test
    void blocksRecordModeInactiveUnconfirmedAndInvalidInput() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        execute(
                        "/api/v1/actions/CREATE_ACCOUNT/executions",
                        admin,
                        "{\"requestId\": \"%s\", \"input\": {}}".formatted(UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("직접 실행할 수 없는 Action입니다. 해당 관리 화면에서 실행해 주세요."));

        String inactive = createPlanDefinition(false, false);
        execute(
                        "/api/v1/actions/%s/executions".formatted(inactive),
                        admin,
                        singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value("사용이 중지된 Action입니다."));

        String confirmed = createPlanDefinition(true, true);
        String path = "/api/v1/actions/%s/executions".formatted(confirmed);
        execute(path, admin, singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("실행 전에 확인이 필요한 Action입니다. 확인한 뒤 다시 요청해 주세요."));
        execute(
                        path,
                        admin,
                        singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101)
                                .replace("\"input\"", "\"confirmed\": true, \"input\""))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        execute(PLAN, admin, body(UUID.randomUUID(), s4.p103, "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("입력이 형식에 맞지 않습니다. input.items: 항목이 1개 이상이어야 합니다."));
    }

    // ---------------------------------------------------------------- requestId

    @Test
    void replaysSameRequestAndRejectsReuseWithDifferentInput() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        UUID requestId = UUID.randomUUID();
        String first = singleItem(requestId, s4.p103, "20", s4.p101);

        long executionId =
                longAt(execute(PLAN, planner, first).andExpect(status().isOk()).andReturn(), "id");
        long plansAfterFirst = countPlans();

        MvcResult again = execute(PLAN, planner, first).andExpect(status().isOk()).andReturn();
        assertThat(longAt(again, "id")).isEqualTo(executionId);
        assertThat(countPlans()).isEqualTo(plansAfterFirst);

        execute(PLAN, planner, singleItem(requestId, s4.p103, "30", s4.p101))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.details.field").value("requestId"));
        execute(PLAN, login(createAccount("PLANNER")), first).andExpect(status().isConflict());
    }

    @Test
    void replaysBlockedRequestWithoutRecordingAgain() throws Exception {
        TestAccount viewerAccount = createAccount("VIEWER");
        LoginSession viewer = login(viewerAccount);
        String request = singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101);

        long first = longAt(execute(PLAN, viewer, request).andReturn(), "id");
        long second = longAt(execute(PLAN, viewer, request).andReturn(), "id");

        assertThat(second).isEqualTo(first);
        assertThat(executionsOf(viewerAccount.id())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 오류 응답

    @Test
    void rejectsMalformedRequests() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));

        execute(PLAN, planner, "{\"input\": {}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        execute(
                        PLAN,
                        planner,
                        "{\"requestId\": \"%s\", \"input\": {}, \"agentMessageId\": 1}"
                                .formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("agentMessageId"));
        execute(
                        PLAN,
                        planner,
                        "{\"requestId\": \"%s\", \"input\": {}, \"targetType\": \"object_instance\"}"
                                .formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetType"));
        execute(
                        "/api/v1/actions/NO_SUCH_ACTION/executions",
                        planner,
                        "{\"requestId\": \"%s\", \"input\": {}}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void acceptsOnlyTargetDerivedFromInput() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        String withTarget = "\"targetType\": \"object_instance\", \"targetId\": %d, \"input\"";

        execute(
                        PLAN,
                        planner,
                        singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101)
                                .replace("\"input\"", withTarget.formatted(s4.p103)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.targetId").value(s4.p103));
        execute(
                        PLAN,
                        planner,
                        singleItem(UUID.randomUUID(), s4.p103, "30", s4.p101)
                                .replace("\"input\"", withTarget.formatted(s4.p201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetId"));
    }

    @Test
    void recordsBlockedWithoutTargetWhenTargetCannotBeDerived() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        String withTarget =
                "\"targetType\": \"object_instance\", \"targetId\": %d, \"input\""
                        .formatted(s4.p103);

        // 입력 형식이 틀려 서버가 대상을 정하지 못하면 보낸 대상은 쓰지 않고 BLOCKED를 남긴다.
        execute(
                        PLAN,
                        admin,
                        "{\"requestId\": \"%s\", \"input\": {\"fromEquipmentId\": \"%d\"}}"
                                .formatted(UUID.randomUUID(), s4.p103)
                                .replace("\"input\"", withTarget))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.targetType").doesNotExist())
                .andExpect(jsonPath("$.data.targetId").doesNotExist());
        // 관리 Action은 Handler가 없어 대상을 정하지 않는다.
        execute(
                        "/api/v1/actions/CREATE_ACCOUNT/executions",
                        admin,
                        "{\"requestId\": \"%s\", \"input\": {}}"
                                .formatted(UUID.randomUUID())
                                .replace("\"input\"", withTarget))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.targetId").doesNotExist());
    }

    @Test
    void blocksWorkOrderWithoutNumberOrProduct() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));
        jdbcClient
                .sql(
                        "update object_instance set properties = properties - 'product_code' where id = :id")
                .param("id", s4.order)
                .update();

        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101),
                "제품 코드(product_code) 속성이 없어");
        jdbcClient
                .sql("update object_instance set properties = properties - 'wo_no' where id = :id")
                .param("id", s4.order)
                .update();
        assertBlocked(
                planner,
                singleItem(UUID.randomUUID(), s4.p103, "20", s4.p101),
                "작업지시 번호(wo_no) 속성이 없어");
    }

    // ---------------------------------------------------------------- 실패(FAILED)

    @Test
    void rollsBackWorkAndRecordsFailedSeparately() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = createDefinition(FailingHandlerConfig.KEY);
        String marker = "FAIL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String request =
                "{\"requestId\": \"%s\", \"input\": {\"marker\": \"%s\"}}"
                        .formatted(UUID.randomUUID(), marker);

        MvcResult result =
                execute("/api/v1/actions/%s/executions".formatted(code), admin, request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("FAILED"))
                        .andExpect(
                                jsonPath("$.data.result.errorMessage")
                                        .value("Action을 실행하지 못했습니다. 관리자에게 문의해 주세요."))
                        .andReturn();
        long executionId = longAt(result, "id");

        // Handler가 저장한 행과 성공 기록·변경 기록은 되돌려지고, FAILED 기록만 남는다.
        assertThat(objectTypeExists(marker)).isFalse();
        assertThat(changesOf(executionId)).isEmpty();
        assertThat(executionsOf(adminAccount.id())).isEqualTo(1);
        long again =
                longAt(
                        execute("/api/v1/actions/%s/executions".formatted(code), admin, request)
                                .andExpect(jsonPath("$.data.status").value("FAILED"))
                                .andReturn(),
                        "id");
        assertThat(again).isEqualTo(executionId);
    }

    // ---------------------------------------------------------------- Handler 구현 전(Jira 287)

    @Test
    void blocksActionWhoseHandlerIsNotDeployedWithoutTarget() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String path = "/api/v1/actions/%s/executions".formatted(createDefinition(UNDEPLOYED));
        long plansBefore = countPlans();
        // 대상은 Handler가 입력으로 정하므로, Handler가 없으면 보낸 대상은 쓰지 않고 대상 없이 남긴다.
        String request =
                ("{\"requestId\": \"%s\", \"targetType\": \"object_instance\", \"targetId\": %d,"
                                + " \"input\": {}}")
                        .formatted(UUID.randomUUID(), s4.p103);

        long executionId =
                longAt(
                        execute(path, admin, request)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                                .andExpect(
                                        jsonPath("$.data.blockedReason")
                                                .value(HANDLER_NOT_READY_REASON))
                                .andExpect(jsonPath("$.data.targetType").doesNotExist())
                                .andExpect(jsonPath("$.data.targetId").doesNotExist())
                                .andExpect(jsonPath("$.data.result").isEmpty())
                                .andReturn(),
                        "id");

        assertThat(countPlans()).isEqualTo(plansBefore);
        // 같은 requestId로 다시 보내도 새로 기록하지 않는다.
        assertThat(longAt(execute(path, admin, request).andReturn(), "id")).isEqualTo(executionId);
        assertThat(executionsOf(adminAccount.id())).isOne();
    }

    @Test
    void checksHandlerAfterInactiveAndBeforePermission() throws Exception {
        String request = "{\"requestId\": \"%s\", \"input\": {}}";
        String inactive = createDefinition(UNDEPLOYED);
        jdbcClient
                .sql("update action_definition set is_active = false where code = :code")
                .param("code", inactive)
                .update();

        execute(
                        "/api/v1/actions/%s/executions".formatted(inactive),
                        login(createAccount("ADMIN")),
                        request.formatted(UUID.randomUUID()))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value("사용이 중지된 Action입니다."));
        // 권한이 없는 사용자에게도 실행할 수 없는 이유(Handler 구현 전)를 먼저 보여 준다.
        execute(
                        "/api/v1/actions/%s/executions".formatted(createDefinition(UNDEPLOYED)),
                        login(createAccount("VIEWER")),
                        request.formatted(UUID.randomUUID()))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blockedReason").value(HANDLER_NOT_READY_REASON));
    }

    @Test
    void checksInputBeforeConfirmation() throws Exception {
        String path = "/api/v1/actions/%s/executions".formatted(createPlanDefinition(true, true));

        execute(path, login(createAccount("ADMIN")), body(UUID.randomUUID(), s4.p103, "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(
                        jsonPath("$.data.blockedReason")
                                .value("입력이 형식에 맞지 않습니다. input.items: 항목이 1개 이상이어야 합니다."));
    }

    @Test
    void rejectsAnonymousRequest() throws Exception {
        mockMvc.perform(
                        post(PLAN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"requestId\": \"%s\", \"input\": {}}"
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- 088·090 inputSchema

    @Test
    void definitionRejectsSchemaKeywordTheValidatorCannotCheck() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        String code = createPlanDefinition(true, false);

        execute(
                        "/api/v1/actions",
                        admin,
                        """
                        {"code": "TEST_%s", "name": "테스트", "description": "테스트",
                         "targetKind": "SYSTEM", "handlerKey": "CREATE_REASSIGNMENT_PLAN",
                         "inputSchema": {"type": "object", "properties": {"code": {"type": "string", "pattern": "^A"}}},
                         "actionKind": "WRITE", "requiresConfirmation": false, "requiredPermission": "PLAN_CREATE"}
                        """
                                .formatted(
                                        UUID.randomUUID().toString().substring(0, 8).toUpperCase()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("inputSchema"));
        mockMvc.perform(
                        patch("/api/v1/actions/" + code)
                                .cookie(admin.session(), admin.csrf())
                                .header("X-XSRF-TOKEN", admin.csrf().getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"inputSchema\": {\"type\": \"object\", \"oneOf\": []}, \"rowVersion\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("inputSchema"));
    }

    // ---------------------------------------------------------------- 도우미

    /** 저장 도중 예외를 내는 Handler. 실행 기록을 남긴 뒤 object_type 행을 넣고 실패한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingHandlerConfig {

        static final String KEY = "TEST_FAILING_HANDLER";

        @Bean
        ActionHandler failingHandler(JdbcClient jdbcClient) {
            return new ActionHandler() {
                @Override
                public String key() {
                    return KEY;
                }

                @Override
                public ActionPreparation prepare(ActionContext context) {
                    String marker = String.valueOf(context.input().get("marker"));
                    return new ActionPreparation.Ready(
                            Map.of("marker", marker),
                            List.of(
                                    ChangeEntry.insert(
                                            "object_type", Map.of("code", marker), Map.of())),
                            executionId -> {
                                jdbcClient
                                        .sql(
                                                "insert into object_type (code, name) values (:code, :code)")
                                        .param("code", marker)
                                        .update();
                                throw new IllegalStateException("테스트 실패");
                            });
                }
            };
        }

        static final String RESULT_KEY = "TEST_RESULT_HANDLER";

        /** 결과에 설비의 민감 속성 값(result_manager)과 건수를 담는 Handler(404 결과 가림 시험). */
        @Bean
        ActionHandler resultHandler() {
            return new ActionHandler() {
                @Override
                public String key() {
                    return RESULT_KEY;
                }

                @Override
                public ActionPreparation prepare(ActionContext context) {
                    return new ActionPreparation.Ready(
                            Map.of(
                                    "manager",
                                    String.valueOf(context.input().get("manager")),
                                    "count",
                                    1),
                            List.of(),
                            executionId -> {});
                }
            };
        }

        @Bean
        RecordedPropertyPaths resultHandlerPaths() {
            return () ->
                    List.of(
                            Path.result(
                                    RESULT_KEY, List.of("manager"), "EQUIPMENT", "result_manager"),
                            Path.plainResult(RESULT_KEY, List.of("count")));
        }
    }

    /**
     * 404: 091 응답의 result도 허용 목록으로 가린다. 민감 값을 볼 수 있는 사람의 웹 실행은 값을 보여 주고 열람 기록(ACTION_EXECUTE)을 남기며,
     * 같은 requestId로 다시 받아도 새 응답이라 다시 남긴다. Agent 실행은 모델 용도라 가린다.
     */
    @Test
    void masksExecuteResultByPurposeAndRecordsRestrictedView() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        String code = createDefinition(FailingHandlerConfig.RESULT_KEY);
        String path = "/api/v1/actions/%s/executions".formatted(code);
        String body =
                "{\"requestId\": \"%s\", \"input\": {\"manager\": \"T-KIM\"}}"
                        .formatted(UUID.randomUUID());
        Runnable restore = restrict("EQUIPMENT", "result_manager");
        boolean before = plannerSeesRestrictedEquipment();
        setPlannerSeesRestrictedEquipment(true);
        try {
            execute(path, planner, body)
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.result.manager").value("T-KIM"))
                    .andExpect(jsonPath("$.data.result.count").value(1));
            assertThat(restrictedViewsOf(plannerAccount.id())).isEqualTo(1);
            execute(path, planner, body)
                    .andExpect(jsonPath("$.data.result.manager").value("T-KIM"));
            assertThat(restrictedViewsOf(plannerAccount.id())).isEqualTo(2);

            ActionExecutionResponse agent =
                    actionExecutionService.execute(
                            new ActionExecutionCommand(
                                    new LoginAccount(
                                            plannerAccount.id(),
                                            plannerAccount.loginId(),
                                            "PLANNER",
                                            false),
                                    code,
                                    UUID.randomUUID(),
                                    null,
                                    null,
                                    Map.of("manager", "T-KIM"),
                                    false,
                                    EntryPoint.AGENT,
                                    createAgentMessage(plannerAccount.id())));
            assertThat(agent.result()).containsEntry("manager", null).containsEntry("count", 1);
            assertThat(restrictedViewsOf(plannerAccount.id())).isEqualTo(2);
        } finally {
            setPlannerSeesRestrictedEquipment(before);
            restore.run();
        }
    }

    private long restrictedViewsOf(long accountId) {
        return jdbcClient
                .sql(
                        """
                        select count(*) from action_execution
                        where event_kind = 'RESTRICTED_VIEW' and actor_id = :id
                          and input ->> 'view' = 'ACTION_EXECUTE'
                        """)
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private boolean plannerSeesRestrictedEquipment() {
        return jdbcClient
                .sql(
                        """
                        select coalesce(bool_or(s.can_view_restricted), false) from role_data_scope s
                        join role r on r.id = s.role_id
                        join object_type t on t.access_group = s.access_group
                        where r.code = 'PLANNER' and t.code = 'EQUIPMENT'
                        """)
                .query(Boolean.class)
                .single();
    }

    private void setPlannerSeesRestrictedEquipment(boolean value) {
        jdbcClient
                .sql(
                        """
                        update role_data_scope s set can_view_restricted = :value
                        from role r, object_type t
                        where r.id = s.role_id and r.code = 'PLANNER'
                          and t.access_group = s.access_group and t.code = 'EQUIPMENT'
                        """)
                .param("value", value)
                .update();
    }

    private ActionExecutionCommand agentCommand(
            TestAccount account, UUID requestId, long toEquipmentId, long messageId) {
        return new ActionExecutionCommand(
                new LoginAccount(account.id(), account.loginId(), "PLANNER", false),
                "CREATE_REASSIGNMENT_PLAN",
                requestId,
                null,
                null,
                Map.of(
                        "fromEquipmentId",
                        s4.p103,
                        "items",
                        List.of(
                                Map.of(
                                        "workOrderId",
                                        s4.order,
                                        "operationCode",
                                        "20",
                                        "toEquipmentId",
                                        toEquipmentId)),
                        "dataAsOf",
                        DATA_AS_OF),
                false,
                EntryPoint.AGENT,
                messageId);
    }

    /** 지정한 Handler 키를 쓰는 SYSTEM 대상 정의. 다른 테스트와 겹치지 않게 DB에 직접 넣는다. */
    private String createDefinition(String handlerKey) {
        String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcClient
                .sql(
                        """
                        insert into action_definition (code, name, description, target_kind, action_mode,
                            handler_key, input_schema, action_kind, requires_confirmation, required_permission)
                        values (:code, '테스트', '테스트', 'SYSTEM', 'EXECUTE', :handlerKey,
                                '{"type": "object"}'::jsonb, 'WRITE', false, 'PLAN_CREATE')
                        """)
                .param("code", code)
                .param("handlerKey", handlerKey)
                .update();
        return code;
    }

    /** S4 객체의 현재 속성·상태. 실행 전후를 비교한다. */
    private String sourceObjectsOf() {
        return jdbcClient
                .sql(
                        """
                        select string_agg(id || ':' || status || ':' || properties::text, '|' order by id)
                        from object_instance where display_name like :pattern
                        """)
                .param("pattern", "%" + s4.suffix + "%")
                .query(String.class)
                .single();
    }

    private boolean objectTypeExists(String code) {
        return jdbcClient
                .sql("select exists (select 1 from object_type where code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    private void assertBlocked(LoginSession session, String body, String reasonPart)
            throws Exception {
        MvcResult result =
                execute(PLAN, session, body)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                        .andReturn();
        assertThat(stringAt(result, "blockedReason")).contains(reasonPart);
    }

    private String singleItem(UUID requestId, long from, String operation, long to) {
        return singleItem(requestId, from, operation, to, s4.order);
    }

    private static String singleItem(
            UUID requestId, long from, String operation, long to, long order) {
        return body(
                requestId,
                from,
                "[{\"workOrderId\": %d, \"operationCode\": %s, \"toEquipmentId\": %d}]"
                        .formatted(
                                order, operation == null ? "null" : "\"" + operation + "\"", to));
    }

    private static String body(UUID requestId, long from, String items) {
        return """
                {"requestId": "%s",
                 "input": {"fromEquipmentId": %d, "items": %s,
                           "selectionBasis": {"reason": "가동 중이고 공정 표준 최소 톤수 이상"},
                           "dataAsOf": "%s"}}
                """
                .formatted(requestId, from, items, DATA_AS_OF);
    }

    private ResultActions execute(String path, LoginSession session, String body) throws Exception {
        return mockMvc.perform(
                post(path)
                        .cookie(session.session(), session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
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
        return new LoginSession(
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
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
        return new TestAccount(id, loginId);
    }

    /** 재배정 계획 Handler를 쓰는 별도 정의. 초기 정의를 바꾸면 다른 테스트에 영향을 주므로 따로 만든다. */
    private String createPlanDefinition(boolean active, boolean requiresConfirmation) {
        String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcClient
                .sql(
                        """
                        insert into action_definition (code, name, description, target_kind, action_mode,
                            handler_key, input_schema, action_kind, requires_confirmation,
                            required_permission, is_active)
                        select :code, '테스트 재배정', '테스트', target_kind, action_mode, handler_key,
                               input_schema, action_kind, :confirm, required_permission, :active
                        from action_definition where code = 'CREATE_REASSIGNMENT_PLAN'
                        """)
                .param("code", code)
                .param("confirm", requiresConfirmation)
                .param("active", active)
                .update();
        return code;
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
                        values (:conversationId, 'USER', 'P103 정지 작업을 옮겨줘') returning id
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
    }

    private long conversationOf(long messageId) {
        return jdbcClient
                .sql("select conversation_id from agent_message where id = :id")
                .param("id", messageId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> planOf(long planId) {
        return jdbcClient
                .sql(
                        """
                        select status, from_equipment_id, selection_basis::text as selection_basis,
                               action_execution_id, agent_conversation_id, created_by
                        from reassignment_plan where id = :id
                        """)
                .param("id", planId)
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> itemsOf(long planId) {
        return jdbcClient
                .sql(
                        """
                        select work_order_id, operation_code, from_equipment_id, to_equipment_id,
                               source_snapshot::text as source_snapshot
                        from reassignment_plan_item where plan_id = :id order by id
                        """)
                .param("id", planId)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> changesOf(long executionId) {
        return jdbcClient
                .sql(
                        """
                        select table_name, row_key::text as row_key, change_kind
                        from entity_change where action_execution_id = :id order by id
                        """)
                .param("id", executionId)
                .query()
                .listOfRows();
    }

    private long countPlans() {
        return jdbcClient.sql("select count(*) from reassignment_plan").query(Long.class).single();
    }

    private long executionsOf(long actorId) {
        return jdbcClient
                .sql("select count(*) from action_execution where actor_id = :id")
                .param("id", actorId)
                .query(Long.class)
                .single();
    }

    /** ADMIN에게만 허용한 그룹의 유형 객체. 계획자에게는 보이지 않는다. */
    private long ungroupedObject() {
        String code = "HIDDEN_" + UUID.randomUUID().toString().substring(0, 8);
        long typeId =
                jdbcClient
                        .sql(
                                "insert into object_type (code, name, access_group) values (:code, '숨은 유형', :code) returning id")
                        .param("code", code)
                        .query(Long.class)
                        .single();
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group)
                        select id, :group from role where code = 'ADMIN'
                        """)
                .param("group", code)
                .update();
        return jdbcClient
                .sql(
                        "insert into object_instance (object_type_id, display_name) values (:type, '숨은 설비') returning id")
                .param("type", typeId)
                .query(Long.class)
                .single();
    }

    private long plansFrom(long fromEquipmentId) {
        return jdbcClient
                .sql("select count(*) from reassignment_plan where from_equipment_id = :id")
                .param("id", fromEquipmentId)
                .query(Long.class)
                .single();
    }

    /** 속성을 민감으로 지정하고 되돌리는 함수를 돌려준다. 없던 속성이면 만들었다가 지운다. */
    private Runnable restrict(String typeCode, String propertyCode) {
        String before =
                jdbcClient
                        .sql(
                                """
                                select p.sensitivity from property_definition p
                                join object_type t on t.id = p.object_type_id
                                where t.code = :type and p.code = :property
                                """)
                        .param("type", typeCode)
                        .param("property", propertyCode)
                        .query(String.class)
                        .optional()
                        .orElse(null);
        if (before == null) {
            long id = restrictProperty(typeCode, propertyCode);
            return () ->
                    jdbcClient
                            .sql("delete from property_definition where id = :id")
                            .param("id", id)
                            .update();
        }
        setSensitivity(typeCode, propertyCode, "RESTRICTED");
        return () -> setSensitivity(typeCode, propertyCode, before);
    }

    private void setSensitivity(String typeCode, String propertyCode, String sensitivity) {
        jdbcClient
                .sql(
                        """
                        update property_definition p set sensitivity = :sensitivity
                        from object_type t
                        where t.id = p.object_type_id and t.code = :type and p.code = :property
                        """)
                .param("sensitivity", sensitivity)
                .param("type", typeCode)
                .param("property", propertyCode)
                .update();
    }

    private long restrictProperty(String typeCode, String propertyCode) {
        return jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        select id, :property, :property, 'STRING', 'RESTRICTED' from object_type where code = :type
                        returning id
                        """)
                .param("type", typeCode)
                .param("property", propertyCode)
                .query(Long.class)
                .single();
    }

    /** 096에서 정지 설비를 대상으로 한 이 사용자의 실행 한 줄. */
    private JsonNode historyRowOf(LoginSession session) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/audit/actions")
                                        .cookie(session.session())
                                        .param("targetType", "object_instance")
                                        .param("targetId", String.valueOf(s4.p103))
                                        .param("actionCode", "CREATE_REASSIGNMENT_PLAN"))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode rows = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(rows.size()).isEqualTo(1);
        return rows.get(0);
    }

    /** 096·080 한 줄에는 097·098로 옮긴 상세 필드가 없다(S15P31C106-374). */
    private static void assertHasNoDetailFields(JsonNode row) {
        for (String field :
                List.of(
                        "input",
                        "result",
                        "blockedReason",
                        "entityChanges",
                        "restrictedPropertyCodes")) {
            assertThat(row.has(field)).as(field).isFalse();
        }
    }

    private static JsonNode itemChangeIn(JsonNode changes) {
        for (JsonNode change : changes) {
            if ("reassignment_plan_item".equals(change.get("tableName").asString())) {
                return change.get("afterData");
            }
        }
        throw new AssertionError("재배정 항목 변경 기록이 없습니다: " + changes);
    }

    /** 097·098 응답의 data. */
    private JsonNode auditData(String path, LoginSession session) throws Exception {
        MvcResult result =
                mockMvc.perform(get(path).cookie(session.session()))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void allowPlannerToSeeType(String typeCode) {
        jdbcClient
                .sql(
                        """
                        update object_type set access_group = 'ACTION_TEST_' || code
                        where code = :code and access_group is null
                        """)
                .param("code", typeCode)
                .update();
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group)
                        select r.id, t.access_group from role r, object_type t
                        where r.code in ('PLANNER', 'ADMIN') and t.code = :code
                        on conflict do nothing
                        """)
                .param("code", typeCode)
                .update();
    }

    private static long longAt(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll("(?s).*?\"" + field + "\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private static String stringAt(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.replaceAll("(?s).*?\"" + field + "\"\\s*:\\s*\"([^\"]*)\".*", "$1");
    }

    /** S4와 같은 모양의 객체. 설비 코드·작업지시 번호·제품 코드에 접미사를 붙여 다른 테스트의 객체와 섞이지 않게 한다. */
    private final class S4 {

        final String suffix;
        final String p101Code;
        final String p103Code;
        final String p201Code;
        final String orderNo;
        final long p101;
        final long p103;
        final long p201;
        final long cnc;
        final long order;
        final long finishedOrder;
        final long operation30;

        S4(String suffix) {
            long equipment = typeId("EQUIPMENT");
            long workOrder = typeId("WORK_ORDER");
            long operation = typeId("WORK_ORDER_OPERATION");
            long routing = typeId("ROUTING");
            this.suffix = suffix;
            String product = "BRK-" + suffix;
            p101Code = "P101-" + suffix;
            p103Code = "P103-" + suffix;
            p201Code = "P201-" + suffix;
            orderNo = "JB-" + suffix;
            String finishedNo = "JB-DONE-" + suffix;

            p101 =
                    object(
                            equipment,
                            p101Code,
                            Map.of(
                                    "code",
                                    p101Code,
                                    "kind",
                                    "PRS",
                                    "capacity_ton",
                                    400,
                                    "status",
                                    "가동"));
            p103 =
                    object(
                            equipment,
                            p103Code,
                            Map.of(
                                    "code",
                                    p103Code,
                                    "kind",
                                    "PRS",
                                    "capacity_ton",
                                    600,
                                    "status",
                                    "정지"));
            p201 =
                    object(
                            equipment,
                            p201Code,
                            Map.of(
                                    "code",
                                    p201Code,
                                    "kind",
                                    "PRS",
                                    "capacity_ton",
                                    300,
                                    "status",
                                    "가동"));
            cnc =
                    object(
                            equipment,
                            "C301-" + suffix,
                            Map.of(
                                    "code",
                                    "C301-" + suffix,
                                    "kind",
                                    "CNC",
                                    "capacity_ton",
                                    900,
                                    "status",
                                    "가동"));
            order =
                    object(
                            workOrder,
                            orderNo,
                            Map.of("wo_no", orderNo, "product_code", product, "status", "진행"));
            finishedOrder =
                    object(
                            workOrder,
                            finishedNo,
                            Map.of("wo_no", finishedNo, "product_code", product, "status", "완료"));
            object(
                    operation,
                    orderNo + "-10",
                    Map.of(
                            "wo_no",
                            orderNo,
                            "op_seq",
                            10,
                            "equipment_code",
                            p101Code,
                            "status",
                            "완료",
                            "remaining_qty",
                            0));
            object(
                    operation,
                    orderNo + "-20",
                    Map.of(
                            "wo_no",
                            orderNo,
                            "op_seq",
                            20,
                            "equipment_code",
                            p103Code,
                            "status",
                            "중단",
                            "remaining_qty",
                            3200));
            operation30 =
                    object(
                            operation,
                            orderNo + "-30",
                            Map.of(
                                    "wo_no",
                                    orderNo,
                                    "op_seq",
                                    30,
                                    "equipment_code",
                                    p103Code,
                                    "status",
                                    "중단",
                                    "remaining_qty",
                                    3200));
            object(
                    operation,
                    finishedNo + "-20",
                    Map.of(
                            "wo_no",
                            finishedNo,
                            "op_seq",
                            20,
                            "equipment_code",
                            p103Code,
                            "status",
                            "완료",
                            "remaining_qty",
                            0));
            object(
                    routing,
                    product + "-20",
                    Map.of(
                            "product_code",
                            product,
                            "op_seq",
                            20,
                            "equipment_kind",
                            "PRS",
                            "min_tonnage",
                            400));
            object(
                    routing,
                    product + "-30",
                    Map.of(
                            "product_code",
                            product,
                            "op_seq",
                            30,
                            "equipment_kind",
                            "PRS",
                            "min_tonnage",
                            "400.0"));
        }

        private long typeId(String code) {
            jdbcClient
                    .sql(
                            """
                            insert into object_type (code, name) values (:code, :code)
                            on conflict (code) do nothing
                            """)
                    .param("code", code)
                    .update();
            return jdbcClient
                    .sql("select id from object_type where code = :code")
                    .param("code", code)
                    .query(Long.class)
                    .single();
        }

        private long object(long typeId, String displayName, Map<String, Object> properties) {
            String json =
                    properties.entrySet().stream()
                            .map(
                                    entry ->
                                            "\"%s\": %s"
                                                    .formatted(
                                                            entry.getKey(),
                                                            entry.getValue() instanceof Number
                                                                    ? entry.getValue()
                                                                    : "\""
                                                                            + entry.getValue()
                                                                            + "\""))
                            .collect(Collectors.joining(", ", "{", "}"));
            return jdbcClient
                    .sql(
                            """
                            insert into object_instance (object_type_id, display_name, properties)
                            values (:typeId, :name, cast(:properties as jsonb)) returning id
                            """)
                    .param("typeId", typeId)
                    .param("name", displayName)
                    .param("properties", json)
                    .query(Long.class)
                    .single();
        }
    }

    private record TestAccount(Long id, String loginId) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

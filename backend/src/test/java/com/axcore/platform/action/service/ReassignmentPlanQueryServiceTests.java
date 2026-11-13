package com.axcore.platform.action.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import com.axcore.platform.action.repository.ReassignmentPlanRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 재배정 계획 조회(API 093~095)의 JPA 조회를 실제 DB로 확인한다. 계획 수가 늘어도 SQL 수가 늘지 않는지(N+1)는 Hibernate Statistics로
 * 센다. 계획은 091과 같은 저장 경로({@link ReassignmentPlanRepository})로 넣는다. 다른 테스트와 DB를 같이 쓰므로 이 테스트가 만든 정지
 * 설비로 거른다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ReassignmentPlanQueryServiceTests {

    private static final LoginAccount ADMIN = new LoginAccount(1L, "tester", "ADMIN", false);

    /** 권한·범위가 없는 미구성 역할(384). */
    private static final LoginAccount UNCONFIGURED =
            new LoginAccount(2L, "tester", "MAINTENANCE", false);

    @Autowired ReassignmentPlanQueryService service;

    @Autowired ReassignmentPlanRepository planRepository;

    @Autowired AuditRecorder auditRecorder;

    @Autowired JdbcClient jdbcClient;

    @Autowired EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private long plannerId;
    private long fromEquipment;
    private long toEquipment;
    private long workOrder;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        plannerId = createAccount();
        long typeId = createObjectType();
        fromEquipment = createObject(typeId, "P103");
        toEquipment = createObject(typeId, "P101");
        workOrder = createObject(typeId, "JB2610-0141");
        // 384·390: 계획이 가리키는 설비·작업지시·공정 유형을 보는 사람만 계획을 본다. 시험 계정(ADMIN)에 그 유형들을 열어 준다.
        for (String type : List.of("EQUIPMENT", "WORK_ORDER", "WORK_ORDER_OPERATION")) {
            allowAdminToSeeType(type);
        }
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void loadsItemsOfWholePageWithoutQueryPerPlan() {
        savePlan(List.of("10", "20"));
        long withOnePlan = statementsToSearch();

        savePlan(List.of("10", "20"));
        savePlan(List.of("10", "20"));
        savePlan(List.of("10", "20"));
        long withFourPlans = statementsToSearch();

        Page<ReassignmentPlanResponse> page =
                service.search(ADMIN, fromEquipment, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(4);
        assertThat(page.getContent())
                .allSatisfy(
                        plan ->
                                assertThat(plan.items())
                                        .extracting(ReassignmentPlanItemResponse::operationCode)
                                        .containsExactly("10", "20"));
        // 계획 한 페이지 + 항목 IN 조회(+ 필요하면 건수). 계획마다 항목을 읽으면 1개와 4개일 때 수가 달라진다.
        assertThat(withOnePlan).isBetween(2L, 3L);
        assertThat(withFourPlans).isEqualTo(withOnePlan);
    }

    @Test
    void sortsNewestFirstAndKeepsStoredValues() {
        long oldest = insertPlan("2026-10-30T01:00:00Z");
        long newerLowId = insertPlan("2026-10-30T02:00:00Z");
        long newerHighId = insertPlan("2026-10-30T02:00:00Z");
        insertItem(newerHighId, null);

        Page<ReassignmentPlanResponse> page =
                service.search(ADMIN, fromEquipment, plannerId, PageRequest.of(0, 2));

        assertThat(page.getContent())
                .extracting(ReassignmentPlanResponse::id)
                .containsExactly(newerHighId, newerLowId);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(
                        service.search(ADMIN, fromEquipment, plannerId, PageRequest.of(1, 2))
                                .getContent())
                .extracting(ReassignmentPlanResponse::id)
                .containsExactly(oldest);

        ReassignmentPlanResponse plan = service.get(ADMIN, newerHighId);
        assertThat(plan.status()).isEqualTo("CREATED");
        assertThat(plan.selectionBasis())
                .isEqualTo(
                        Map.of(
                                "reason",
                                "톤수 충족",
                                "score",
                                0.85,
                                "candidates",
                                List.of(Map.of("code", "P101", "capacity", 300))));
        assertThat(plan.dataAsOf()).isEqualTo(Instant.parse("2026-10-30T00:30:00Z"));
        assertThat(plan.createdAt()).isEqualTo(Instant.parse("2026-10-30T02:00:00Z"));
        assertThat(plan.agentConversationId()).isNull();
        assertThat(plan.items()).hasSize(1);
        ReassignmentPlanItemResponse item = plan.items().getFirst();
        assertThat(item.operationCode()).isNull();
        assertThat(item.workOrderId()).isEqualTo(workOrder);
        assertThat(item.fromEquipmentId()).isEqualTo(fromEquipment);
        assertThat(item.toEquipmentId()).isEqualTo(toEquipment);
        assertThat(item.sourceSnapshot()).isEqualTo(Map.of("status", "중단"));
        assertThat(service.items(ADMIN, newerLowId)).isEmpty();
    }

    // ---------------------------------------------------------------- 도우미

    /** 조회 하나에 Hibernate가 준비한 SQL 수. */
    private long statementsToSearch() {
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        service.search(ADMIN, fromEquipment, null, PageRequest.of(0, 20));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    /** 091과 같은 방식으로 시퀀스에서 ID를 먼저 받고 넣는다. */
    private void savePlan(List<String> operationCodes) {
        long planId = planRepository.nextPlanId();
        List<Long> itemIds = planRepository.nextItemIds(operationCodes.size());
        planRepository.insertPlan(
                planId,
                fromEquipment,
                Map.of("reason", "톤수 충족"),
                Instant.parse("2026-10-30T05:00:00Z"),
                recordExecution(),
                null,
                plannerId);
        for (int i = 0; i < operationCodes.size(); i++) {
            planRepository.insertItem(
                    itemIds.get(i),
                    planId,
                    workOrder,
                    operationCodes.get(i),
                    fromEquipment,
                    toEquipment,
                    Map.of("status", "중단"));
        }
    }

    private long insertPlan(String createdAt) {
        return jdbcClient
                .sql(
                        """
                        insert into reassignment_plan (from_equipment_id, selection_basis, data_as_of,
                                                       action_execution_id, created_by, created_at)
                        values (:from,
                                '{"reason": "톤수 충족", "score": 0.85,
                                  "candidates": [{"code": "P101", "capacity": 300}]}'::jsonb,
                                '2026-10-30T00:30:00Z', :executionId, :createdBy, :createdAt)
                        returning id
                        """)
                .param("from", fromEquipment)
                .param("executionId", recordExecution())
                .param("createdBy", plannerId)
                .param("createdAt", Timestamp.from(Instant.parse(createdAt)))
                .query(Long.class)
                .single();
    }

    private void insertItem(long planId, String operationCode) {
        jdbcClient
                .sql(
                        """
                        insert into reassignment_plan_item (plan_id, work_order_id, operation_code,
                                                            from_equipment_id, to_equipment_id, source_snapshot)
                        values (:planId, :workOrder, :operationCode, :from, :to,
                                '{"status": "중단"}'::jsonb)
                        """)
                .param("planId", planId)
                .param("workOrder", workOrder)
                .param("operationCode", operationCode, Types.VARCHAR)
                .param("from", fromEquipment)
                .param("to", toEquipment)
                .update();
    }

    private long recordExecution() {
        return auditRecorder.recordSeparately(
                AuditEntry.builder()
                        .eventKind(EventKind.ACTION)
                        .actor(Actor.user(new LoginAccount(plannerId, "planner", "PLANNER", false)))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .actionDefinitionId(definitionId())
                        .checkedPermissionCode("PLAN_CREATE")
                        .targetType("object_instance")
                        .targetId(fromEquipment)
                        .build());
    }

    private long definitionId() {
        return jdbcClient
                .sql("select id from action_definition where code = 'CREATE_REASSIGNMENT_PLAN'")
                .query(Long.class)
                .single();
    }

    private long createAccount() {
        return jdbcClient
                .sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id,
                                             must_change_password)
                        values (:loginId, '테스트 사용자', 'x',
                                (select id from role where code = 'PLANNER'), false)
                        returning id
                        """)
                .param("loginId", "planner-" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class)
                .single();
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

    /** 계획이 가리키는 유형을 볼 수 없는 역할에게는 계획이 없는 것처럼 보인다(384·390). */
    @Test
    void hidesPlansFromRolesThatCannotSeePlanObjects() {
        long planId = insertPlan("2026-10-30T02:00:00Z");

        assertThat(service.search(UNCONFIGURED, fromEquipment, null, PageRequest.of(0, 20)))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(UNCONFIGURED, planId))
                .isInstanceOf(com.axcore.platform.global.error.BusinessException.class);
        assertThat(service.get(ADMIN, planId).id()).isEqualTo(planId);
    }

    /** 스냅샷의 민감 속성 값은 보는 사람의 범위로 가린다. 자리는 Handler가 변경 기록에 알린 것이다(384·390). */
    @Test
    void masksRestrictedSnapshotValuesByTheViewersScope() {
        long planId = insertPlan("2026-10-30T03:00:00Z");
        jdbcClient
                .sql(
                        """
                        insert into reassignment_plan_item (plan_id, work_order_id, operation_code,
                                                            from_equipment_id, to_equipment_id, source_snapshot)
                        values (:planId, :workOrder, '20', :from, :to,
                                '{"workOrderNo": "JB2610-0141", "workOrderStatus": "진행"}'::jsonb)
                        """)
                .param("planId", planId)
                .param("workOrder", workOrder)
                .param("from", fromEquipment)
                .param("to", toEquipment)
                .update();
        jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        select id, 'wo_no', 'wo_no', 'STRING', 'NORMAL' from object_type where code = 'WORK_ORDER'
                        on conflict (object_type_id, code) do nothing
                        """)
                .update();
        String before = sensitivityOfWorkOrderNumber();
        setSensitivityOfWorkOrderNumber("RESTRICTED");
        try {
            ReassignmentPlanItemResponse item = service.items(ADMIN, planId).getFirst();

            assertThat(item.sourceSnapshot())
                    .containsEntry("workOrderNo", null)
                    .containsEntry("workOrderStatus", "진행");
            assertThat(item.operationCode()).isEqualTo("20");
            assertThat(item.restrictedPropertyCodes()).containsExactly("wo_no");
        } finally {
            setSensitivityOfWorkOrderNumber(before);
        }
    }

    private String sensitivityOfWorkOrderNumber() {
        return jdbcClient
                .sql(
                        """
                        select p.sensitivity from property_definition p join object_type t on t.id = p.object_type_id
                        where t.code = 'WORK_ORDER' and p.code = 'wo_no'
                        """)
                .query(String.class)
                .single();
    }

    private void setSensitivityOfWorkOrderNumber(String sensitivity) {
        jdbcClient
                .sql(
                        """
                        update property_definition p set sensitivity = :sensitivity
                        from object_type t
                        where t.id = p.object_type_id and t.code = 'WORK_ORDER' and p.code = 'wo_no'
                        """)
                .param("sensitivity", sensitivity)
                .update();
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
                        "update object_type set access_group = 'PLAN_TEST_' || code where code = :code and access_group is null")
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
}

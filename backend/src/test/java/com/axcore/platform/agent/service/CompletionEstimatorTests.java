package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.repository.ReassignmentObjectRepository;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import com.axcore.platform.agent.service.CompletionEstimator.Estimate;
import com.axcore.platform.agent.service.CompletionEstimator.OperationEstimate;
import com.axcore.platform.agent.service.CompletionEstimator.WorkOrderEstimate;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 시연 데이터(ai/data/data-v1.0/mes)의 값으로 계산을 확인한다. 정답은 ai/data/gold-v1.0/scenario_answers.json이다(기준 시각
 * 2026-10-30 14:00 KST).
 */
class CompletionEstimatorTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    // 2026-10-30 14:00 KST
    private static final Instant START = Instant.parse("2026-10-30T05:00:00Z");
    private static final Set<String> PRESS = Set.of("20", "30");

    private static final long WO_B = 138L;
    private static final long WO_A = 141L;
    private static final long P101 = 2210L;

    private final ReassignmentObjectRepository objects = mock(ReassignmentObjectRepository.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final DataAccess access = mock(DataAccess.class);
    private final ObjectDetailQuery details = mock(ObjectDetailQuery.class);
    private final CompletionEstimator estimator =
            new CompletionEstimator(objects, properties(), policy, details);

    private final Map<Long, ObjectRow> workOrders = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(access.canView(anyString())).thenReturn(true);
        // 077은 205 기준으로 가린 표시 이름을 준다. 기본은 원본과 같은 이름이다.
        when(details.detail(eq(ASKER), eq(DataPurpose.MODEL), anyLong()))
                .thenAnswer(call -> detail(call.getArgument(2), "1라인 1호 프레스"));
        workOrders.put(WO_B, workOrder(WO_B, "JB2610-0138", "BRK-210L"));
        workOrders.put(WO_A, workOrder(WO_A, "JB2610-0141", "BRK-210R"));
        when(objects.findByIds(any()))
                .thenAnswer(
                        call -> {
                            Collection<?> ids = call.getArgument(0);
                            return workOrders.entrySet().stream()
                                    .filter(entry -> ids.contains(entry.getKey()))
                                    .collect(
                                            Collectors.toMap(
                                                    Map.Entry::getKey, Map.Entry::getValue));
                        });
        operations(
                operation(1001L, "JB2610-0138", 10, "B101", "완료", 0),
                operation(1002L, "JB2610-0138", 20, "P101", "진행", 2400),
                operation(1003L, "JB2610-0138", 30, "P101", "진행", 2400),
                operation(1004L, "JB2610-0138", 40, "I101", "대기", 6000),
                operation(1011L, "JB2610-0141", 10, "B101", "완료", 0),
                operation(1012L, "JB2610-0141", 20, "P103", "중단", 3200),
                operation(1013L, "JB2610-0141", 30, "P103", "중단", 3200),
                operation(1014L, "JB2610-0141", 40, "I101", "대기", 5000));
        routings(
                Stream.of("BRK-210L", "BRK-210R")
                        .flatMap(
                                product ->
                                        Stream.of(
                                                routing(product, 10, "BLK", "3.00", 15),
                                                routing(product, 20, "PRS", "6.00", 40),
                                                routing(product, 30, "PRS", "5.50", 30),
                                                routing(product, 40, "INS", "3.50", 5)))
                        .toArray(ObjectRow[]::new));
    }

    /** P1: "JB2610-0141 남은 수량을 P101에서 찍으면 몇 시간?" → 3,200개 × 11.5초 = 10.2시간, 준비 70분 포함 11.4시간. */
    @Test
    void estimatesPressOperationsOfOneWorkOrder() {
        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.FOUND);
        WorkOrderEstimate order = estimate.result().workOrders().getFirst();
        assertThat(order.workOrderNo()).isEqualTo("JB2610-0141");
        assertThat(order.processingHours()).isEqualByComparingTo("10.2");
        assertThat(order.setupMinutes()).isEqualByComparingTo("70");
        assertThat(order.totalHours()).isEqualByComparingTo("11.4");
        assertThat(order.operations())
                .extracting(OperationEstimate::sequence, OperationEstimate::setupMinutes)
                .containsExactly(
                        tuple("20", new BigDecimal("40")), tuple("30", new BigDecimal("30")));
        // 14:00 + 3,200 × 11.5초(36,800초) + 70분(4,200초) = 다음 날 01:23:20
        assertThat(estimate.result().estimatedCompletionAt())
                .isEqualTo("2026-10-31T01:23:20+09:00");
        assertThat(estimate.result().startAt()).isEqualTo("2026-10-30T14:00+09:00");
        assertThat(estimate.result().assumptions()).isEqualTo(CompletionEstimator.ASSUMPTIONS);
        assertThat(estimate.result().missingInputs()).isEmpty();
        assertThat(estimate.objectIds()).contains(WO_A, 1012L, 1013L).doesNotContain(1014L);
    }

    /**
     * S4 연속 가동 가정: P101에서 진행 중인 JB2610-0138 잔량(2,400개)을 먼저 끝내고 JB2610-0141(3,200개)을 이어서 한다. 맨 처음
     * 공정(0138 20공정)만 금형이 걸려 있다고 보고, 다음 공정(0138 30공정 트리밍)부터는 준비 시간을 넣는다(148 재리뷰). 정답의 교대 반영 일정도 0138
     * 30공정에 준비 시간을 넣는다.
     */
    @Test
    void addsWorkOrdersInGivenOrderAndSkipsSetupOfFirstRunningOperation() {
        Estimate estimate = estimator.estimate(ASKER, List.of(WO_B, WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.FOUND);
        List<WorkOrderEstimate> orders = estimate.result().workOrders();
        assertThat(orders.get(0).operations())
                .extracting(OperationEstimate::setupMinutes)
                .containsExactly(new BigDecimal("0"), new BigDecimal("30"));
        // 14:00 + 2,400 × 11.5초(27,600초) + 30분 = 22:10
        assertThat(orders.get(0).estimatedCompletionAt()).isEqualTo("2026-10-30T22:10+09:00");
        assertThat(orders.get(1).estimatedCompletionAt()).isEqualTo("2026-10-31T09:33:20+09:00");
        assertThat(estimate.result().estimatedCompletionAt())
                .isEqualTo("2026-10-31T09:33:20+09:00");
        // 27,600 + 1,800 + 36,800 + 4,200 = 70,400초
        assertThat(estimate.result().totalHours()).isEqualByComparingTo("19.6");
    }

    @Test
    void takesAllUnfinishedOperationsWhenNoSequenceIsGiven() {
        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), null, START);

        assertThat(estimate.result().workOrders().getFirst().operations())
                .extracting(OperationEstimate::sequence)
                .containsExactly("20", "30", "40");
        assertThat(estimate.objectIds()).doesNotContain(1011L);
    }

    @Test
    void ordersOperationsBySequenceNumber() {
        operations(
                operation(2001L, "JB2610-0141", 100, "I101", "대기", 10),
                operation(2002L, "JB2610-0141", 20, "P103", "대기", 10));
        routings(routing("BRK-210R", 100, "PRS", "1", 0), routing("BRK-210R", 20, "PRS", "1", 0));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), null, START);

        assertThat(estimate.result().workOrders().getFirst().operations())
                .extracting(OperationEstimate::sequence)
                .containsExactly("20", "100");
    }

    @Test
    void tellsMissingRoutingInsteadOfEstimating() {
        routings(routing("BRK-210R", 20, "PRS", "6.00", 40));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().estimatedCompletionAt()).isNull();
        assertThat(estimate.result().workOrders()).isEmpty();
        assertThat(estimate.result().missingInputs())
                .containsExactly("작업지시 JB2610-0141의 30 공정 표준(제품 BRK-210R)이 없습니다.");
    }

    @Test
    void tellsMissingOrNegativeValues() {
        operations(
                operation(1012L, "JB2610-0141", 20, "P103", "중단", null),
                operation(1013L, "JB2610-0141", 30, "P103", "중단", 3200));
        routings(
                routing("BRK-210R", 20, "PRS", "6.00", 40),
                routing("BRK-210R", 30, "PRS", "-1", 30));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly(
                        "작업지시 JB2610-0141의 20 공정 남은 수량(remaining_qty) 값이 없습니다.",
                        "제품 BRK-210R의 30 공정 개당 표준 가공 시간(std_cycle_sec) 값이 음수입니다: -1");
    }

    @Test
    void tellsWhenNoOperationIsLeft() {
        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of("10"), null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly("작업지시 JB2610-0141에 끝나지 않은 10 공정이 없습니다.");
    }

    @Test
    void treatsUnknownOrOtherTypeObjectAsMissingWorkOrder() {
        workOrders.put(
                999L, new ObjectRow(999L, "Equipment", "ACTIVE", "P101", Map.of("code", "P101")));
        workOrders.put(
                998L,
                new ObjectRow(
                        998L,
                        "WorkOrder",
                        "DELETED_AT_SOURCE",
                        "JB2610-0001",
                        Map.of("wo_no", "JB2610-0001", "product_code", "BRK-210R")));

        Estimate estimate =
                estimator.estimate(ASKER, List.of(WO_A, 999L, 998L, 404L), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly(
                        "작업지시(id=999)를 찾을 수 없습니다.",
                        "작업지시(id=998)를 찾을 수 없습니다.",
                        "작업지시(id=404)를 찾을 수 없습니다.");
        assertThat(estimate.objectIds()).containsExactly(WO_A);
        verify(objects, never()).findActiveByProperty(anyString(), anyString(), any());
    }

    /** 볼 수 없는 유형의 작업지시는 있다는 사실도 알리지 않는다(205). */
    @Test
    void treatsHiddenWorkOrderTypeAsMissing() {
        when(access.canView("WorkOrder")).thenReturn(false);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs()).containsExactly("작업지시(id=141)를 찾을 수 없습니다.");
        assertThat(estimate.objectIds()).isEmpty();
    }

    @Test
    void deniesWhenOperationOrRoutingTypeIsHidden() {
        when(access.canView("Routing")).thenReturn(false);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(estimate.result().estimatedCompletionAt()).isNull();
        assertThat(estimate.result().missingInputs())
                .containsExactly("계산에 필요한 Routing 정보를 볼 권한이 없습니다.");
        verify(objects, never()).findActiveByProperty(anyString(), anyString(), any());
    }

    @Test
    void deniesWhenNeededValueIsRestricted() {
        when(access.hidesValue("Routing", "std_cycle_sec")).thenReturn(true);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(estimate.result().missingInputs())
                .containsExactly("계산에 필요한 Routing의 std_cycle_sec 값을 볼 권한이 없습니다.");
    }

    @Test
    void readsOperationsAndRoutingsByConfiguredCodes() {
        estimator.estimate(ASKER, List.of(WO_A), PRESS, null, START);

        verify(objects)
                .findActiveByProperty(
                        eq("WorkOrderOperation"), eq("wo_no"), eq(Set.of("JB2610-0141")));
        verify(objects)
                .findActiveByProperty(eq("Routing"), eq("product_code"), eq(Set.of("BRK-210R")));
    }

    /**
     * P1을 설비로 물으면("P101에서 찍으면") 공정 순서를 몰라도 P101의 설비 종류(PRS)로 하는 성형·트리밍만 계산한다. GMS에서 검사 공정까지 더해
     * 16.3시간으로 답한 것을 고쳤다(148).
     */
    @Test
    void countsOnlyOperationsOfEquipmentKind() {
        workOrders.put(P101, press(P101, "P101", "PRS"));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(estimate.result().equipment()).isEqualTo("P101(1라인 1호 프레스)");
        WorkOrderEstimate order = estimate.result().workOrders().getFirst();
        assertThat(order.operations())
                .extracting(OperationEstimate::sequence)
                .containsExactly("20", "30");
        assertThat(order.totalHours()).isEqualByComparingTo("11.4");
        assertThat(estimate.result().assumptions())
                .hasSize(CompletionEstimator.ASSUMPTIONS.size() + 1);
        assertThat(estimate.result().assumptions().getLast())
                .contains("P101(1라인 1호 프레스)", "PRS", "톤수");
        assertThat(estimate.objectIds()).contains(P101, 1012L, 1013L).doesNotContain(1014L);
    }

    @Test
    void tellsMissingEquipmentOrItsKind() {
        Estimate missing = estimator.estimate(ASKER, List.of(WO_A), Set.of(), 404L, START);

        assertThat(missing.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(missing.result().missingInputs()).containsExactly("설비(id=404)를 찾을 수 없습니다.");

        workOrders.put(P101, press(P101, "P101", null));
        Estimate noKind = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(noKind.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(noKind.result().missingInputs())
                .containsExactly("설비 P101(1라인 1호 프레스)에 설비 종류(kind) 값이 없어 할 수 있는 공정을 정할 수 없습니다.");
    }

    @Test
    void tellsWhenNoOperationFitsEquipmentKind() {
        workOrders.put(P101, press(P101, "W201", "WLD"));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly("작업지시 JB2610-0141에 설비 종류 WLD로 하는 끝나지 않은 공정이 없습니다.");
    }

    @Test
    void deniesWhenEquipmentKindIsRestricted() {
        workOrders.put(P101, press(P101, "P101", "PRS"));
        when(access.hidesValue("Routing", "equipment_kind")).thenReturn(true);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(estimate.result().missingInputs())
                .containsExactly("계산에 필요한 Routing의 equipment_kind 값을 볼 권한이 없습니다.");
    }

    /** 계산에 쓰지 않는 민감 값(설비 코드)은 비우고 가린 속성으로 알린다. 계산은 그대로 한다(148 리뷰 M1). */
    @Test
    void hidesRestrictedEquipmentCodesButStillEstimates() {
        workOrders.put(P101, press(P101, "P101", "PRS"));
        when(access.hidesValue("WorkOrderOperation", "equipment_code")).thenReturn(true);
        when(access.hidesValue("Equipment", "code")).thenReturn(true);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(estimate.result().equipment()).isEqualTo("1라인 1호 프레스");
        assertThat(estimate.result().workOrders().getFirst().operations())
                .extracting(OperationEstimate::equipmentCode)
                .containsOnlyNulls();
        assertThat(estimate.result().restrictedPropertyCodes())
                .containsExactly("Equipment.code", "WorkOrderOperation.equipment_code");
        assertThat(estimate.result().assumptions().getLast()).doesNotContain("P101");
        assertThat(estimate.result().totalHours()).isEqualByComparingTo("11.4");
    }

    /**
     * 진행 중인 공정을 다른 설비에서 하면 금형을 새로 걸어야 하므로 준비 시간을 넣는다. 같은 설비면 넣지 않는다(148 리뷰 M2). JB2610-0138은 P101에서
     * 진행 중이다.
     */
    @Test
    void addsSetupWhenRunningOperationMovesToOtherEquipment() {
        long p103 = 2211L;
        workOrders.put(P101, press(P101, "P101", "PRS"));
        workOrders.put(p103, press(p103, "P103", "PRS"));

        Estimate moved = estimator.estimate(ASKER, List.of(WO_B), Set.of(), p103, START);
        Estimate same = estimator.estimate(ASKER, List.of(WO_B), Set.of(), P101, START);

        assertThat(moved.result().workOrders().getFirst().setupMinutes())
                .isEqualByComparingTo("70");
        // 같은 설비면 맨 처음 공정(20)만 준비 시간이 없다.
        assertThat(same.result().workOrders().getFirst().setupMinutes()).isEqualByComparingTo("30");
    }

    /** 설비 코드를 볼 수 없으면 같은 설비인지 알 수 없으므로 준비 시간을 넣는다(늦은 쪽). */
    @Test
    void addsSetupWhenItCannotTellTheEquipmentIsTheSame() {
        workOrders.put(P101, press(P101, "P101", "PRS"));
        when(access.hidesValue("WorkOrderOperation", "equipment_code")).thenReturn(true);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_B), Set.of(), P101, START);

        assertThat(estimate.result().workOrders().getFirst().setupMinutes())
                .isEqualByComparingTo("70");
        assertThat(estimate.result().assumptions()).contains(CompletionEstimator.UNKNOWN_EQUIPMENT);
    }

    /** 설비 쪽 코드만 볼 수 없어도 같은 설비인지 알 수 없으므로 준비 시간을 넣는다. */
    @Test
    void addsSetupWhenOnlyEquipmentCodeIsHidden() {
        workOrders.put(P101, press(P101, "P101", "PRS"));
        when(access.hidesValue("Equipment", "code")).thenReturn(true);

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_B), Set.of(), P101, START);

        assertThat(estimate.result().workOrders().getFirst().setupMinutes())
                .isEqualByComparingTo("70");
        assertThat(estimate.result().assumptions()).contains(CompletionEstimator.UNKNOWN_EQUIPMENT);
        assertThat(estimate.result().restrictedPropertyCodes()).containsExactly("Equipment.code");
    }

    /** 원본 표시 이름이 설비 코드("P101")면 코드를 가려도 이름으로 새어 나간다. 설비 이름은 077이 205 기준으로 가린 값을 쓴다(148 재리뷰). */
    @Test
    void usesMaskedDisplayNameFromObjectDetail() {
        workOrders.put(
                P101,
                new ObjectRow(
                        P101,
                        "Equipment",
                        "ACTIVE",
                        "P101",
                        Map.of("code", "P101", "kind", "PRS")));
        when(access.hidesValue("Equipment", "code")).thenReturn(true);
        when(details.detail(ASKER, DataPurpose.MODEL, P101)).thenReturn(detail(P101, "#2210"));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.result().equipment()).isEqualTo("#2210");
        assertThat(estimate.result().assumptions()).noneMatch(line -> line.contains("P101"));
    }

    /** 진행 중인 공정이라도 앞에 다른 공정을 먼저 하면 금형을 바꾸므로 준비 시간을 넣는다. */
    @Test
    void addsSetupWhenRunningOperationIsNotFirst() {
        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A, WO_B), PRESS, null, START);

        assertThat(estimate.result().workOrders().get(1).setupMinutes()).isEqualByComparingTo("70");
    }

    /** 요청한 공정이 없거나 끝났으면 남은 공정만 조용히 계산하지 않는다(148 리뷰 m1). */
    @Test
    void tellsRequestedSequenceThatIsMissingOrFinished() {
        Estimate estimate =
                estimator.estimate(
                        ASKER,
                        List.of(WO_A),
                        new LinkedHashSet<>(List.of("20", "10", "50")),
                        null,
                        START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly(
                        "작업지시 JB2610-0141에 끝나지 않은 10 공정이 없습니다.",
                        "작업지시 JB2610-0141에 끝나지 않은 50 공정이 없습니다.");
    }

    @Test
    void tellsRequestedSequenceOfOtherEquipmentKind() {
        workOrders.put(P101, press(P101, "P101", "PRS"));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of("40"), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly("작업지시 JB2610-0141의 40 공정은 설비 종류 PRS로 하는 공정이 아닙니다(필요: INS).");
    }

    @Test
    void tellsMissingEquipmentKindOfRoutingOrStatusOfOperation() {
        workOrders.put(P101, press(P101, "P101", "PRS"));
        operations(
                operation(1012L, "JB2610-0141", 20, "P103", null, 3200),
                operation(1013L, "JB2610-0141", 30, "P103", "중단", 3200));
        routings(
                routing("BRK-210R", 20, "PRS", "6.00", 40),
                routing("BRK-210R", 30, null, "5.50", 30));

        Estimate estimate = estimator.estimate(ASKER, List.of(WO_A), Set.of(), P101, START);

        assertThat(estimate.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(estimate.result().missingInputs())
                .containsExactly(
                        "작업지시 JB2610-0141의 20 공정 상태(status) 값이 없습니다.",
                        "제품 BRK-210R의 30 공정 표준에 설비 종류(equipment_kind) 값이 없어 이 설비로 하는 공정인지 알 수 없습니다.");
    }

    @Test
    void normalizesSequencesLikeObjectProperties() {
        assertThat(CompletionEstimator.normalizeSequences(List.of("20.0", " 30 ", "OP-1")))
                .containsExactly("20", "30", "OP-1");
        assertThatThrownBy(() -> CompletionEstimator.normalizeSequences(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void operations(ObjectRow... rows) {
        when(objects.findActiveByProperty(eq("WorkOrderOperation"), eq("wo_no"), any()))
                .thenReturn(List.of(rows));
    }

    private void routings(ObjectRow... rows) {
        when(objects.findActiveByProperty(eq("Routing"), eq("product_code"), any()))
                .thenReturn(List.of(rows));
    }

    private static ObjectRow workOrder(long id, String number, String product) {
        return new ObjectRow(
                id,
                "WorkOrder",
                "ACTIVE",
                number,
                Map.of("wo_no", number, "product_code", product, "status", "진행"));
    }

    private static ObjectDetailResponse detail(long id, String displayName) {
        return new ObjectDetailResponse(
                id,
                1L,
                "설비",
                displayName,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                null);
    }

    private static ObjectRow press(long id, String code, String kind) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", code);
        properties.put("kind", kind);
        return new ObjectRow(id, "Equipment", "ACTIVE", "1라인 1호 프레스", properties);
    }

    private static ObjectRow operation(
            long id,
            String order,
            int sequence,
            String equipment,
            String status,
            Integer remaining) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("wo_no", order);
        properties.put("op_seq", sequence);
        properties.put("equipment_code", equipment);
        properties.put("status", status);
        properties.put("remaining_qty", remaining);
        return new ObjectRow(
                id, "WorkOrderOperation", "ACTIVE", order + "-" + sequence, properties);
    }

    private static long routingId = 5000L;

    private static ObjectRow routing(
            String product, int sequence, String kind, String cycle, int setup) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("product_code", product);
        properties.put("op_seq", sequence);
        properties.put("equipment_kind", kind);
        properties.put("std_cycle_sec", cycle);
        properties.put("std_setup_min", setup);
        return new ObjectRow(
                routingId++, "Routing", "ACTIVE", product + "-" + sequence, properties);
    }

    static CompletionEstimateProperties properties() {
        return new CompletionEstimateProperties(
                new CompletionEstimateProperties.WorkOrder("WorkOrder", "wo_no", "product_code"),
                new CompletionEstimateProperties.Operation(
                        "WorkOrderOperation",
                        "wo_no",
                        "op_seq",
                        "equipment_code",
                        "status",
                        "remaining_qty",
                        List.of("완료"),
                        List.of("진행")),
                new CompletionEstimateProperties.Routing(
                        "Routing",
                        "product_code",
                        "op_seq",
                        "equipment_kind",
                        "std_cycle_sec",
                        "std_setup_min"),
                new CompletionEstimateProperties.Equipment("Equipment", "code", "kind"));
    }
}

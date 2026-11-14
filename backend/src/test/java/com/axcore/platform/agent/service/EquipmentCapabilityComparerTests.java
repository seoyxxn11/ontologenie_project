package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.handler.ReassignmentProperties;
import com.axcore.platform.action.repository.ReassignmentObjectRepository;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import com.axcore.platform.agent.service.EquipmentCapabilityComparer.Comparison;
import com.axcore.platform.agent.service.EquipmentCapabilityComparer.EquipmentResult;
import com.axcore.platform.agent.service.EquipmentCapabilityComparer.OperationCheck;
import com.axcore.platform.agent.service.EquipmentCapabilityComparer.OperationRequirement;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 대체 설비 조건 비교를 시연 데이터 값으로 확인한다. 작업지시 JB2610-0141(제품 BRK-210R)의 공정 20·30은 PRS·최소 400톤, 40은 INS다.
 * P101은 PRS 400톤(충족), P201은 PRS 300톤(미충족)이다.
 */
class EquipmentCapabilityComparerTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final long WO = 141L;
    private static final long P101 = 2210L;
    private static final long P201 = 2213L;
    private static final long I101 = 2214L;
    private static final long B101 = 2209L;

    private final ReassignmentObjectRepository objects = mock(ReassignmentObjectRepository.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final DataAccess access = mock(DataAccess.class);
    private final ObjectDetailQuery details = mock(ObjectDetailQuery.class);
    private final EquipmentCapabilityComparer comparer =
            new EquipmentCapabilityComparer(objects, properties(), policy, details);

    private final Map<Long, ObjectRow> rows = new LinkedHashMap<>();
    private final Map<String, List<ObjectRow>> byType = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(access.canView(anyString())).thenReturn(true);
        when(details.detail(eq(ASKER), eq(DataPurpose.MODEL), anyLong()))
                .thenAnswer(call -> detail(call.getArgument(2), "설비"));
        rows.put(WO, workOrder("진행"));
        rows.put(P101, equipment(P101, "P101", "PRS", 400));
        rows.put(P201, equipment(P201, "P201", "PRS", 300));
        rows.put(I101, equipment(I101, "I101", "INS", null));
        rows.put(B101, equipment(B101, "B101", "BLK", 250));
        when(objects.findByIds(any(), any()))
                .thenAnswer(
                        call -> {
                            Collection<?> ids = call.getArgument(1);
                            return rows.entrySet().stream()
                                    .filter(entry -> ids.contains(entry.getKey()))
                                    .collect(
                                            Collectors.toMap(
                                                    Map.Entry::getKey, Map.Entry::getValue));
                        });
        byType.put(
                "WorkOrderOperation",
                new ArrayList<>(
                        List.of(
                                operation(1011L, 10, "B101", "완료"),
                                operation(1014L, 40, "I101", "대기"),
                                operation(1012L, 20, "P103", "중단"),
                                operation(1013L, 30, "P103", "중단"))));
        byType.put(
                "Routing",
                List.of(
                        routing(5010L, 10, "BLK", 200),
                        routing(5020L, 20, "PRS", 400),
                        routing(5030L, 30, "PRS", 400),
                        routing(5040L, 40, "INS", null)));
        when(objects.findActiveByProperty(any(), anyString(), anyString(), any()))
                .thenAnswer(call -> byType.getOrDefault((String) call.getArgument(1), List.of()));
    }

    /** 공정을 정하지 않으면 끝나지 않은 공정 중 그 설비 종류(PRS)로 하는 20·30만 비교하고, 검사(INS) 40은 빼고 알린다. */
    @Test
    void comparesPressOperationsAndJudgesEachCandidate() {
        Comparison comparison = comparer.compare(ASKER, WO, List.of(P101, P201), Set.of());

        assertThat(comparison.category()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(comparison.result().workOrderNo()).isEqualTo("JB2610-0141");
        assertThat(comparison.result().productCode()).isEqualTo("BRK-210R");
        assertThat(comparison.result().workOrderStatus()).isEqualTo("진행");
        assertThat(comparison.result().operations())
                .extracting(
                        OperationRequirement::sequence,
                        OperationRequirement::requiredKind,
                        OperationRequirement::minimumCapacity)
                .containsExactly(
                        tuple("20", "PRS", new BigDecimal("400")),
                        tuple("30", "PRS", new BigDecimal("400")));
        assertThat(comparison.result().equipment())
                .extracting(EquipmentResult::equipment, EquipmentResult::verdict)
                .containsExactly(tuple("P101(설비)", "충족"), tuple("P201(설비)", "미충족"));
        EquipmentResult p201 = comparison.result().equipment().get(1);
        assertThat(p201.excludedOperations()).containsExactly("40 공정(필요 설비 종류 INS)");
        OperationCheck check = p201.checks().getFirst();
        assertThat(check.kind()).isEqualTo("충족");
        assertThat(check.capacity()).isEqualTo("미충족");
        assertThat(check.reason()).contains("능력 300톤 < 최소 400톤");
        assertThat(comparison.result().notes()).anyMatch(note -> note.contains("저장하지 않았다"));
        assertThat(comparison.objectIds()).contains(WO, P101, P201, 1012L, 1013L, 5020L, 5030L);
    }

    /** 비교한 공정이 지금 배정된 설비의 ID를 준다. 재배정 계획의 정지 설비로 쓴다(2026-11-14 대화 95). */
    @Test
    void givesIdOfEquipmentTheOperationsAreAssignedTo() {
        long p103 = 2212L;
        byType.put("Equipment", List.of(equipment(p103, "P103", "PRS", 400)));

        Comparison comparison = comparer.compare(ASKER, WO, List.of(P101), Set.of());

        assertThat(comparison.result().operations())
                .extracting(
                        OperationRequirement::sequence,
                        OperationRequirement::assignedEquipment,
                        OperationRequirement::assignedEquipmentId)
                .containsExactly(tuple("20", "P103", p103), tuple("30", "P103", p103));
        byType.remove("Equipment");
        assertThat(comparer.compare(ASKER, WO, List.of(P101), Set.of()).result().operations())
                .as("배정 설비를 찾지 못하면 ID만 비운다")
                .extracting(OperationRequirement::assignedEquipmentId)
                .containsOnlyNulls();
    }

    /**
     * 볼 수 있는 다른 유형의 객체 ID를 작업지시·설비 자리에 넣으면 "없음"이 아니라 입력 오류로 고칠 방법을 준다. 공정 배정 ID를 작업지시로 넣고 "찾을 수
     * 없다"로 끝낸 일이 있었다(대화 95). 볼 수 없는 유형이면 있다는 사실을 알리지 않는다(205).
     */
    @Test
    void rejectsObjectOfAnotherVisibleTypeAsInputError() {
        long operation = 1012L;
        rows.put(operation, operation(operation, 20, "P103", "중단"));

        assertThatThrownBy(() -> comparer.compare(ASKER, operation, List.of(P101), Set.of()))
                .isInstanceOf(AgentQuerySession.InvalidInput.class)
                .hasMessageContaining("workOrderId #1012")
                .hasMessageContaining("WorkOrderOperation")
                .hasMessageContaining("연결된 작업지시");
        assertThatThrownBy(() -> comparer.compare(ASKER, WO, List.of(operation), Set.of()))
                .isInstanceOf(AgentQuerySession.InvalidInput.class)
                .hasMessageContaining("equipmentIds #1012");

        when(access.canView("WorkOrderOperation")).thenReturn(false);
        Comparison hidden = comparer.compare(ASKER, operation, List.of(P101), Set.of());
        assertThat(hidden.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(hidden.result().missingInputs()).containsExactly("작업지시(id=1012)를 찾을 수 없습니다.");
    }

    /** 종류가 다른 후보를 함께 넣어도 설비마다 자기 종류로 하는 공정만 비교한다(함께 넣은 후보 때문에 판정이 바뀌지 않는다). */
    @Test
    void judgesEachCandidateWithItsOwnKind() {
        Comparison together = comparer.compare(ASKER, WO, List.of(P101, I101), Set.of());

        assertThat(together.result().equipment())
                .extracting(EquipmentResult::verdict)
                .containsExactly("충족", "충족");
        assertThat(together.result().equipment().get(1).checks())
                .extracting(OperationCheck::sequence)
                .containsExactly("40");
        assertThat(together.result().operations())
                .extracting(OperationRequirement::sequence)
                .containsExactly("20", "30", "40");
        assertThat(comparer.compare(ASKER, WO, List.of(P101), Set.of()).result().equipment())
                .extracting(EquipmentResult::verdict)
                .containsExactly("충족");
    }

    /** 이 설비 종류로 하는 남은 공정이 없으면 남은 공정 전체와 비교해 종류가 맞지 않는다고 보여 주고 그 사실을 알린다. */
    @Test
    void comparesAllRemainingWhenNoOperationMatchesTheKind() {
        EquipmentResult b101 =
                comparer.compare(ASKER, WO, List.of(B101), Set.of())
                        .result()
                        .equipment()
                        .getFirst();

        assertThat(b101.verdict()).isEqualTo("미충족");
        assertThat(b101.checks())
                .extracting(OperationCheck::sequence)
                .containsExactly("20", "30", "40");
        assertThat(b101.note()).contains("남은 공정 전체");
    }

    /** 공정을 정하면 종류가 달라도 비교해 미충족을 보여 준다. 능력 조건이 없는 공정은 "조건 없음"이다. */
    @Test
    void comparesRequestedSequencesEvenWhenKindDiffers() {
        EquipmentResult p101 =
                comparer.compare(ASKER, WO, List.of(P101), Set.of("40"))
                        .result()
                        .equipment()
                        .getFirst();

        assertThat(p101.verdict()).isEqualTo("미충족");
        assertThat(p101.checks())
                .extracting(OperationCheck::kind, OperationCheck::capacity)
                .containsExactly(tuple("미충족", "조건 없음"));
    }

    /** 설비 능력·종류를 볼 권한이 없으면 미충족이 아니라 미확인이고, 권한 때문이라고 적는다. */
    @Test
    void treatsHiddenEquipmentValuesAsUnknownNotUnmet() {
        when(access.hidesValue("Equipment", "capacity_ton")).thenReturn(true);
        // 저장소는 모델 범위에서 가린 값을 null로 준다.
        rows.put(P201, equipment(P201, "P201", "PRS", null));
        EquipmentResult capacity =
                comparer.compare(ASKER, WO, List.of(P201), Set.of())
                        .result()
                        .equipment()
                        .getFirst();
        assertThat(capacity.verdict()).isEqualTo("미확인");
        assertThat(capacity.capacity()).isNull();
        assertThat(capacity.checks().getFirst().reason()).contains("설비 능력을 볼 권한이 없음");

        when(access.hidesValue("Equipment", "kind")).thenReturn(true);
        rows.put(P101, equipment(P101, "P101", null, null));
        Comparison kind = comparer.compare(ASKER, WO, List.of(P101), Set.of());
        EquipmentResult p101 = kind.result().equipment().getFirst();
        assertThat(p101.verdict()).isEqualTo("미확인");
        assertThat(p101.note()).contains("설비 종류를 알 수 없어");
        assertThat(p101.checks().getFirst().reason()).contains("설비 종류를 볼 권한이 없음");
        assertThat(kind.result().restrictedPropertyCodes())
                .contains("Equipment.capacity_ton", "Equipment.kind");
    }

    /** 공정 표준이 없는 공정은 기본 비교에서 빼고 알린다. 공정을 정해 물으면 미확인으로 보여 준다. */
    @Test
    void handlesMissingStandards() {
        byType.put(
                "Routing",
                List.of(routing(5020L, 20, "PRS", 400), routing(5040L, 40, "INS", null)));

        EquipmentResult byDefault =
                comparer.compare(ASKER, WO, List.of(P101), Set.of())
                        .result()
                        .equipment()
                        .getFirst();
        assertThat(byDefault.verdict()).isEqualTo("충족");
        assertThat(byDefault.excludedOperations()).contains("30 공정(공정 표준을 찾지 못함)");

        EquipmentResult requested =
                comparer.compare(ASKER, WO, List.of(P101), Set.of("20", "30"))
                        .result()
                        .equipment()
                        .getFirst();
        assertThat(requested.verdict()).isEqualTo("미확인");
        assertThat(requested.checks())
                .extracting(OperationCheck::sequence, OperationCheck::kind)
                .containsExactly(tuple("20", "충족"), tuple("30", "미확인"));
    }

    /** 같은 순서의 공정 행이 여럿이면 끝나지 않은 행 하나로 비교한다. */
    @Test
    void usesOneRowPerSequence() {
        byType.get("WorkOrderOperation").add(operation(1099L, 20, "P101", "완료"));

        Comparison comparison = comparer.compare(ASKER, WO, List.of(P101), Set.of("20"));

        assertThat(comparison.result().operations())
                .extracting(OperationRequirement::sequence, OperationRequirement::status)
                .containsExactly(tuple("20", "중단"));
        assertThat(comparison.result().equipment().getFirst().checks()).hasSize(1);
    }

    /** 재배정 저장 검증에서 옮길 수 없는 작업지시 상태면 그 사실을 알린다. */
    @Test
    void notesWorkOrderStatusThatCannotBeMoved() {
        rows.put(WO, workOrder("완료"));

        Comparison comparison = comparer.compare(ASKER, WO, List.of(P101), Set.of("20"));

        assertThat(comparison.result().workOrderStatus()).isEqualTo("완료");
        assertThat(comparison.result().notes()).anyMatch(note -> note.contains("옮길 수 없다"));
    }

    /** 볼 수 없는 작업지시·설비는 없는 객체처럼 NO_DATA다. 요청한 공정이 없으면 알린다. */
    @Test
    void reportsMissingObjectsAndSequences() {
        Comparison missing = comparer.compare(ASKER, 404L, List.of(P101), Set.of());
        assertThat(missing.category()).isEqualTo(AgentResultCategory.NO_DATA);
        assertThat(missing.result().missingInputs()).containsExactly("작업지시(id=404)를 찾을 수 없습니다.");

        Comparison sequence = comparer.compare(ASKER, WO, List.of(P101), Set.of("20", "99"));
        assertThat(sequence.category()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(sequence.result().missingInputs()).containsExactly("작업지시에 99 공정이 없습니다.");
    }

    /** 공정 표준 유형이나 최소 능력 값을 볼 수 없으면 비교하지 않고 DENIED다. */
    @Test
    void deniesWhenStandardsCannotBeRead() {
        when(access.canView("Routing")).thenReturn(false);
        assertThat(comparer.compare(ASKER, WO, List.of(P101), Set.of()).category())
                .isEqualTo(AgentResultCategory.DENIED);

        when(access.canView("Routing")).thenReturn(true);
        when(access.hidesValue("Routing", "min_tonnage")).thenReturn(true);
        Comparison denied = comparer.compare(ASKER, WO, List.of(P101), Set.of());
        assertThat(denied.category()).isEqualTo(AgentResultCategory.DENIED);
        assertThat(denied.result().missingInputs())
                .containsExactly("비교에 필요한 Routing의 min_tonnage 값을 볼 권한이 없습니다.");
    }

    static ReassignmentProperties properties() {
        return new ReassignmentProperties(
                new ReassignmentProperties.Equipment("Equipment", "code", "kind", "capacity_ton"),
                new ReassignmentProperties.WorkOrder(
                        "WorkOrder", "wo_no", "product_code", "status", List.of("대기", "진행")),
                new ReassignmentProperties.Operation(
                        "WorkOrderOperation",
                        "wo_no",
                        "op_seq",
                        "equipment_code",
                        "status",
                        List.of("완료")),
                new ReassignmentProperties.Routing(
                        "Routing", "product_code", "op_seq", "equipment_kind", "min_tonnage"));
    }

    private static ObjectRow workOrder(String status) {
        return new ObjectRow(
                WO,
                "WorkOrder",
                "ACTIVE",
                "JB2610-0141",
                Map.of("wo_no", "JB2610-0141", "product_code", "BRK-210R", "status", status));
    }

    private static ObjectRow equipment(long id, String code, String kind, Integer capacity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", code);
        if (kind != null) {
            properties.put("kind", kind);
        }
        if (capacity != null) {
            properties.put("capacity_ton", capacity);
        }
        return new ObjectRow(id, "Equipment", "ACTIVE", "설비", properties);
    }

    private static ObjectRow operation(long id, int sequence, String equipment, String status) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("wo_no", "JB2610-0141");
        properties.put("op_seq", sequence);
        properties.put("equipment_code", equipment);
        properties.put("status", status);
        return new ObjectRow(
                id, "WorkOrderOperation", "ACTIVE", "JB2610-0141-" + sequence, properties);
    }

    private static ObjectRow routing(long id, int sequence, String kind, Integer minimum) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("product_code", "BRK-210R");
        properties.put("op_seq", sequence);
        properties.put("equipment_kind", kind);
        if (minimum != null) {
            properties.put("min_tonnage", minimum);
        }
        return new ObjectRow(id, "Routing", "ACTIVE", "BRK-210R-" + sequence, properties);
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
}

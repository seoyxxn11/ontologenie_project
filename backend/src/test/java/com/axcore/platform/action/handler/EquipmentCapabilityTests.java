package com.axcore.platform.action.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.action.handler.EquipmentCapability.Verdict;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 재배정 저장 검증과 Agent 설비 조건 비교가 함께 쓰는 판정 규칙. */
class EquipmentCapabilityTests {
    private static final ReassignmentProperties PROPERTIES =
            new ReassignmentProperties(
                    new ReassignmentProperties.Equipment(
                            "Equipment", "code", "kind", "capacity_ton"),
                    new ReassignmentProperties.WorkOrder(
                            "WorkOrder", "wo_no", "product_code", "status", List.of("진행")),
                    new ReassignmentProperties.Operation(
                            "WorkOrderOperation",
                            "wo_no",
                            "op_seq",
                            "equipment_code",
                            "status",
                            List.of("완료")),
                    new ReassignmentProperties.Routing(
                            "Routing", "product_code", "op_seq", "equipment_kind", "min_tonnage"));

    @Test
    void judgesKindAndCapacity() {
        assertThat(check("PRS", 400, "PRS", 400).overall()).isEqualTo(Verdict.MET);
        assertThat(check("PRS", 400, "PRS", 300).capacityVerdict()).isEqualTo(Verdict.NOT_MET);
        assertThat(check("PRS", 400, "BLK", 600).kind()).isEqualTo(Verdict.NOT_MET);
        assertThat(check("PRS", 400, "PRS", null).overall()).isEqualTo(Verdict.UNKNOWN);
        assertThat(check("PRS", 400, null, 400).kind()).isEqualTo(Verdict.UNKNOWN);
        // 미충족이 하나라도 있으면 모르는 값이 있어도 미충족이다.
        assertThat(check("PRS", 400, null, 300).overall()).isEqualTo(Verdict.NOT_MET);
        EquipmentCapability.Check none = check(null, null, "PRS", null);
        assertThat(none.kind()).isEqualTo(Verdict.NOT_REQUIRED);
        assertThat(none.capacityVerdict()).isEqualTo(Verdict.NOT_REQUIRED);
        assertThat(none.overall()).isEqualTo(Verdict.MET);
    }

    private static EquipmentCapability.Check check(
            String requiredKind, Integer minimum, String kind, Integer capacity) {
        Map<String, Object> standard = new LinkedHashMap<>();
        if (requiredKind != null) {
            standard.put("equipment_kind", requiredKind);
        }
        if (minimum != null) {
            standard.put("min_tonnage", minimum);
        }
        Map<String, Object> machine = new LinkedHashMap<>();
        if (kind != null) {
            machine.put("kind", kind);
        }
        if (capacity != null) {
            machine.put("capacity_ton", capacity);
        }
        return EquipmentCapability.check(
                new ObjectRow(1L, "Routing", "ACTIVE", "r", standard),
                new ObjectRow(2L, "Equipment", "ACTIVE", "e", machine),
                PROPERTIES);
    }
}

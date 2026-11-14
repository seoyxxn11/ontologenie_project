package com.axcore.platform.action.handler;

import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 공정 표준이 요구하는 설비 종류·최소 능력(톤)을 설비의 종류·능력과 비교하는 규칙. 재배정 계획 저장 검증({@link ReassignmentPlanValidator})과
 * Agent의 저장 없는 설비 조건 비교(대체 설비 비교)가 같은 규칙을 쓴다. 판정만 하고 문구는 부르는 쪽이 만든다.
 */
public final class EquipmentCapability {

    private EquipmentCapability() {}

    /**
     * 조건 하나의 판정. NOT_REQUIRED는 공정 표준에 그 조건 값이 없어 따질 것이 없다는 뜻이고, UNKNOWN은 조건은 있는데 설비 쪽 값이 없어(또는 가려져)
     * 판단할 수 없다는 뜻이다.
     */
    public enum Verdict {
        MET,
        NOT_MET,
        UNKNOWN,
        NOT_REQUIRED
    }

    /** 설비 종류·능력 비교 결과. 값은 판정에 쓴 원래 값이다(가려진 값은 null). */
    public record Check(
            String requiredKind,
            String equipmentKind,
            Verdict kind,
            BigDecimal minimumCapacity,
            BigDecimal capacity,
            Verdict capacityVerdict) {

        /** 두 조건을 합친 판정({@link #combine}). */
        public Verdict overall() {
            return combine(List.of(kind, capacityVerdict));
        }
    }

    /**
     * 판정 여러 개를 합친다. 하나라도 못 맞추면 NOT_MET, 아니고 하나라도 모르면 UNKNOWN, 아니면 MET이다(NOT_REQUIRED는 맞춘 것으로 본다).
     * 공정 하나의 두 조건과 설비 하나의 여러 공정을 같은 규칙으로 합친다.
     */
    public static Verdict combine(Collection<Verdict> verdicts) {
        if (verdicts.contains(Verdict.NOT_MET)) {
            return Verdict.NOT_MET;
        }
        if (verdicts.contains(Verdict.UNKNOWN)) {
            return Verdict.UNKNOWN;
        }
        return Verdict.MET;
    }

    /** 공정 표준(standard)이 요구하는 조건을 설비(equipment)가 만족하는지 본다. 능력은 최소값 이상이면 만족이다. */
    public static Check check(
            ObjectRow standard, ObjectRow equipment, ReassignmentProperties properties) {
        ReassignmentProperties.Routing routing = properties.routing();
        ReassignmentProperties.Equipment machine = properties.equipment();
        String requiredKind = standard.text(routing.equipmentKind());
        String kind = equipment.text(machine.kind());
        Verdict kindVerdict =
                requiredKind == null
                        ? Verdict.NOT_REQUIRED
                        : kind == null
                                ? Verdict.UNKNOWN
                                : requiredKind.equals(kind) ? Verdict.MET : Verdict.NOT_MET;
        BigDecimal minimum = standard.number(routing.minCapacity());
        BigDecimal capacity = equipment.number(machine.capacity());
        Verdict capacityVerdict =
                minimum == null
                        ? Verdict.NOT_REQUIRED
                        : capacity == null
                                ? Verdict.UNKNOWN
                                : capacity.compareTo(minimum) >= 0 ? Verdict.MET : Verdict.NOT_MET;
        return new Check(requiredKind, kind, kindVerdict, minimum, capacity, capacityVerdict);
    }
}

package com.axcore.platform.agent.service;

import com.axcore.platform.action.handler.EquipmentCapability;
import com.axcore.platform.action.handler.EquipmentCapability.Verdict;
import com.axcore.platform.action.handler.ReassignmentProperties;
import com.axcore.platform.action.repository.ReassignmentObjectRepository;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대체 설비 조건 비교(저장 없음). 작업지시 제품의 공정 표준에서 공정마다 필요한 설비 종류·최소 능력(톤)을 찾아 후보 설비의 종류·능력과 비교한다. 판정 규칙은 재배정
 * 계획 저장 검증과 같다({@link EquipmentCapability}). 재배정 계획·항목을 만들지 않는다.
 *
 * <p>객체는 모델 범위로 읽는다(재배정 검증과 같은 저장소, 384·390). 볼 수 없는 유형의 객체는 없는 객체로, 민감 값은 null로 온다. 작업지시·설비를 볼 수
 * 없으면 NO_DATA, 비교에 필요한 공정·공정 표준 유형이나 값을 볼 수 없으면 DENIED다. 설비 쪽 값이 가려졌거나 없으면 그 조건만 미확인이다. 설비 종류·능력
 * 말고는 판단하지 않는다(금형 호환·착수 순서·납기는 범위 밖).
 *
 * <p>공정을 정하지 않으면 설비마다 자기 종류로 하는 끝나지 않은 공정만 비교한다. 후보를 합쳐 고르면 한 설비의 판정이 함께 넣은 다른 후보에 따라 바뀐다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class EquipmentCapabilityComparer {
    static final int MAX_EQUIPMENT = 5;
    static final String MET = "충족";
    static final String NOT_MET = "미충족";
    static final String UNKNOWN = "미확인";
    static final String NOT_REQUIRED = "조건 없음";
    static final List<String> NOTES =
            List.of(
                    "설비 종류와 최소 능력(톤)만 공정 표준과 비교했다. 금형 호환, 착수 순서·일정, 납기, 설비 가동 상태는 판단하지 않았다.",
                    "충족은 설비 종류·능력 조건을 맞춘다는 뜻이지 생산 가능이 확정됐다는 뜻이 아니다.",
                    "재배정 계획은 저장하지 않았다. 계획을 저장하면 그때의 데이터로 다시 검증한다.");

    private final ReassignmentObjectRepository objects;
    private final ReassignmentProperties properties;
    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectDetailQuery details;

    /** 비교 결과. objectIds는 비교에 읽은 작업지시·설비·공정·공정 표준 객체로, 출처와 기준 시점에 쓴다. */
    public record Comparison(AgentResultCategory category, Result result, Set<Long> objectIds) {}

    /**
     * AI에게 넘기는 결과. 비교하지 못했으면 operations·equipment가 비고 missingInputs에 이유가 있다. operations는 어느 설비든 비교한
     * 공정의 조건이다. restrictedPropertyCodes는 볼 권한이 없어 비운 값이다("유형코드.속성코드").
     */
    public record Result(
            String workOrderNo,
            String productCode,
            String workOrderStatus,
            List<OperationRequirement> operations,
            List<EquipmentResult> equipment,
            List<String> notes,
            List<String> missingInputs,
            List<String> restrictedPropertyCodes) {}

    /** 공정 하나에 필요한 조건. 공정 표준을 찾지 못했으면 requiredKind·minimumCapacity가 null이고 standardFound가 false다. */
    public record OperationRequirement(
            String sequence,
            String status,
            String assignedEquipment,
            Long assignedEquipmentId,
            boolean standardFound,
            String requiredKind,
            BigDecimal minimumCapacity) {}

    /**
     * 후보 설비 하나. verdict는 비교한 공정 전체를 합친 판정이다(하나라도 미충족이면 미충족, 아니고 하나라도 미확인이면 미확인).
     * excludedOperations는 이 설비 종류로 하지 않거나 공정 표준이 없어 비교에서 뺀 공정이고, note는 공정을 고른 방식이 기본과 다를 때의 설명이다.
     */
    public record EquipmentResult(
            long equipmentId,
            String equipment,
            String kind,
            BigDecimal capacity,
            String verdict,
            List<OperationCheck> checks,
            List<String> excludedOperations,
            String note) {}

    /** 공정 하나에 대한 설비 판정과 이유. */
    public record OperationCheck(String sequence, String kind, String capacity, String reason) {}

    /** 공정 순서 하나. 같은 순서의 공정 행이 여럿이면 끝나지 않은 행을 대표로 쓴다. standard는 없으면 null이다. */
    private record Step(String sequence, ObjectRow operation, ObjectRow standard) {}

    /**
     * @param workOrderId 작업지시 객체 ID
     * @param equipmentIds 후보 설비 객체 ID(1~{@value #MAX_EQUIPMENT}개, 중복 없음)
     * @param sequences 비교할 공정 순서. 비어 있으면 설비마다 자기 종류로 하는 끝나지 않은 공정
     */
    @Transactional(readOnly = true)
    public Comparison compare(
            LoginAccount asker, long workOrderId, List<Long> equipmentIds, Set<String> sequences) {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Routing routing = properties.routing();
        ReassignmentProperties.Equipment equipment = properties.equipment();
        DataAccess access = dataAccessPolicy.forModel(asker);
        Visibility visibility = new Visibility(access);

        Set<Long> ids = new LinkedHashSet<>(equipmentIds);
        ids.add(workOrderId);
        Map<Long, ObjectRow> rows = objects.findByIds(access, ids);
        rejectOtherType(rows.get(workOrderId), workOrder.type(), "workOrderId", "작업지시", access);
        for (Long id : equipmentIds) {
            rejectOtherType(rows.get(id), equipment.type(), "equipmentIds", "설비", access);
        }
        List<String> missing = new ArrayList<>();
        Set<Long> used = new LinkedHashSet<>();
        ObjectRow order = visible(rows.get(workOrderId), workOrder.type(), access);
        if (order == null) {
            // 볼 수 없는 유형의 객체도 있다는 사실을 알리지 않고 없는 작업지시로 답한다(205).
            missing.add("작업지시(id=%d)를 찾을 수 없습니다.".formatted(workOrderId));
        } else {
            used.add(order.id());
        }
        List<ObjectRow> machines = new ArrayList<>();
        for (Long id : equipmentIds) {
            ObjectRow machine = visible(rows.get(id), equipment.type(), access);
            if (machine == null) {
                missing.add("설비(id=%d)를 찾을 수 없습니다.".formatted(id));
            } else {
                machines.add(machine);
                used.add(machine.id());
            }
        }
        if (!missing.isEmpty()) {
            return notCompared(
                    AgentResultCategory.NO_DATA, null, null, null, missing, used, visibility);
        }

        List<String> denied = deniedInputs(access);
        if (!denied.isEmpty()) {
            return notCompared(
                    AgentResultCategory.DENIED, null, null, null, denied, used, visibility);
        }
        String orderNo = order.text(workOrder.number());
        String product = order.text(workOrder.product());
        String orderStatus = order.text(workOrder.status());
        if (orderNo == null) {
            missing.add(
                    "작업지시 %s에 작업지시 번호(%s) 값이 없어 공정을 찾을 수 없습니다."
                            .formatted(order.displayName(), workOrder.number()));
        }
        if (product == null) {
            missing.add(
                    "작업지시 %s에 제품 코드(%s) 값이 없어 공정 표준을 찾을 수 없습니다."
                            .formatted(order.displayName(), workOrder.product()));
        }
        if (!missing.isEmpty()) {
            return notCompared(
                    AgentResultCategory.NO_DATA,
                    orderNo,
                    product,
                    orderStatus,
                    missing,
                    used,
                    visibility);
        }

        List<ObjectRow> routings =
                objects.findActiveByProperty(
                        access, routing.type(), routing.product(), Set.of(product));
        List<Step> steps =
                steps(
                        objects.findActiveByProperty(
                                access, operation.type(), operation.workOrder(), Set.of(orderNo)),
                        routings,
                        product);
        List<Step> requested = new ArrayList<>();
        if (!sequences.isEmpty()) {
            Set<String> found = new LinkedHashSet<>();
            for (Step step : steps) {
                if (sequences.contains(step.sequence())) {
                    requested.add(step);
                    found.add(step.sequence());
                }
            }
            sequences.stream()
                    .filter(sequence -> !found.contains(sequence))
                    .forEach(sequence -> missing.add("작업지시에 %s 공정이 없습니다.".formatted(sequence)));
            if (requested.isEmpty()) {
                return notCompared(
                        AgentResultCategory.NO_DATA,
                        orderNo,
                        product,
                        orderStatus,
                        missing,
                        used,
                        visibility);
            }
        }
        List<Step> remaining = steps.stream().filter(step -> !finished(step.operation())).toList();
        if (sequences.isEmpty() && remaining.isEmpty()) {
            missing.add("작업지시 %s에 끝나지 않은 공정이 없습니다.".formatted(orderNo));
            return notCompared(
                    AgentResultCategory.NO_DATA,
                    orderNo,
                    product,
                    orderStatus,
                    missing,
                    used,
                    visibility);
        }

        Map<String, Step> compared = new LinkedHashMap<>();
        List<EquipmentResult> results = new ArrayList<>();
        for (ObjectRow machine : machines) {
            String kind = visibility.text(machine, equipment.type(), equipment.kind());
            List<String> excluded = new ArrayList<>();
            String note = null;
            List<Step> chosen;
            if (!sequences.isEmpty()) {
                chosen = requested;
            } else {
                List<Step> withStandard = new ArrayList<>();
                for (Step step : remaining) {
                    if (step.standard() == null) {
                        excluded.add("%s 공정(공정 표준을 찾지 못함)".formatted(step.sequence()));
                    } else {
                        withStandard.add(step);
                    }
                }
                chosen = new ArrayList<>();
                List<String> otherKinds = new ArrayList<>();
                for (Step step : withStandard) {
                    String required = step.standard().text(routing.equipmentKind());
                    if (kind == null || required == null || required.equals(kind)) {
                        chosen.add(step);
                    } else {
                        otherKinds.add("%s 공정(필요 설비 종류 %s)".formatted(step.sequence(), required));
                    }
                }
                if (chosen.isEmpty()) {
                    // 이 설비 종류로 하는 남은 공정이 없으면 남은 공정 전체와 비교해 종류가 맞지 않는다는 판정을 보여 준다.
                    chosen = withStandard;
                    note = "이 설비 종류로 하는 끝나지 않은 공정이 없어 남은 공정 전체와 비교했다.";
                } else {
                    excluded.addAll(otherKinds);
                }
                if (kind == null) {
                    note = "설비 종류를 알 수 없어 끝나지 않은 공정 전체와 비교했다.";
                }
            }
            List<OperationCheck> checks = new ArrayList<>();
            List<Verdict> verdicts = new ArrayList<>();
            for (Step step : chosen) {
                compared.putIfAbsent(step.sequence(), step);
                if (step.standard() == null) {
                    verdicts.add(Verdict.UNKNOWN);
                    checks.add(
                            new OperationCheck(
                                    step.sequence(),
                                    UNKNOWN,
                                    UNKNOWN,
                                    "제품 %s의 %s 공정 표준을 찾지 못했다."
                                            .formatted(product, step.sequence())));
                    continue;
                }
                EquipmentCapability.Check check =
                        EquipmentCapability.check(step.standard(), machine, properties);
                verdicts.add(check.overall());
                checks.add(
                        new OperationCheck(
                                step.sequence(),
                                word(check.kind()),
                                word(check.capacityVerdict()),
                                reason(check, visibility)));
            }
            results.add(
                    new EquipmentResult(
                            machine.id(),
                            label(asker, machine, visibility),
                            kind,
                            visibility.number(machine, equipment.type(), equipment.capacity()),
                            chosen.isEmpty()
                                    ? UNKNOWN
                                    : word(EquipmentCapability.combine(verdicts)),
                            List.copyOf(checks),
                            List.copyOf(excluded),
                            note));
        }

        Map<String, Long> assignedIds = assignedEquipmentIds(compared.values(), access, visibility);
        List<OperationRequirement> requirements = new ArrayList<>();
        compared.values().stream()
                .sorted(
                        Comparator.comparing(
                                Step::sequence, EquipmentCapabilityComparer::bySequence))
                .forEach(
                        step -> {
                            used.add(step.operation().id());
                            if (step.standard() != null) {
                                used.add(step.standard().id());
                            }
                            String assigned =
                                    visibility.text(
                                            step.operation(),
                                            operation.type(),
                                            operation.equipment());
                            requirements.add(
                                    new OperationRequirement(
                                            step.sequence(),
                                            step.operation().text(operation.status()),
                                            assigned,
                                            assigned == null ? null : assignedIds.get(assigned),
                                            step.standard() != null,
                                            step.standard() == null
                                                    ? null
                                                    : step.standard().text(routing.equipmentKind()),
                                            step.standard() == null
                                                    ? null
                                                    : step.standard()
                                                            .number(routing.minCapacity())));
                        });
        return new Comparison(
                AgentResultCategory.FOUND,
                new Result(
                        orderNo,
                        product,
                        orderStatus,
                        List.copyOf(requirements),
                        List.copyOf(results),
                        notes(orderStatus),
                        List.copyOf(missing),
                        visibility.restricted()),
                used);
    }

    /** 공정 행을 공정 순서별로 묶는다. 같은 순서가 여럿이면 끝나지 않은 행, 없으면 첫 행을 쓴다. 순서가 없는 행은 뺀다. */
    private List<Step> steps(List<ObjectRow> operations, List<ObjectRow> routings, String product) {
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Routing routing = properties.routing();
        Map<String, ObjectRow> bySequence = new LinkedHashMap<>();
        for (ObjectRow op : operations) {
            String sequence = op.text(operation.sequence());
            if (sequence == null) {
                continue;
            }
            ObjectRow current = bySequence.get(sequence);
            if (current == null || (finished(current) && !finished(op))) {
                bySequence.put(sequence, op);
            }
        }
        List<Step> steps = new ArrayList<>();
        bySequence.forEach(
                (sequence, op) ->
                        steps.add(
                                new Step(
                                        sequence,
                                        op,
                                        routings.stream()
                                                .filter(
                                                        r ->
                                                                Objects.equals(
                                                                        r.text(routing.product()),
                                                                        product))
                                                .filter(
                                                        r ->
                                                                Objects.equals(
                                                                        r.text(routing.sequence()),
                                                                        sequence))
                                                .findFirst()
                                                .orElse(null))));
        steps.sort(Comparator.comparing(Step::sequence, EquipmentCapabilityComparer::bySequence));
        return steps;
    }

    /** 공정 순서는 숫자로 비교한다("10" < "20" < "100"). 숫자가 아니면 뒤에 문자 순서로 둔다. */
    private static int bySequence(String left, String right) {
        BigDecimal a = number(left);
        BigDecimal b = number(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        if (a != null) {
            return -1;
        }
        if (b != null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static BigDecimal number(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 작업지시가 재배정 저장 검증에서 옮길 수 없는 상태면 그 사실을 함께 알린다. */
    private List<String> notes(String orderStatus) {
        List<String> movable = properties.workOrder().movableStatuses();
        if (orderStatus != null && movable.contains(orderStatus)) {
            return NOTES;
        }
        List<String> notes = new ArrayList<>(NOTES);
        notes.add(
                "작업지시가 %s 상태라 재배정 계획으로 옮길 수 없다(옮길 수 있는 상태: %s)."
                        .formatted(orderStatus == null ? "알 수 없는" : orderStatus, movable));
        return List.copyOf(notes);
    }

    /** 판정 이유. 설비 쪽 값이 가려졌으면 권한 때문에 모른다고 적는다. */
    private String reason(EquipmentCapability.Check check, Visibility visibility) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        List<String> parts = new ArrayList<>();
        switch (check.kind()) {
            case MET ->
                    parts.add(
                            "설비 종류 %s = 필요 %s"
                                    .formatted(check.equipmentKind(), check.requiredKind()));
            case NOT_MET ->
                    parts.add(
                            "설비 종류 %s ≠ 필요 %s"
                                    .formatted(check.equipmentKind(), check.requiredKind()));
            case UNKNOWN ->
                    parts.add(
                            visibility.hidden(equipment.type(), equipment.kind())
                                    ? "설비 종류를 볼 권한이 없음(필요 %s)".formatted(check.requiredKind())
                                    : "설비 종류 값이 없음(필요 %s)".formatted(check.requiredKind()));
            case NOT_REQUIRED -> parts.add("필요 설비 종류 조건 없음");
        }
        switch (check.capacityVerdict()) {
            case MET ->
                    parts.add(
                            "능력 %s톤 ≥ 최소 %s톤"
                                    .formatted(
                                            check.capacity().toPlainString(),
                                            check.minimumCapacity().toPlainString()));
            case NOT_MET ->
                    parts.add(
                            "능력 %s톤 < 최소 %s톤"
                                    .formatted(
                                            check.capacity().toPlainString(),
                                            check.minimumCapacity().toPlainString()));
            case UNKNOWN ->
                    parts.add(
                            visibility.hidden(equipment.type(), equipment.capacity())
                                    ? "설비 능력을 볼 권한이 없음(최소 %s톤)"
                                            .formatted(check.minimumCapacity().toPlainString())
                                    : "설비 능력 값이 없음(최소 %s톤)"
                                            .formatted(check.minimumCapacity().toPlainString()));
            case NOT_REQUIRED -> parts.add("최소 능력 조건 없음");
        }
        return String.join(", ", parts);
    }

    private static String word(Verdict verdict) {
        return switch (verdict) {
            case MET -> MET;
            case NOT_MET -> NOT_MET;
            case UNKNOWN -> UNKNOWN;
            case NOT_REQUIRED -> NOT_REQUIRED;
        };
    }

    /** 비교에 꼭 필요한 공정·공정 표준 유형이나 그 값을 볼 수 없으면 이유를 돌려준다. 설비 값은 가려지면 그 조건만 미확인으로 둔다. */
    private List<String> deniedInputs(DataAccess access) {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Routing routing = properties.routing();
        List<String> denied = new ArrayList<>();
        for (String type : List.of(operation.type(), routing.type())) {
            if (!access.canView(type)) {
                denied.add("비교에 필요한 %s 정보를 볼 권한이 없습니다.".formatted(type));
            }
        }
        if (!denied.isEmpty()) {
            return denied;
        }
        Map<String, List<String>> needed =
                Map.of(
                        workOrder.type(),
                        List.of(workOrder.number(), workOrder.product(), workOrder.status()),
                        operation.type(),
                        List.of(operation.workOrder(), operation.sequence(), operation.status()),
                        routing.type(),
                        List.of(
                                routing.product(),
                                routing.sequence(),
                                routing.equipmentKind(),
                                routing.minCapacity()));
        needed.forEach(
                (type, codes) ->
                        codes.stream()
                                .filter(code -> access.hidesValue(type, code))
                                .forEach(
                                        code ->
                                                denied.add(
                                                        "비교에 필요한 %s의 %s 값을 볼 권한이 없습니다."
                                                                .formatted(type, code))));
        denied.sort(null);
        return denied;
    }

    private boolean finished(ObjectRow operation) {
        String status = operation.text(properties.operation().status());
        return status != null && properties.operation().finishedStatuses().contains(status);
    }

    /**
     * 비교한 공정이 지금 배정된 설비의 객체 ID(설비 코드 → ID). 재배정 계획의 정지 설비(fromEquipmentId)로 쓴다. 공정의 설비 코드가 가려졌거나 그
     * 코드의 설비를 볼 수 없으면 빠진다.
     */
    private Map<String, Long> assignedEquipmentIds(
            Collection<Step> steps, DataAccess access, Visibility visibility) {
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Equipment equipment = properties.equipment();
        Set<String> codes = new LinkedHashSet<>();
        for (Step step : steps) {
            String code =
                    visibility.text(step.operation(), operation.type(), operation.equipment());
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty() || !access.canView(equipment.type())) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>();
        for (ObjectRow row :
                objects.findActiveByProperty(access, equipment.type(), equipment.code(), codes)) {
            String code = row.text(equipment.code());
            if (code != null) {
                ids.putIfAbsent(code, row.id());
            }
        }
        return ids;
    }

    /**
     * 볼 수 있는 다른 유형의 객체 ID를 넣었으면 "없음"이 아니라 입력 오류로 알려 AI가 고치게 한다. 공정 배정 ID를 작업지시 ID로 넣고 "작업지시를 찾을 수
     * 없다"로 끝낸 일이 있었다(2026-11-14 대화 95). 볼 수 없는 유형이면 있다는 사실을 알리지 않고 기존처럼 없는 객체로 둔다(205).
     */
    private static void rejectOtherType(
            ObjectRow row, String type, String field, String label, DataAccess access) {
        if (row == null || !row.isActive() || type.equals(row.typeCode())) {
            return;
        }
        if (!access.canView(row.typeCode())) {
            return;
        }
        throw new AgentQuerySession.InvalidInput(
                "%s #%d는 %s가 아니라 %s 유형 객체입니다. 이 객체와 연결된 %s를 관계 조회로 찾아 그 %s의 ID로 다시 부르세요."
                        .formatted(field, row.id(), label, row.typeCode(), label, label),
                null);
    }

    /** 활성이고 그 유형이며 사용자가 볼 수 있는 객체만 돌려준다. 아니면 null이다. */
    private static ObjectRow visible(ObjectRow row, String type, DataAccess access) {
        if (row == null || !row.isActive() || !type.equals(row.typeCode())) {
            return null;
        }
        return access.canView(type) ? row : null;
    }

    /** 답변에 말할 설비 이름. 완료 시각 계산과 같다: 객체 상세(077)의 가린 표시 이름, 코드를 볼 수 있으면 "코드(이름)". */
    private String label(LoginAccount asker, ObjectRow machine, Visibility visibility) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        String name;
        try {
            name = details.detail(asker, DataPurpose.MODEL, machine.id()).displayName();
        } catch (BusinessException e) {
            name = "#" + machine.id();
        }
        String code = visibility.text(machine, equipment.type(), equipment.code());
        return code == null || code.equals(name) ? name : "%s(%s)".formatted(code, name);
    }

    private Comparison notCompared(
            AgentResultCategory category,
            String orderNo,
            String product,
            String orderStatus,
            List<String> reasons,
            Set<Long> used,
            Visibility visibility) {
        return new Comparison(
                category,
                new Result(
                        orderNo,
                        product,
                        orderStatus,
                        List.of(),
                        List.of(),
                        NOTES,
                        List.copyOf(reasons),
                        visibility.restricted()),
                used);
    }

    /** 결과에 보여 줄 민감 값을 가리고, 가린 속성을 모은다. */
    private static final class Visibility {
        private final DataAccess access;
        private final Set<String> restricted = new TreeSet<>();

        Visibility(DataAccess access) {
            this.access = access;
        }

        boolean hidden(String type, String code) {
            return access.hidesValue(type, code);
        }

        String text(ObjectRow row, String type, String code) {
            if (access.hidesValue(type, code)) {
                restricted.add(type + "." + code);
                return null;
            }
            return row.text(code);
        }

        BigDecimal number(ObjectRow row, String type, String code) {
            if (access.hidesValue(type, code)) {
                restricted.add(type + "." + code);
                return null;
            }
            return row.number(code);
        }

        List<String> restricted() {
            return List.copyOf(restricted);
        }
    }
}

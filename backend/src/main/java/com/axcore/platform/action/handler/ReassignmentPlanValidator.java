package com.axcore.platform.action.handler;

import com.axcore.platform.action.repository.ReassignmentObjectRepository;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import com.axcore.platform.auth.service.DataAccess;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 재배정 계획의 업무 조건 (Action 정의서 4-1 실행 전 검증, Jira 208). 검사에 쓸 객체를 처음에 한 번에 읽고, 아래 순서로 검사해 첫 위반을 이유로
 * 돌려준다.
 *
 * <ol>
 *   <li>정지·대체 설비가 설비 객체이고 작업지시가 작업지시 객체인가(활성 객체만)
 *   <li>대체 설비가 정지 설비와 다른가
 *   <li>같은 계획에 같은 작업지시·공정이 두 번 들어가지 않았는가
 *   <li>작업지시가 옮길 수 있는 상태인가
 *   <li>옮길 공정이 정지 설비에 배정돼 있는가(2026-11-04 추가). 공정을 정하지 않으면 정지 설비에 배정된 남은 공정 전체다
 *   <li>공정이 이미 끝나지 않았는가
 *   <li>대체 설비가 공정 표준의 설비 종류와 최소 능력(톤)을 만족하는가
 * </ol>
 *
 * <p>객체는 실행자의 조회 범위로 읽는다(384·390). 볼 수 없는 유형의 객체는 "찾을 수 없음"으로, 볼 수 없는 민감 값은 "속성이 없음"으로 막힌다. 차단 문구에
 * 나가는 값은 범위를 적용한 값이고, Agent가 실행하면 모델 용도로 읽어 민감 값은 늘 가린다.
 *
 * <p>공정 표준은 관계가 아니라 작업지시의 제품 코드와 공정 순서 속성으로 찾는다. 정답 온톨로지에서 공정→공정 표준 관계는 작업지시를 거쳐 이어지는데, 관계
 * 규칙(link_mapping_condition)은 양 끝 속성을 직접 비교하는 조건만 담을 수 있어 그 관계가 만들어지지 않을 수 있다.
 */
@Component
@RequiredArgsConstructor
class ReassignmentPlanValidator {

    private final ReassignmentObjectRepository objects;
    private final ReassignmentProperties properties;

    Result validate(ReassignmentPlanInput input, DataAccess access) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();

        Set<Long> ids = new LinkedHashSet<>();
        ids.add(input.fromEquipmentId());
        input.items()
                .forEach(
                        item -> {
                            ids.add(item.workOrderId());
                            ids.add(item.toEquipmentId());
                        });
        Map<Long, ObjectRow> rows = objects.findByIds(access, ids);

        ObjectRow from = rows.get(input.fromEquipmentId());
        if (!isType(from, equipment.type())) {
            return Result.blocked(
                    "정지 설비(id=%d)를 찾을 수 없거나 설비 객체가 아닙니다.".formatted(input.fromEquipmentId()));
        }
        for (ReassignmentPlanInput.Item item : input.items()) {
            if (!isType(rows.get(item.workOrderId()), workOrder.type())) {
                return Result.blocked(
                        "작업지시(id=%d)를 찾을 수 없거나 작업지시 객체가 아닙니다.".formatted(item.workOrderId()));
            }
            if (!isType(rows.get(item.toEquipmentId()), equipment.type())) {
                return Result.blocked(
                        "대체 설비(id=%d)를 찾을 수 없거나 설비 객체가 아닙니다.".formatted(item.toEquipmentId()));
            }
        }
        // 배정 판정은 공정의 설비 코드와 비교하므로 표시 이름으로 대신하지 않는다.
        String fromCode = from.text(equipment.code());
        if (fromCode == null) {
            return Result.blocked(
                    "정지 설비 %s에 설비 코드(%s) 속성이 없어 배정을 확인할 수 없습니다."
                            .formatted(from.displayName(), equipment.code()));
        }
        for (ReassignmentPlanInput.Item item : input.items()) {
            if (item.toEquipmentId().equals(input.fromEquipmentId())) {
                return Result.blocked(
                        "대체 설비가 정지 설비 %s와 같습니다(작업지시 %s)."
                                .formatted(
                                        fromCode,
                                        label(rows.get(item.workOrderId()), workOrder.number())));
            }
        }
        String duplicate = duplicateItem(input.items(), rows);
        if (duplicate != null) {
            return Result.blocked(duplicate);
        }
        for (ReassignmentPlanInput.Item item : input.items()) {
            ObjectRow order = rows.get(item.workOrderId());
            // 공정과 공정 표준을 이 두 속성으로 찾으므로, 없으면 원인을 바로 알려 준다.
            if (order.text(workOrder.number()) == null) {
                return Result.blocked(
                        "작업지시 %s에 작업지시 번호(%s) 속성이 없어 공정을 찾을 수 없습니다."
                                .formatted(order.displayName(), workOrder.number()));
            }
            if (order.text(workOrder.product()) == null) {
                return Result.blocked(
                        "작업지시 %s에 제품 코드(%s) 속성이 없어 공정 표준을 찾을 수 없습니다."
                                .formatted(order.displayName(), workOrder.product()));
            }
            String status = order.text(workOrder.status());
            if (status == null || !workOrder.movableStatuses().contains(status)) {
                return Result.blocked(
                        "작업지시 %s는 %s 상태라 옮길 수 없습니다."
                                .formatted(label(order, workOrder.number()), status));
            }
        }

        Set<String> orderNumbers = new LinkedHashSet<>();
        input.items()
                .forEach(
                        item ->
                                orderNumbers.add(
                                        rows.get(item.workOrderId()).text(workOrder.number())));
        List<ObjectRow> operations =
                objects.findActiveByProperty(
                        access, operation.type(), operation.workOrder(), orderNumbers);

        List<ValidatedItem> validated = new ArrayList<>();
        for (ReassignmentPlanInput.Item item : input.items()) {
            ObjectRow order = rows.get(item.workOrderId());
            String orderNo = order.text(workOrder.number());
            List<ObjectRow> ofOrder =
                    operations.stream()
                            .filter(op -> Objects.equals(op.text(operation.workOrder()), orderNo))
                            .toList();
            List<ObjectRow> targets;
            if (item.operationCode() != null) {
                List<ObjectRow> sameSequence =
                        ofOrder.stream()
                                .filter(
                                        op ->
                                                item.operationCode()
                                                        .equals(op.text(operation.sequence())))
                                .toList();
                if (sameSequence.isEmpty()) {
                    return Result.blocked(
                            "작업지시 %s에 %s 공정이 없습니다.".formatted(orderNo, item.operationCode()));
                }
                List<ObjectRow> assigned =
                        sameSequence.stream()
                                .filter(op -> fromCode.equals(op.text(operation.equipment())))
                                .toList();
                if (assigned.isEmpty()) {
                    return Result.blocked(
                            "작업지시 %s의 %s 공정은 정지 설비 %s에 배정돼 있지 않습니다."
                                    .formatted(orderNo, item.operationCode(), fromCode));
                }
                // 같은 공정 행이 여러 개면 끝나지 않은 것만 옮긴다.
                targets = assigned.stream().filter(op -> !isFinished(op)).toList();
                if (targets.isEmpty()) {
                    return Result.blocked(
                            "작업지시 %s의 %s 공정은 이미 끝났습니다.".formatted(orderNo, item.operationCode()));
                }
            } else {
                targets =
                        ofOrder.stream()
                                .filter(op -> fromCode.equals(op.text(operation.equipment())))
                                .filter(op -> !isFinished(op))
                                .toList();
                if (targets.isEmpty()) {
                    return Result.blocked(
                            "작업지시 %s에 정지 설비 %s에 배정된 남은 공정이 없습니다.".formatted(orderNo, fromCode));
                }
            }
            validated.add(new ValidatedItem(item, order, rows.get(item.toEquipmentId()), targets));
        }

        String capability = capabilityViolation(validated, access);
        if (capability != null) {
            return Result.blocked(capability);
        }
        return new Result(null, from, List.copyOf(validated));
    }

    /** 공정 표준이 요구하는 설비 종류와 최소 능력을 대체 설비가 만족하지 않으면 이유를 돌려준다. */
    private String capabilityViolation(List<ValidatedItem> items, DataAccess access) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Routing routing = properties.routing();

        Set<String> products = new LinkedHashSet<>();
        items.forEach(item -> products.add(item.workOrder().text(workOrder.product())));
        List<ObjectRow> routings =
                objects.findActiveByProperty(access, routing.type(), routing.product(), products);

        for (ValidatedItem item : items) {
            String orderNo = item.workOrder().text(workOrder.number());
            String product = item.workOrder().text(workOrder.product());
            ObjectRow to = item.toEquipment();
            String toCode = label(to, equipment.code());
            for (ObjectRow op : item.operations()) {
                String sequence = op.text(operation.sequence());
                ObjectRow standard =
                        routings.stream()
                                .filter(r -> Objects.equals(r.text(routing.product()), product))
                                .filter(r -> Objects.equals(r.text(routing.sequence()), sequence))
                                .findFirst()
                                .orElse(null);
                if (standard == null) {
                    return "작업지시 %s의 %s 공정 표준(제품 %s)을 찾을 수 없습니다."
                            .formatted(orderNo, sequence, product);
                }
                String requiredKind = standard.text(routing.equipmentKind());
                if (requiredKind != null && !requiredKind.equals(to.text(equipment.kind()))) {
                    return "대체 설비 %s(%s)는 작업지시 %s의 %s 공정에 쓸 수 없는 종류입니다(필요: %s)."
                            .formatted(
                                    toCode,
                                    to.text(equipment.kind()),
                                    orderNo,
                                    sequence,
                                    requiredKind);
                }
                BigDecimal minimum = standard.number(routing.minCapacity());
                if (minimum != null) {
                    BigDecimal capacity = to.number(equipment.capacity());
                    if (capacity == null || capacity.compareTo(minimum) < 0) {
                        return "대체 설비 %s의 능력 %s톤이 작업지시 %s의 %s 공정 최소 %s톤보다 작습니다."
                                .formatted(
                                        toCode,
                                        capacity == null ? "(없음)" : capacity.toPlainString(),
                                        orderNo,
                                        sequence,
                                        minimum.toPlainString());
                    }
                }
            }
        }
        return null;
    }

    /** 같은 작업지시·공정이 두 번 있거나, 작업지시 전체와 그 작업지시의 개별 공정이 함께 있으면 이유를 돌려준다. */
    private String duplicateItem(
            List<ReassignmentPlanInput.Item> items, Map<Long, ObjectRow> rows) {
        Set<String> seen = new HashSet<>();
        Set<Long> whole = new HashSet<>();
        Set<Long> partial = new HashSet<>();
        for (ReassignmentPlanInput.Item item : items) {
            String orderNo = label(rows.get(item.workOrderId()), properties.workOrder().number());
            String key = item.workOrderId() + "/" + item.operationCode();
            if (!seen.add(key)) {
                return item.operationCode() == null
                        ? "작업지시 %s 전체가 두 번 들어 있습니다.".formatted(orderNo)
                        : "작업지시 %s의 %s 공정이 두 번 들어 있습니다.".formatted(orderNo, item.operationCode());
            }
            (item.operationCode() == null ? whole : partial).add(item.workOrderId());
            if (whole.contains(item.workOrderId()) && partial.contains(item.workOrderId())) {
                return "작업지시 %s 전체와 개별 공정을 함께 옮길 수 없습니다.".formatted(orderNo);
            }
        }
        return null;
    }

    private boolean isFinished(ObjectRow operation) {
        String status = operation.text(properties.operation().status());
        return status != null && properties.operation().finishedStatuses().contains(status);
    }

    private static boolean isType(ObjectRow row, String typeCode) {
        return row != null && row.isActive() && typeCode.equals(row.typeCode());
    }

    /** 메시지에 쓸 객체 이름. 코드 속성이 없으면 표시 이름이다. */
    private static String label(ObjectRow row, String key) {
        String code = row.text(key);
        return code == null ? row.displayName() : code;
    }

    /** blockedReason이 있으면 막힌 것이고, 없으면 검사를 통과한 객체가 들어 있다. */
    record Result(String blockedReason, ObjectRow fromEquipment, List<ValidatedItem> items) {

        static Result blocked(String reason) {
            return new Result(reason, null, List.of());
        }
    }

    /** 검사를 통과한 항목. operations는 이 항목이 옮길 공정이다. */
    record ValidatedItem(
            ReassignmentPlanInput.Item item,
            ObjectRow workOrder,
            ObjectRow toEquipment,
            List<ObjectRow> operations) {}
}

package com.axcore.platform.agent.service;

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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업지시의 예상 완료 시각을 계산한다(FN-AGT-09, S15P31C106-148). 공정마다 남은 수량 × 개당 표준 가공 시간 + 표준 준비 시간을 구하고, 받은
 * 작업지시 순서·공정 순서대로 쉬지 않고 이어서 한다고 보고 더한다(연속 가동). 교대·휴무·비가동은 반영하지 않는다(2026-11-12 사용자 결정).
 *
 * <p>객체는 재배정 검증과 같은 저장소로 유형·속성 코드를 기준으로 읽는다. 이 저장소는 조회 권한(205)을 거치지 않으므로 여기서 질문한 사용자의 범위를 직접 확인한다.
 * 작업지시를 볼 수 없으면 없는 작업지시처럼 NO_DATA, 계산에 필요한 공정·공정 표준이나 그 값을 볼 수 없으면 DENIED다. 설비 코드는 준비 시간을 넣을지 정하는
 * 데만 쓰므로, 볼 수 없으면 DENIED로 막지 않고 비운 뒤 restrictedPropertyCodes로 알리고 준비 시간을 넣는다(늦은 쪽). 설비 이름은 객체 상세
 * 조회(077)에서 받아 205 기준으로 가린 표시 이름을 쓴다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(CompletionEstimateProperties.class)
@RequiredArgsConstructor
public class CompletionEstimator {
    static final List<String> ASSUMPTIONS =
            List.of(
                    "작업지시는 받은 순서대로, 공정은 공정 순서대로 쉬지 않고 이어서 한다고 보았다(연속 가동).",
                    "교대·휴무·비가동·작업 대기는 반영하지 않았다.",
                    "맨 처음 할 공정이 지금 그 설비에서 진행 중이면 준비(금형 교체)를 마쳤다고 보고 준비 시간을 넣지 않았다. 그 밖의 공정은"
                            + " 진행 중이어도 준비 시간을 넣었다(앞 공정이 금형을 바꾸거나 다른 설비에서 한다).");
    static final String UNKNOWN_EQUIPMENT = "설비 코드를 볼 수 없어 같은 설비인지 알 수 없는 진행 중 공정은 준비 시간을 넣었다.";

    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final ReassignmentObjectRepository objects;
    private final CompletionEstimateProperties properties;
    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectDetailQuery details;

    /** 계산 결과. objectIds는 계산에 읽은 작업지시·공정·공정 표준 객체로, 출처와 기준 시점에 쓴다. */
    public record Estimate(AgentResultCategory category, Result result, Set<Long> objectIds) {}

    /**
     * AI에게 넘기는 결과. 시각은 한국 시간 오프셋 문자열이고, 계산하지 못했으면 완료 시각은 null이고 missingInputs에 이유가 있다.
     * restrictedPropertyCodes는 볼 권한이 없어 비운 값이다("유형코드.속성코드").
     */
    public record Result(
            String startAt,
            String equipment,
            String estimatedCompletionAt,
            BigDecimal totalHours,
            List<WorkOrderEstimate> workOrders,
            List<String> assumptions,
            List<String> missingInputs,
            List<String> restrictedPropertyCodes) {}

    /** 작업지시 하나. estimatedCompletionAt은 앞 작업지시까지 끝낸 뒤 이어서 했을 때의 완료 시각이다. */
    public record WorkOrderEstimate(
            long workOrderId,
            String workOrderNo,
            String productCode,
            List<OperationEstimate> operations,
            BigDecimal processingHours,
            BigDecimal setupMinutes,
            BigDecimal totalHours,
            String estimatedCompletionAt) {}

    /** 공정 하나의 계산 근거. setupMinutes는 실제로 넣은 준비 시간이다(같은 설비에서 진행 중이면 0). 설비 코드를 볼 수 없으면 null이다. */
    public record OperationEstimate(
            String sequence,
            String equipmentCode,
            String status,
            BigDecimal remainingQuantity,
            BigDecimal cycleSeconds,
            BigDecimal setupMinutes,
            BigDecimal hours) {}

    /**
     * @param workOrderIds 이어서 할 순서대로 적은 작업지시 객체 ID(비어 있지 않고 중복 없음)
     * @param sequences 계산할 공정 순서. 비어 있으면 끝나지 않은 공정 전체
     * @param equipmentId 이 설비에서 한다고 볼 설비 객체 ID. 있으면 그 설비 종류로 하는 공정만 계산한다. null이면 거르지 않는다
     * @param startAt 계산 시작 시각
     */
    @Transactional(readOnly = true)
    public Estimate estimate(
            LoginAccount asker,
            List<Long> workOrderIds,
            Set<String> sequences,
            Long equipmentId,
            Instant startAt) {
        CompletionEstimateProperties.WorkOrder workOrder = properties.workOrder();
        CompletionEstimateProperties.Operation operation = properties.operation();
        CompletionEstimateProperties.Routing routing = properties.routing();
        CompletionEstimateProperties.Equipment equipment = properties.equipment();
        DataAccess access = dataAccessPolicy.forModel(asker);

        Set<Long> ids = new LinkedHashSet<>(workOrderIds);
        if (equipmentId != null) {
            ids.add(equipmentId);
        }
        Map<Long, ObjectRow> rows = objects.findByIds(ids);
        List<ObjectRow> orders = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Long id : workOrderIds) {
            ObjectRow row = visible(rows.get(id), workOrder.type(), access);
            if (row == null) {
                // 볼 수 없는 유형의 객체도 있다는 사실을 알리지 않고 없는 작업지시로 답한다(205).
                missing.add("작업지시(id=%d)를 찾을 수 없습니다.".formatted(id));
            } else {
                orders.add(row);
            }
        }
        ObjectRow machine = null;
        if (equipmentId != null) {
            machine = visible(rows.get(equipmentId), equipment.type(), access);
            if (machine == null) {
                missing.add("설비(id=%d)를 찾을 수 없습니다.".formatted(equipmentId));
            }
        }
        Set<Long> used = new LinkedHashSet<>();
        orders.forEach(order -> used.add(order.id()));
        if (machine != null) {
            used.add(machine.id());
        }
        Visibility visibility = new Visibility(access);
        String machineLabel = machine == null ? null : label(asker, machine, visibility);
        if (!missing.isEmpty()) {
            return notComputed(
                    AgentResultCategory.NO_DATA, startAt, machineLabel, missing, used, visibility);
        }

        List<String> denied = deniedInputs(access, machine != null);
        if (!denied.isEmpty()) {
            return notComputed(
                    AgentResultCategory.DENIED, startAt, machineLabel, denied, used, visibility);
        }
        String kind = null;
        if (machine != null) {
            kind = machine.text(equipment.kind());
            if (kind == null) {
                missing.add(
                        "설비 %s에 설비 종류(%s) 값이 없어 할 수 있는 공정을 정할 수 없습니다."
                                .formatted(machineLabel, equipment.kind()));
                return notComputed(
                        AgentResultCategory.NO_DATA,
                        startAt,
                        machineLabel,
                        missing,
                        used,
                        visibility);
            }
        }
        Machine target =
                machine == null ? null : new Machine(kind, machineCode(machine, visibility));
        Cursor cursor = new Cursor();

        Set<String> orderNumbers = new LinkedHashSet<>();
        Set<String> products = new LinkedHashSet<>();
        for (ObjectRow order : orders) {
            String number = order.text(workOrder.number());
            String product = order.text(workOrder.product());
            if (number == null) {
                missing.add(
                        "작업지시 %s에 작업지시 번호(%s) 값이 없어 공정을 찾을 수 없습니다."
                                .formatted(order.displayName(), workOrder.number()));
            } else {
                orderNumbers.add(number);
            }
            if (product == null) {
                missing.add(
                        "작업지시 %s에 제품 코드(%s) 값이 없어 공정 표준을 찾을 수 없습니다."
                                .formatted(order.displayName(), workOrder.product()));
            } else {
                products.add(product);
            }
        }
        if (!missing.isEmpty()) {
            return notComputed(
                    AgentResultCategory.NO_DATA, startAt, machineLabel, missing, used, visibility);
        }

        List<ObjectRow> operations =
                objects.findActiveByProperty(operation.type(), operation.workOrder(), orderNumbers);
        List<ObjectRow> routings =
                objects.findActiveByProperty(routing.type(), routing.product(), products);

        List<Plan> plans = new ArrayList<>();
        for (ObjectRow order : orders) {
            plans.add(
                    plan(
                            order,
                            operations,
                            routings,
                            sequences,
                            target,
                            visibility,
                            cursor,
                            missing,
                            used));
        }
        if (!missing.isEmpty()) {
            return notComputed(
                    AgentResultCategory.NO_DATA, startAt, machineLabel, missing, used, visibility);
        }

        List<WorkOrderEstimate> estimates = new ArrayList<>();
        BigDecimal elapsedSeconds = BigDecimal.ZERO;
        for (Plan plan : plans) {
            elapsedSeconds = elapsedSeconds.add(plan.seconds());
            estimates.add(plan.toEstimate(at(startAt, elapsedSeconds)));
        }
        return new Estimate(
                AgentResultCategory.FOUND,
                new Result(
                        AgentQuerySession.inUserZone(startAt),
                        machineLabel,
                        estimates.getLast().estimatedCompletionAt(),
                        hours(elapsedSeconds),
                        List.copyOf(estimates),
                        assumptions(machineLabel, kind, cursor.unknownEquipment),
                        List.of(),
                        visibility.restricted()),
                used);
    }

    /** 계산 순서를 따라가며 맨 처음 공정인지, 설비 코드를 몰라 준비 시간을 넣었는지 기억한다. */
    private static final class Cursor {
        private boolean first = true;
        private boolean unknownEquipment;
    }

    /** "이 설비에서 하면"의 설비. code는 볼 수 없으면 null이다. */
    private record Machine(String kind, String code) {}

    /** 결과에 보여 줄 민감 값을 가리고, 가린 속성을 모은다. */
    private static final class Visibility {
        private final DataAccess access;
        private final Set<String> restricted = new TreeSet<>();

        Visibility(DataAccess access) {
            this.access = access;
        }

        /** 볼 수 없으면 null을 돌려주고 가린 속성으로 남긴다. */
        String text(ObjectRow row, String type, String code) {
            if (access.hidesValue(type, code)) {
                restricted.add(type + "." + code);
                return null;
            }
            return row.text(code);
        }

        List<String> restricted() {
            return List.copyOf(restricted);
        }
    }

    private String machineCode(ObjectRow machine, Visibility visibility) {
        CompletionEstimateProperties.Equipment equipment = properties.equipment();
        return visibility.text(machine, equipment.type(), equipment.code());
    }

    /** 활성이고 그 유형이며 사용자가 볼 수 있는 객체만 돌려준다. 아니면 null이다. */
    private static ObjectRow visible(ObjectRow row, String type, DataAccess access) {
        if (row == null || !row.isActive() || !type.equals(row.typeCode())) {
            return null;
        }
        return access.canView(type) ? row : null;
    }

    /**
     * 답변에 말할 설비 이름. 표시 이름은 객체 상세(077)에서 받는다. 원본 표시 이름에는 민감 속성이나 원천 키(설비 코드)가 들어 있을 수 있어 그대로 쓰지
     * 않는다(205, 148 재리뷰). 설비 코드를 볼 수 있고 표시 이름과 다르면 "코드(표시 이름)"다.
     */
    private String label(LoginAccount asker, ObjectRow machine, Visibility visibility) {
        String name;
        try {
            name = details.detail(asker, DataPurpose.MODEL, machine.id()).displayName();
        } catch (BusinessException e) {
            name = "#" + machine.id();
        }
        String code = machineCode(machine, visibility);
        return code == null || code.equals(name) ? name : "%s(%s)".formatted(code, name);
    }

    private static List<String> assumptions(
            String machineLabel, String kind, boolean unknownEquipment) {
        List<String> assumptions = new ArrayList<>(ASSUMPTIONS);
        if (unknownEquipment) {
            assumptions.add(UNKNOWN_EQUIPMENT);
        }
        if (machineLabel == null) {
            return List.copyOf(assumptions);
        }
        assumptions.add(
                "설비 %s에서 하는 것으로 보고 설비 종류 %s로 하는 공정만 계산했다. 설비 능력(톤수)과 가동 상태는 확인하지 않았다."
                        .formatted(machineLabel, kind));
        return List.copyOf(assumptions);
    }

    /** 계산에 필요한 공정·공정 표준 유형이나 그 값을 볼 수 없으면 이유를 돌려준다. */
    private List<String> deniedInputs(DataAccess access, boolean byEquipment) {
        CompletionEstimateProperties.WorkOrder workOrder = properties.workOrder();
        CompletionEstimateProperties.Operation operation = properties.operation();
        CompletionEstimateProperties.Routing routing = properties.routing();
        CompletionEstimateProperties.Equipment equipment = properties.equipment();
        List<String> denied = new ArrayList<>();
        List<String> types = new ArrayList<>(List.of(operation.type(), routing.type()));
        if (byEquipment) {
            types.add(equipment.type());
        }
        for (String type : types) {
            if (!access.canView(type)) {
                denied.add("계산에 필요한 %s 정보를 볼 권한이 없습니다.".formatted(type));
            }
        }
        if (!denied.isEmpty()) {
            return denied;
        }
        Map<String, List<String>> needed =
                Map.of(
                        workOrder.type(),
                        List.of(workOrder.number(), workOrder.product()),
                        operation.type(),
                        List.of(
                                operation.workOrder(),
                                operation.sequence(),
                                operation.status(),
                                operation.remainingQuantity()),
                        routing.type(),
                        byEquipment
                                ? List.of(
                                        routing.product(),
                                        routing.sequence(),
                                        routing.equipmentKind(),
                                        routing.cycleSeconds(),
                                        routing.setupMinutes())
                                : List.of(
                                        routing.product(),
                                        routing.sequence(),
                                        routing.cycleSeconds(),
                                        routing.setupMinutes()),
                        equipment.type(),
                        byEquipment ? List.of(equipment.kind()) : List.of());
        needed.forEach(
                (type, codes) ->
                        codes.stream()
                                .filter(code -> access.hidesValue(type, code))
                                .forEach(
                                        code ->
                                                denied.add(
                                                        "계산에 필요한 %s의 %s 값을 볼 권한이 없습니다."
                                                                .formatted(type, code))));
        denied.sort(null);
        return denied;
    }

    /** 작업지시 하나의 남은 공정과 공정별 소요 시간. 값이 없으면 이유를 missing에 담는다. */
    private Plan plan(
            ObjectRow order,
            List<ObjectRow> operations,
            List<ObjectRow> routings,
            Set<String> sequences,
            Machine target,
            Visibility visibility,
            Cursor cursor,
            List<String> missing,
            Set<Long> used) {
        CompletionEstimateProperties.WorkOrder workOrder = properties.workOrder();
        CompletionEstimateProperties.Operation operation = properties.operation();
        CompletionEstimateProperties.Routing routing = properties.routing();
        String orderNo = order.text(workOrder.number());
        String product = order.text(workOrder.product());

        List<ObjectRow> remaining =
                operations.stream()
                        .filter(op -> Objects.equals(op.text(operation.workOrder()), orderNo))
                        .filter(
                                op ->
                                        sequences.isEmpty()
                                                || sequences.contains(
                                                        op.text(operation.sequence())))
                        .filter(op -> !finished(op.text(operation.status())))
                        .sorted(bySequence(operation.sequence()))
                        .toList();
        if (!sequences.isEmpty()) {
            // 요청한 공정이 없거나 이미 끝났으면 남은 공정만 조용히 계산하지 않고 알린다.
            Set<String> found = new LinkedHashSet<>();
            remaining.forEach(op -> found.add(op.text(operation.sequence())));
            sequences.stream()
                    .filter(sequence -> !found.contains(sequence))
                    .forEach(
                            sequence ->
                                    missing.add(
                                            "작업지시 %s에 끝나지 않은 %s 공정이 없습니다."
                                                    .formatted(orderNo, sequence)));
        } else if (remaining.isEmpty()) {
            missing.add("작업지시 %s에 끝나지 않은 공정이 없습니다.".formatted(orderNo));
        }
        if (remaining.isEmpty()) {
            return new Plan(
                    order.id(), orderNo, product, List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<OperationEstimate> estimates = new ArrayList<>();
        BigDecimal processing = BigDecimal.ZERO;
        BigDecimal setup = BigDecimal.ZERO;
        int missingBefore = missing.size();
        for (ObjectRow op : remaining) {
            String sequence = op.text(operation.sequence());
            ObjectRow standard =
                    routings.stream()
                            .filter(r -> Objects.equals(r.text(routing.product()), product))
                            .filter(r -> Objects.equals(r.text(routing.sequence()), sequence))
                            .findFirst()
                            .orElse(null);
            if (standard == null) {
                used.add(op.id());
                missing.add(
                        "작업지시 %s의 %s 공정 표준(제품 %s)이 없습니다.".formatted(orderNo, sequence, product));
                continue;
            }
            // 설비를 정했으면 그 설비 종류로 하는 공정만 센다(예: 프레스에서 찍으면 성형·트리밍만, 검사는 뺀다).
            if (target != null) {
                String requiredKind = standard.text(routing.equipmentKind());
                if (requiredKind == null) {
                    used.add(op.id());
                    used.add(standard.id());
                    missing.add(
                            "제품 %s의 %s 공정 표준에 설비 종류(%s) 값이 없어 이 설비로 하는 공정인지 알 수 없습니다."
                                    .formatted(product, sequence, routing.equipmentKind()));
                    continue;
                }
                if (!target.kind().equals(requiredKind)) {
                    if (!sequences.isEmpty()) {
                        missing.add(
                                "작업지시 %s의 %s 공정은 설비 종류 %s로 하는 공정이 아닙니다(필요: %s)."
                                        .formatted(orderNo, sequence, target.kind(), requiredKind));
                    }
                    continue;
                }
            }
            used.add(op.id());
            used.add(standard.id());
            boolean first = cursor.first;
            cursor.first = false;
            BigDecimal quantity =
                    nonNegative(
                            op,
                            operation.remainingQuantity(),
                            "작업지시 %s의 %s 공정 남은 수량".formatted(orderNo, sequence),
                            missing);
            BigDecimal cycle =
                    nonNegative(
                            standard,
                            routing.cycleSeconds(),
                            "제품 %s의 %s 공정 개당 표준 가공 시간".formatted(product, sequence),
                            missing);
            BigDecimal standardSetup =
                    nonNegative(
                            standard,
                            routing.setupMinutes(),
                            "제품 %s의 %s 공정 표준 준비 시간".formatted(product, sequence),
                            missing);
            String status = op.text(operation.status());
            if (status == null) {
                missing.add(
                        "작업지시 %s의 %s 공정 상태(%s) 값이 없습니다."
                                .formatted(orderNo, sequence, operation.status()));
            }
            if (quantity == null || cycle == null || standardSetup == null || status == null) {
                continue;
            }
            String assigned = visibility.text(op, operation.type(), operation.equipment());
            BigDecimal appliedSetup =
                    keepsSetup(first, status, assigned, target, cursor)
                            ? BigDecimal.ZERO
                            : standardSetup;
            BigDecimal seconds =
                    quantity.multiply(cycle).add(appliedSetup.multiply(SECONDS_PER_MINUTE));
            processing = processing.add(quantity.multiply(cycle));
            setup = setup.add(appliedSetup);
            estimates.add(
                    new OperationEstimate(
                            sequence,
                            assigned,
                            status,
                            quantity,
                            cycle,
                            appliedSetup,
                            hours(seconds)));
        }
        if (target != null && estimates.isEmpty() && missing.size() == missingBefore) {
            missing.add("작업지시 %s에 설비 종류 %s로 하는 끝나지 않은 공정이 없습니다.".formatted(orderNo, target.kind()));
        }
        return new Plan(order.id(), orderNo, product, List.copyOf(estimates), processing, setup);
    }

    /** 상태 값이 없으면 끝난 공정으로 보지 않고 계산 대상에 넣어 빠진 값으로 알린다. */
    private boolean finished(String status) {
        return status != null && properties.operation().finishedStatuses().contains(status);
    }

    /**
     * 계산 순서상 맨 처음 공정이 진행 중이고 같은 설비에서 이어서 하면 준비를 마쳤다고 본다. 설비를 정하지 않으면 지금 설비에서 이어서 한다고 본다. 앞에 다른 공정이
     * 있으면 금형을 바꾸므로 준비 시간을 넣는다. 정한 설비와 지금 설비가 다르거나 둘 중 하나를 볼 수 없으면 준비 시간을 넣는다(늦은 쪽).
     */
    private boolean keepsSetup(
            boolean first, String status, String assigned, Machine target, Cursor cursor) {
        if (!first || !properties.operation().runningStatuses().contains(status)) {
            return false;
        }
        if (target == null) {
            return true;
        }
        if (target.code() == null || assigned == null) {
            cursor.unknownEquipment = true;
            return false;
        }
        return target.code().equals(assigned);
    }

    /** 숫자 값을 읽는다. 없거나 숫자가 아니거나 음수면 이유를 missing에 담고 null을 돌려준다. */
    private static BigDecimal nonNegative(
            ObjectRow row, String code, String label, List<String> missing) {
        BigDecimal value = row.number(code);
        if (value == null) {
            missing.add("%s(%s) 값이 없습니다.".formatted(label, code));
            return null;
        }
        if (value.signum() < 0) {
            missing.add("%s(%s) 값이 음수입니다: %s".formatted(label, code, value.toPlainString()));
            return null;
        }
        return value;
    }

    /** 공정 순서는 숫자로 비교한다("10" < "20" < "100"). 숫자가 아니면 뒤에 문자 순서로 둔다. */
    private static Comparator<ObjectRow> bySequence(String code) {
        return Comparator.comparing(
                        (ObjectRow row) -> row.number(code),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                        row -> row.text(code), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Estimate notComputed(
            AgentResultCategory category,
            Instant startAt,
            String machineLabel,
            List<String> reasons,
            Set<Long> used,
            Visibility visibility) {
        return new Estimate(
                category,
                new Result(
                        AgentQuerySession.inUserZone(startAt),
                        machineLabel,
                        null,
                        null,
                        List.of(),
                        ASSUMPTIONS,
                        List.copyOf(reasons),
                        visibility.restricted()),
                used);
    }

    /** 시작 시각에서 초를 더한 시각. 소수 초는 올려 늦은 쪽으로 둔다. */
    private static String at(Instant startAt, BigDecimal seconds) {
        long whole = seconds.setScale(0, RoundingMode.CEILING).longValueExact();
        return AgentQuerySession.inUserZone(startAt.plusSeconds(whole));
    }

    /** 초를 시간으로 바꿔 소수 첫째 자리까지 둔다. */
    private static BigDecimal hours(BigDecimal seconds) {
        return seconds.divide(SECONDS_PER_HOUR, 1, RoundingMode.HALF_UP);
    }

    private record Plan(
            long workOrderId,
            String workOrderNo,
            String productCode,
            List<OperationEstimate> operations,
            BigDecimal processingSeconds,
            BigDecimal setupMinutes) {

        BigDecimal seconds() {
            return processingSeconds.add(setupMinutes.multiply(SECONDS_PER_MINUTE));
        }

        WorkOrderEstimate toEstimate(String estimatedCompletionAt) {
            return new WorkOrderEstimate(
                    workOrderId,
                    workOrderNo,
                    productCode,
                    operations,
                    hours(processingSeconds),
                    setupMinutes,
                    hours(seconds()),
                    estimatedCompletionAt);
        }
    }

    /** 공정 순서 값을 객체 속성과 같은 모양으로 맞춘다("20.0" → "20"). 숫자가 아니면 앞뒤 공백만 뺀다. */
    static Set<String> normalizeSequences(Collection<String> sequences) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String sequence : sequences) {
            if (sequence == null || sequence.isBlank()) {
                throw new IllegalArgumentException("operationSequences에 빈 값이 있습니다.");
            }
            String value = sequence.strip();
            try {
                normalized.add(new BigDecimal(value).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException e) {
                normalized.add(value);
            }
        }
        return normalized;
    }
}

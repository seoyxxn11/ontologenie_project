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
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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
public class EquipmentCapabilityComparer {
    static final int MAX_EQUIPMENT = 5;
    static final int MAX_CANDIDATES = 10;
    static final String MET = "충족";
    static final String NOT_MET = "미충족";
    static final String UNKNOWN = "미확인";
    static final String NOT_REQUIRED = "조건 없음";
    static final List<String> NOTES =
            List.of(
                    "설비 종류와 최소 능력(톤)만 공정 표준과 비교했다. 금형 호환, 착수 순서·일정, 설비 가동 상태는 판단하지 않았다. 납기는 충족한 설비의 queue에서"
                            + " 계획 시작일이 이미 납기보다 늦은 작업만 dueNotes로 알린다.",
                    "충족은 설비 종류·능력 조건을 맞춘다는 뜻이지 생산 가능이 확정됐다는 뜻이 아니다.",
                    "재배정 계획은 저장하지 않았다. 계획을 저장하면 그때의 데이터로 다시 검증한다.");

    private final ReassignmentObjectRepository objects;
    private final ReassignmentProperties properties;
    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectDetailQuery details;

    /**
     * queue 작업지시의 계획 시작일 속성과 연결 객체(수주)의 납기 속성 코드(418). 계획 시작일이 납기보다 늦으면 dueNotes로 알린다. 날짜를 AI가 비교하지
     * 않고 넘어가 납기 위험 수주를 빠뜨린 일이 있었다(gold S4). 완료 시각 계산처럼 임시 설정이며, 업무 역할 묶기를 도입하면 바꾼다. 비우면 알리지 않는다.
     */
    private final String plannedStartProperty;

    private final String dueDateProperty;

    public EquipmentCapabilityComparer(
            ReassignmentObjectRepository objects,
            ReassignmentProperties properties,
            DataAccessPolicy dataAccessPolicy,
            ObjectDetailQuery details,
            @Value("${axcore.agent.capability-compare.planned-start:}") String plannedStartProperty,
            @Value("${axcore.agent.capability-compare.due-date:}") String dueDateProperty) {
        this.objects = objects;
        this.properties = properties;
        this.dataAccessPolicy = dataAccessPolicy;
        this.details = details;
        this.plannedStartProperty = blankToNull(plannedStartProperty);
        this.dueDateProperty = blankToNull(dueDateProperty);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 비교 결과. objectIds는 비교에 읽은 작업지시·설비·공정·공정 표준 객체로, 출처와 기준 시점에 쓴다. */
    public record Comparison(AgentResultCategory category, Result result, Set<Long> objectIds) {}

    /**
     * AI에게 넘기는 결과. 비교하지 못했으면 operations·equipment가 비고 missingInputs에 이유가 있다. operations는 어느 설비든 비교한
     * 공정의 조건이다. restrictedPropertyCodes는 볼 권한이 없어 비운 값이다("유형코드.속성코드"). workOrderLinked는 옮길 작업지시 자신에
     * 연결된 납기 속성 유형 객체(수주 등)다(418).
     */
    public record Result(
            String workOrderNo,
            String productCode,
            String workOrderStatus,
            List<OperationRequirement> operations,
            List<EquipmentResult> equipment,
            List<String> notes,
            List<String> missingInputs,
            List<String> restrictedPropertyCodes,
            List<LinkedObject> workOrderLinked) {}

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
            String note,
            List<QueuedWork> queue,
            Integer queueTotal) {}

    /**
     * 조건을 충족한 대체 설비에 지금 배정된 끝나지 않은 다른 작업지시(418). 충족한 설비 앞 {@value #MAX_QUEUED_EQUIPMENT}대만 채우고 다른
     * 설비는 queue가 비고 queueTotal이 null이다. queueTotal은 잘리기 전 작업지시 수다. linkedTotal은 잘리기 전 연결 객체 수다. 옮기면
     * 이 작업들이 밀릴 수 있다. properties는 객체 상세(077)와 같이 가린 작업지시 속성(계획 시작일·종료일·상태 등)이다. 수주와 납기는 이 작업지시에서
     * 관계로 찾는다.
     */
    public record QueuedWork(
            long workOrderId,
            String workOrder,
            Map<String, Object> properties,
            List<String> restrictedPropertyCodes,
            List<LinkedObject> linked,
            int linkedTotal,
            List<String> dueNotes) {}

    /**
     * queue 작업지시에 연결된 객체 중 납기 속성을 가진 유형의 것(수주 등). properties는 객체 상세와 같이 가린 속성이고,
     * restrictedPropertyCodes는 볼 권한이 없어 비운 속성 코드다. 실적·인수인계처럼 납기와 관계없는 기록은 넣지 않는다.
     */
    public record LinkedObject(
            long id,
            String type,
            String name,
            Map<String, Object> properties,
            List<String> restrictedPropertyCodes) {}

    static final int MAX_LINKED = 5;

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

        if (equipmentIds.isEmpty()) {
            // 후보를 고르기 전에 작업지시 유형과 권한을 먼저 본다. 다른 유형 ID나 볼 수 없는 값을 "후보 없음"으로 답하지 않게 한다.
            ObjectRow given = objects.findByIds(access, Set.of(workOrderId)).get(workOrderId);
            rejectOtherType(given, workOrder.type(), "workOrderId", "작업지시", access);
            ObjectRow visibleOrder = visible(given, workOrder.type(), access);
            if (visibleOrder == null) {
                return notCompared(
                        AgentResultCategory.NO_DATA,
                        null,
                        null,
                        null,
                        List.of("작업지시(id=%d)를 찾을 수 없습니다.".formatted(workOrderId)),
                        new LinkedHashSet<>(),
                        visibility);
            }
            List<String> denied = deniedInputs(access);
            if (!denied.isEmpty()) {
                return notCompared(
                        AgentResultCategory.DENIED,
                        null,
                        null,
                        null,
                        denied,
                        new LinkedHashSet<>(Set.of(visibleOrder.id())),
                        visibility);
            }
            equipmentIds = candidates(access, visibility, visibleOrder, sequences);
            if (equipmentIds.isEmpty()) {
                return notCompared(
                        AgentResultCategory.NO_DATA,
                        null,
                        null,
                        null,
                        List.of(
                                "후보 설비를 찾지 못했습니다. 작업지시의 남은 공정에 필요한 설비 종류를 공정 표준에서 찾지 못했거나, 그 종류의"
                                        + " 다른 설비가 없습니다."),
                        new LinkedHashSet<>(),
                        visibility);
            }
        }
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
        if (order == null || !missing.isEmpty()) {
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
        List<ObjectRow> orderOperations =
                objects.findActiveByProperty(
                        access, operation.type(), operation.workOrder(), Set.of(orderNo));
        List<Step> steps = steps(orderOperations, routings, product);
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
                            note,
                            List.of(),
                            null));
        }
        Map<Long, ObjectRow> machineRows = new HashMap<>();
        machines.forEach(machine -> machineRows.put(machine.id(), machine));
        List<EquipmentResult> withQueues = new ArrayList<>();
        int queued = 0;
        for (EquipmentResult result : results) {
            if (!MET.equals(result.verdict()) || queued >= MAX_QUEUED_EQUIPMENT) {
                withQueues.add(result);
                continue;
            }
            queued++;
            Queue queue =
                    queue(
                            asker,
                            access,
                            visibility,
                            machineRows.get(result.equipmentId()),
                            order.id());
            queue.works()
                    .forEach(
                            work -> {
                                used.add(work.workOrderId());
                                work.linked().forEach(linkedObject -> used.add(linkedObject.id()));
                            });
            withQueues.add(
                    new EquipmentResult(
                            result.equipmentId(),
                            result.equipment(),
                            result.kind(),
                            result.capacity(),
                            result.verdict(),
                            result.checks(),
                            result.excludedOperations(),
                            result.note(),
                            queue.works(),
                            queue.total()));
        }
        results = withQueues;

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
        // 옮길 작업지시 자신의 수주·납기도 준다. 멈춘 설비의 수주를 "확인되지 않았다"고 답하지 않고 납기 안인지 구분하게 한다(418 S4).
        Linked own =
                linked(
                        asker,
                        order.id(),
                        orderOperations.stream().map(ObjectRow::id).collect(Collectors.toSet()));
        own.objects().forEach(object -> used.add(object.id()));
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
                        visibility.restricted(),
                        own.objects()),
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
     * 후보 설비를 비웠을 때 서버가 고르는 후보(418). 비교할 공정(공정을 정하지 않으면 끝나지 않은 공정)의 공정 표준이 요구하는 설비 종류와 같은 종류의 설비 중,
     * 그 공정이 지금 배정된 설비를 뺀 것을 id 순으로 {@value #MAX_CANDIDATES}개까지 고른다. 사용자가 후보를 말하지 않은 "옮길 설비를 찾아서"
     * 질문에서 작업지시에 이미 연결된 설비만 비교하고 "옮길 수 없다"고 답한 일이 있었다(2026-11-14 대화 98). 작업지시·공정 표준을 찾지 못하면 빈 목록이다.
     */
    private List<Long> candidates(
            DataAccess access, Visibility visibility, ObjectRow order, Set<String> sequences) {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.Routing routing = properties.routing();
        ReassignmentProperties.Equipment equipment = properties.equipment();
        if (!access.canView(equipment.type())) {
            return List.of();
        }
        String orderNo = order.text(workOrder.number());
        String product = order.text(workOrder.product());
        if (orderNo == null || product == null) {
            return List.of();
        }
        List<Step> steps =
                steps(
                        objects.findActiveByProperty(
                                access, operation.type(), operation.workOrder(), Set.of(orderNo)),
                        objects.findActiveByProperty(
                                access, routing.type(), routing.product(), Set.of(product)),
                        product);
        Set<String> kinds = new LinkedHashSet<>();
        Set<String> assigned = new LinkedHashSet<>();
        for (Step step : steps) {
            boolean chosen =
                    sequences.isEmpty()
                            ? !finished(step.operation())
                            : sequences.contains(step.sequence());
            if (!chosen || step.standard() == null) {
                continue;
            }
            String kind = step.standard().text(routing.equipmentKind());
            if (kind != null && !kind.isBlank()) {
                kinds.add(kind);
            }
            String code =
                    visibility.text(step.operation(), operation.type(), operation.equipment());
            if (code != null) {
                assigned.add(code);
            }
        }
        if (kinds.isEmpty()) {
            return List.of();
        }
        return objects
                .findActiveByProperty(access, equipment.type(), equipment.kind(), kinds)
                .stream()
                .filter(row -> !assigned.contains(row.text(equipment.code())))
                .map(ObjectRow::id)
                .sorted()
                .distinct()
                .limit(MAX_CANDIDATES)
                .toList();
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

    static final int MAX_QUEUE = 5;

    /** 멈춘 설비에서 서버가 미리 비교할 작업지시 수(418 S4). */
    static final int MAX_STOPPED_WORK = 3;

    /** 멈춘 설비에서 미리 비교할 작업지시와 그 설비에 배정된 끝나지 않은 공정 순서. */
    public record StoppedWork(long workOrderId, List<String> sequences) {}

    /** 미리 비교할 작업지시(id 순 {@value #MAX_STOPPED_WORK}개까지)와 잘리기 전 전체 수. */
    public record StoppedWorks(List<StoppedWork> works, int total) {
        static final StoppedWorks NONE = new StoppedWorks(List.of(), 0);
    }

    /** 객체가 질문자가 볼 수 있는 설비인지. 서버가 미리 비교할 참고 객체를 고를 때 쓴다(418 S4). */
    @Transactional(readOnly = true)
    public boolean isEquipment(LoginAccount asker, long objectId) {
        DataAccess access = dataAccessPolicy.forModel(asker);
        return visible(
                        objects.findByIds(access, Set.of(objectId)).get(objectId),
                        properties.equipment().type(),
                        access)
                != null;
    }

    /**
     * 설비에 배정된 끝나지 않은 공정의 작업지시와 그 공정 순서. "설비가 멈춰서 납기가 위험한 수주" 질문에서 서버가 대체 설비 비교를 먼저 부르려고 쓴다(418 S4).
     * AI가 비교 도구를 부르지 않고 멈춘 설비의 수주만 답한 일이 3번 중 1번 있었다. 공정 순서를 함께 주는 것은 같은 작업지시의 다른 설비 공정까지 비교하지 않게
     * 하려는 것이다. 설비가 아니거나 볼 수 없거나 설비 코드·공정·작업지시를 볼 수 없으면 비어 있다.
     */
    @Transactional(readOnly = true)
    public StoppedWorks unfinishedWorkOrders(LoginAccount asker, long equipmentId) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        DataAccess access = dataAccessPolicy.forModel(asker);
        Visibility visibility = new Visibility(access);
        ObjectRow machine =
                visible(
                        objects.findByIds(access, Set.of(equipmentId)).get(equipmentId),
                        equipment.type(),
                        access);
        String code =
                machine == null
                        ? null
                        : visibility.text(machine, equipment.type(), equipment.code());
        if (code == null
                || !access.canView(operation.type())
                || !access.canView(workOrder.type())) {
            return StoppedWorks.NONE;
        }
        Map<String, Set<String>> sequences = unfinishedOrderNumbers(access, code);
        if (sequences.isEmpty()) {
            return StoppedWorks.NONE;
        }
        List<ObjectRow> orders =
                objects
                        .findActiveByProperty(
                                access, workOrder.type(), workOrder.number(), sequences.keySet())
                        .stream()
                        .sorted(Comparator.comparingLong(ObjectRow::id))
                        .toList();
        List<StoppedWork> works =
                orders.stream()
                        .limit(MAX_STOPPED_WORK)
                        .map(
                                row ->
                                        new StoppedWork(
                                                row.id(),
                                                List.copyOf(
                                                        sequences.getOrDefault(
                                                                row.text(workOrder.number()),
                                                                Set.of()))))
                        .toList();
        return new StoppedWorks(works, orders.size());
    }

    /** 그 설비 코드로 배정된 끝나지 않은 공정의 작업지시 번호와 공정 순서(순서를 모르면 빈 집합). */
    private Map<String, Set<String>> unfinishedOrderNumbers(
            DataAccess access, String equipmentCode) {
        ReassignmentProperties.Operation operation = properties.operation();
        Map<String, Set<String>> numbers = new LinkedHashMap<>();
        for (ObjectRow op :
                objects.findActiveByProperty(
                        access, operation.type(), operation.equipment(), Set.of(equipmentCode))) {
            String number = op.text(operation.workOrder());
            if (number != null && !finished(op)) {
                Set<String> found = numbers.computeIfAbsent(number, key -> new TreeSet<>());
                String sequence = op.text(operation.sequence());
                if (sequence != null && !sequence.isBlank()) {
                    found.add(sequence.strip());
                }
            }
        }
        return numbers;
    }

    /** queue를 채우는 충족 설비 수. 설비마다 작업지시 상세·관계 조회가 붙어 도구 한 번의 조회 수와 결과 크기를 제한한다. */
    static final int MAX_QUEUED_EQUIPMENT = 3;

    private record Queue(List<QueuedWork> works, int total) {
        static final Queue EMPTY = new Queue(List.of(), 0);
    }

    /**
     * 대체 설비 하나에 배정된 끝나지 않은 공정의 작업지시(옮길 작업지시 제외). 계획 시작일이 이른 순(값이 없거나 가려지면 뒤, 같으면 id 순)으로 {@value
     * #MAX_QUEUE}개까지 상세와 연결을 붙인다. 설비가 멈춰 작업을 옮기면 이 작업들이 밀린다. 계획 시작일이 이미 납기보다 늦은 작업지시를 찾지 못하고 멈춘 설비의
     * 수주만 답한 일이 있었다(418, gold S4: JB2610-0145 대기·납기 10-31).
     */
    private Queue queue(
            LoginAccount asker,
            DataAccess access,
            Visibility visibility,
            ObjectRow machine,
            long movingOrderId) {
        ReassignmentProperties.Equipment equipment = properties.equipment();
        ReassignmentProperties.Operation operation = properties.operation();
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        String code =
                machine == null
                        ? null
                        : visibility.text(machine, equipment.type(), equipment.code());
        if (code == null
                || !access.canView(operation.type())
                || !access.canView(workOrder.type())) {
            return Queue.EMPTY;
        }
        Set<String> numbers = unfinishedOrderNumbers(access, code).keySet();
        if (numbers.isEmpty()) {
            return Queue.EMPTY;
        }
        // 공정 배정은 연결에서 뺀다. 수주처럼 납기를 가진 객체를 남기려는 것이다.
        Set<Long> operationIds = new LinkedHashSet<>();
        objects.findActiveByProperty(access, operation.type(), operation.workOrder(), numbers)
                .forEach(op -> operationIds.add(op.id()));
        List<ObjectRow> orders =
                objects
                        .findActiveByProperty(access, workOrder.type(), workOrder.number(), numbers)
                        .stream()
                        .filter(row -> row.id() != movingOrderId)
                        .sorted(
                                Comparator.comparing(
                                                (ObjectRow row) ->
                                                        plannedStartProperty == null
                                                                ? null
                                                                : date(
                                                                        visibility.text(
                                                                                row,
                                                                                workOrder.type(),
                                                                                plannedStartProperty)),
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparingLong(ObjectRow::id))
                        .toList();
        List<QueuedWork> queue = new ArrayList<>();
        for (ObjectRow row : orders.subList(0, Math.min(MAX_QUEUE, orders.size()))) {
            ObjectDetailResponse shown;
            try {
                shown = details.detail(asker, DataPurpose.MODEL, row.id());
            } catch (BusinessException e) {
                continue;
            }
            Linked linked = linked(asker, row.id(), operationIds);
            List<String> restricted = codes(shown.restrictedPropertyCodes());
            queue.add(
                    new QueuedWork(
                            row.id(),
                            row.text(workOrder.number()),
                            shown.properties(),
                            restricted,
                            linked.objects(),
                            linked.total(),
                            dueNotes(
                                    row.text(workOrder.number()),
                                    shown.properties(),
                                    restricted,
                                    linked.objects())));
        }
        return new Queue(List.copyOf(queue), orders.size());
    }

    /** 계획 시작일이 연결 객체의 납기보다 늦으면 그 사실을 문장으로 준다. 날짜를 읽지 못하거나 가려졌으면 넘어간다. */
    private List<String> dueNotes(
            String workOrderNo,
            Map<String, Object> properties,
            List<String> restricted,
            List<LinkedObject> linked) {
        if (plannedStartProperty == null || dueDateProperty == null) {
            return List.of();
        }
        // 가린 날짜는 "없음"이 아니다. 비교하지 못했다고 알려 "납기 위험 없음"으로 답하지 않게 한다.
        if (restricted.contains(plannedStartProperty)) {
            return List.of("%s의 계획 시작일을 볼 권한이 없어 납기와 비교하지 못했습니다.".formatted(workOrderNo));
        }
        LocalDate start = date(properties.get(plannedStartProperty));
        if (start == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (LinkedObject object : linked) {
            if (object.restrictedPropertyCodes().contains(dueDateProperty)) {
                notes.add(
                        "%s %s의 납기를 볼 권한이 없어 %s의 계획 시작일과 비교하지 못했습니다."
                                .formatted(object.type(), object.name(), workOrderNo));
                continue;
            }
            LocalDate due = date(object.properties().get(dueDateProperty));
            if (due != null && start.isAfter(due)) {
                notes.add(
                        "%s의 계획 시작일 %s이 %s %s의 납기 %s보다 늦습니다. 이 설비로 다른 작업을 옮기면 더 밀립니다."
                                .formatted(workOrderNo, start, object.type(), object.name(), due));
            }
        }
        return List.copyOf(notes);
    }

    private static LocalDate date(Object value) {
        if (!(value instanceof String text) || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 작업지시에 연결된 객체 중 공정 배정이 아닌 것({@value #MAX_LINKED}개까지, id 순)과 그 가린 속성. 볼 수 없는 상대는 관계 조회가 이미 뺀다.
     */
    private record Linked(List<LinkedObject> objects, int total) {}

    /**
     * 작업지시에 연결된 객체 중 공정 배정이 아니고 납기 속성을 가진 유형의 것(id 순 {@value #MAX_LINKED}개까지)과 그 가린 속성. 유형마다 첫 객체를
     * 상세 조회해 납기 속성이 있는지 보고, 없으면 그 유형은 더 읽지 않는다. 실적·자재 출고처럼 연결이 많은 작업지시에서 수주가 id 순 앞 몇 개에 들지 못하거나
     * 납기와 관계없는 기록이 모델로 가지 않게 한다. total은 잘리기 전 납기 유형 객체 수다. 볼 수 없는 상대는 관계 조회가 이미 뺀다. 납기 속성을 정하지 않았으면
     * 비어 있다.
     */
    private Linked linked(LoginAccount asker, long workOrderId, Set<Long> operationIds) {
        if (dueDateProperty == null) {
            return new Linked(List.of(), 0);
        }
        List<ObjectLinkResponse> links;
        try {
            links =
                    details.links(
                                    asker,
                                    DataPurpose.MODEL,
                                    workOrderId,
                                    new ObjectLinkSearchRequest(
                                            null, ObjectLinkSearchRequest.Direction.BOTH, 0, 100))
                            .getContent();
        } catch (BusinessException e) {
            return new Linked(List.of(), 0);
        }
        List<ObjectLinkResponse.RelatedObject> others =
                links.stream()
                        .map(ObjectLinkResponse::relatedObject)
                        .filter(object -> !operationIds.contains(object.id()))
                        .sorted(Comparator.comparingLong(ObjectLinkResponse.RelatedObject::id))
                        .distinct()
                        .toList();
        Map<Long, Boolean> dueTypes = new HashMap<>();
        List<LinkedObject> linked = new ArrayList<>();
        int total = 0;
        for (ObjectLinkResponse.RelatedObject related : others) {
            Boolean hasDue = dueTypes.get(related.objectTypeId());
            if (Boolean.FALSE.equals(hasDue)) {
                continue;
            }
            if (Boolean.TRUE.equals(hasDue) && linked.size() >= MAX_LINKED) {
                total++;
                continue;
            }
            ObjectDetailResponse shown;
            try {
                shown = details.detail(asker, DataPurpose.MODEL, related.id());
            } catch (BusinessException e) {
                continue;
            }
            List<String> restricted = codes(shown.restrictedPropertyCodes());
            boolean due =
                    shown.properties().containsKey(dueDateProperty)
                            || restricted.contains(dueDateProperty);
            dueTypes.putIfAbsent(related.objectTypeId(), due);
            if (!due) {
                continue;
            }
            total++;
            if (linked.size() < MAX_LINKED) {
                linked.add(
                        new LinkedObject(
                                related.id(),
                                related.objectTypeName(),
                                related.displayName(),
                                shown.properties(),
                                restricted));
            }
        }
        return new Linked(List.copyOf(linked), total);
    }

    private static List<String> codes(List<String> codes) {
        return codes == null ? List.of() : List.copyOf(codes);
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
                        visibility.restricted(),
                        List.of()),
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

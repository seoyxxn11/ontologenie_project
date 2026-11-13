package com.axcore.platform.action.handler;

import com.axcore.platform.action.handler.ReassignmentPlanValidator.ValidatedItem;
import com.axcore.platform.action.repository.ReassignmentObjectRepository.ObjectRow;
import com.axcore.platform.action.repository.ReassignmentPlanRepository;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.service.RecordedPropertyPaths;
import com.axcore.platform.auth.service.DataAccessPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재배정 계획 생성 (CREATE_REASSIGNMENT_PLAN, FN-ACT-04·05, Jira 208·209). 업무 조건을 통과하면 계획(CREATED)과 항목을
 * 저장한다. 승인 절차는 없고 원천 MES 배정은 바꾸지 않는다(결정 O3). 항목의 source_snapshot에는 계획 시점의 작업지시 상태와 옮길 공정의 배정·상태를
 * 남긴다.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ReassignmentProperties.class)
public class ReassignmentPlanHandler implements ActionHandler, RecordedPropertyPaths {

    static final String KEY = "CREATE_REASSIGNMENT_PLAN";
    private static final String PLAN_TABLE = "reassignment_plan";
    private static final String ITEM_TABLE = "reassignment_plan_item";
    private static final String OPERATION_CODE = "operation_code";
    private static final String SNAPSHOT = "source_snapshot";
    private static final String WORK_ORDER_NO = "workOrderNo";
    private static final String WORK_ORDER_STATUS = "workOrderStatus";
    private static final String OPERATIONS = "operations";
    private static final String SEQUENCE = "sequence";
    private static final String EQUIPMENT_CODE = "equipmentCode";
    private static final String STATUS = "status";

    private final ReassignmentPlanValidator validator;
    private final DataAccessPolicy dataAccessPolicy;
    private final ReassignmentPlanRepository plans;
    private final AgentMessageRepository agentMessages;
    private final ReassignmentProperties properties;
    private final JsonMapper json;

    @Override
    public String key() {
        return KEY;
    }

    /** 대상은 정지 설비 객체다. 정지 설비의 실행 이력에서 이 계획을 찾을 수 있다. */
    @Override
    public Optional<ActionTarget> target(Map<String, Object> input) {
        if (input == null || !(input.get("fromEquipmentId") instanceof Number id)) {
            return Optional.empty();
        }
        try {
            // 소수이거나 long 범위를 넘으면 대상을 정하지 않는다. 값이 넘쳐 엉뚱한 ID가 기록되지 않게 한다.
            long value = new BigDecimal(id.toString()).longValueExact();
            return Optional.of(new ActionTarget(ActionTarget.OBJECT_INSTANCE, value));
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    @Override
    public ActionPreparation prepare(ActionContext context) {
        ReassignmentPlanInput input =
                json.convertValue(context.input(), ReassignmentPlanInput.class);
        // 실행자의 조회 범위로 검사한다. Agent가 실행하면 문구가 모델로 가므로 모델 용도다(384·390).
        ReassignmentPlanValidator.Result validated =
                validator.validate(
                        input, dataAccessPolicy.of(context.actor(), context.dataPurpose()));
        if (validated.blockedReason() != null) {
            return ActionPreparation.blocked(validated.blockedReason());
        }

        long planId = plans.nextPlanId();
        List<Long> itemIds = plans.nextItemIds(validated.items().size());
        Long conversationId =
                context.agentMessageId() == null
                        ? null
                        : agentMessages
                                .findConversationIdById(context.agentMessageId())
                                .orElse(null);
        Instant dataAsOf = OffsetDateTime.parse(input.dataAsOf()).toInstant();
        Map<String, Object> selectionBasis =
                input.selectionBasis() == null ? Map.of() : input.selectionBasis();
        long fromId = input.fromEquipmentId();
        long createdBy = context.actor().id();

        List<ChangeEntry> changes = new ArrayList<>();
        Map<String, Object> planRow = new LinkedHashMap<>();
        planRow.put("status", "CREATED");
        planRow.put("from_equipment_id", fromId);
        planRow.put("selection_basis", selectionBasis);
        planRow.put("data_as_of", dataAsOf.toString());
        planRow.put("agent_conversation_id", conversationId);
        planRow.put("created_by", createdBy);
        changes.add(ChangeEntry.insert(PLAN_TABLE, Map.of("id", planId), planRow));

        // 변경 기록(entity_change)과 실제 저장이 같은 값을 쓰도록 스냅샷은 한 번만 만든다.
        List<Map<String, Object>> snapshots = new ArrayList<>();
        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (int i = 0; i < validated.items().size(); i++) {
            ValidatedItem item = validated.items().get(i);
            Map<String, Object> snapshot = snapshot(item);
            snapshots.add(snapshot);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan_id", planId);
            row.put("work_order_id", item.item().workOrderId());
            row.put(OPERATION_CODE, item.item().operationCode());
            row.put("from_equipment_id", fromId);
            row.put("to_equipment_id", item.item().toEquipmentId());
            row.put(SNAPSHOT, snapshot);
            itemRows.add(row);
            changes.add(ChangeEntry.insert(ITEM_TABLE, Map.of("id", itemIds.get(i)), row));
        }

        Map<String, Object> result = Map.of("planId", planId, "itemCount", itemRows.size());
        return new ActionPreparation.Ready(
                result,
                changes,
                executionId -> {
                    plans.insertPlan(
                            planId,
                            fromId,
                            selectionBasis,
                            dataAsOf,
                            executionId,
                            conversationId,
                            createdBy);
                    for (int i = 0; i < itemRows.size(); i++) {
                        ValidatedItem item = validated.items().get(i);
                        plans.insertItem(
                                itemIds.get(i),
                                planId,
                                item.item().workOrderId(),
                                item.item().operationCode(),
                                fromId,
                                item.item().toEquipmentId(),
                                snapshots.get(i));
                    }
                });
    }

    /** 계획 시점의 원천 배정·상태(reassignment_plan_item.source_snapshot). */
    private Map<String, Object> snapshot(ValidatedItem item) {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        List<Map<String, Object>> operations = new ArrayList<>();
        for (ObjectRow op : item.operations()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("objectId", op.id());
            value.put(SEQUENCE, op.text(operation.sequence()));
            value.put(EQUIPMENT_CODE, op.text(operation.equipment()));
            value.put(STATUS, op.text(operation.status()));
            operations.add(value);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(WORK_ORDER_NO, item.workOrder().text(workOrder.number()));
        snapshot.put(WORK_ORDER_STATUS, item.workOrder().text(workOrder.status()));
        snapshot.put(OPERATIONS, operations);
        return snapshot;
    }

    /** 입력과 항목 행의 공정 코드, 항목 스냅샷에 든 작업지시·공정 속성 값의 자리. */
    @Override
    public List<Path> recordedPropertyPaths() {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        return List.of(
                Path.input(
                        KEY,
                        List.of("items", "*", "operationCode"),
                        operation.type(),
                        operation.sequence()),
                Path.change(
                        ITEM_TABLE,
                        List.of(OPERATION_CODE),
                        operation.type(),
                        operation.sequence()),
                Path.change(
                        ITEM_TABLE,
                        List.of(SNAPSHOT, WORK_ORDER_NO),
                        workOrder.type(),
                        workOrder.number()),
                Path.change(
                        ITEM_TABLE,
                        List.of(SNAPSHOT, WORK_ORDER_STATUS),
                        workOrder.type(),
                        workOrder.status()),
                Path.change(
                        ITEM_TABLE,
                        List.of(SNAPSHOT, OPERATIONS, "*", SEQUENCE),
                        operation.type(),
                        operation.sequence()),
                Path.change(
                        ITEM_TABLE,
                        List.of(SNAPSHOT, OPERATIONS, "*", EQUIPMENT_CODE),
                        operation.type(),
                        operation.equipment()),
                Path.change(
                        ITEM_TABLE,
                        List.of(SNAPSHOT, OPERATIONS, "*", STATUS),
                        operation.type(),
                        operation.status()));
    }
}

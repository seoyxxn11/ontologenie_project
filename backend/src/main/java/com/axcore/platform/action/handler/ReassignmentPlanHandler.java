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
import java.util.Set;
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
    private static final String WORK_ORDER_NO = ReassignmentSnapshot.WORK_ORDER_NO;
    private static final String WORK_ORDER_STATUS = ReassignmentSnapshot.WORK_ORDER_STATUS;
    private static final String OPERATIONS = ReassignmentSnapshot.OPERATIONS;
    private static final String SEQUENCE = ReassignmentSnapshot.SEQUENCE;
    private static final String EQUIPMENT_CODE = ReassignmentSnapshot.EQUIPMENT_CODE;
    private static final String STATUS = ReassignmentSnapshot.STATUS;
    private static final String OBJECT_ID = ReassignmentSnapshot.OBJECT_ID;
    private static final String REASON = ReassignmentSnapshot.REASON;
    private static final String PLAN_ID = "planId";
    private static final String ITEM_COUNT = "itemCount";

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
        // 실행자가 볼 수 있는 유형만 검사 범위에 넣되, 민감 값은 가리지 않은 원래 값으로 검사하고 스냅샷에 남긴다(404). 가린 값(null)을 "조건
        // 없음"으로 보고 검사를 건너뛰지 않게 한다. 차단 문구는 저장되어 사람·모델에게 가므로 민감 값을 넣지 않는다(검사기가 맡는다).
        ReassignmentPlanValidator.Result validated =
                validator.validate(input, dataAccessPolicy.forCheck(context.actor()));
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

        Map<String, Object> result = Map.of(PLAN_ID, planId, ITEM_COUNT, itemRows.size());
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
            value.put(OBJECT_ID, op.id());
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

    /** 차단 문구에 이름·일반 속성 값이 들어가는 유형. 검사가 읽는 설비·작업지시·공정·공정 표준이다(404). */
    @Override
    public Map<String, Set<String>> blockedReasonObjectTypes() {
        return Map.of(
                KEY,
                Set.of(
                        properties.equipment().type(),
                        properties.workOrder().type(),
                        properties.operation().type(),
                        properties.routing().type()));
    }

    /**
     * 이 Handler가 남기는 기록(입력·결과·변경 행)의 자리 전부(허용 목록, 404). 속성 값은 입력과 항목 행의 공정 코드, 스냅샷의 작업지시·공정 값이다.
     * 나머지는 ID·건수·상태·시각과 선정 근거의 이유 문장이다. 선정 근거의 다른 필드와 여기 없는 자리는 감사 조회에서 가려진다.
     */
    @Override
    public List<Path> recordedPropertyPaths() {
        ReassignmentProperties.WorkOrder workOrder = properties.workOrder();
        ReassignmentProperties.Operation operation = properties.operation();
        return List.of(
                Path.plainInput(KEY, List.of("fromEquipmentId")),
                Path.plainResult(KEY, List.of(PLAN_ID)),
                Path.plainResult(KEY, List.of(ITEM_COUNT)),
                Path.plainInput(KEY, List.of("items", "*", "workOrderId")),
                Path.plainInput(KEY, List.of("items", "*", "toEquipmentId")),
                Path.plainInput(KEY, List.of("selectionBasis", REASON)),
                Path.plainInput(KEY, List.of("dataAsOf")),
                Path.plainChange(PLAN_TABLE, List.of("status")),
                Path.plainChange(PLAN_TABLE, List.of("from_equipment_id")),
                Path.plainChange(PLAN_TABLE, List.of("selection_basis", REASON)),
                Path.plainChange(PLAN_TABLE, List.of("data_as_of")),
                Path.plainChange(PLAN_TABLE, List.of("agent_conversation_id")),
                Path.plainChange(PLAN_TABLE, List.of("created_by")),
                Path.plainChange(ITEM_TABLE, List.of("plan_id")),
                Path.plainChange(ITEM_TABLE, List.of("work_order_id")),
                Path.plainChange(ITEM_TABLE, List.of("from_equipment_id")),
                Path.plainChange(ITEM_TABLE, List.of("to_equipment_id")),
                Path.plainChange(ITEM_TABLE, List.of(SNAPSHOT, OPERATIONS, "*", OBJECT_ID)),
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

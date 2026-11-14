package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditEntityChangeResponse;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.auth.service.DataAccess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 감사 가림은 허용 목록이다(404). 알린 자리만 보이고, 속성 자리는 보는 사람의 범위로 가린다. */
class AuditValueMaskerTests {

    private static final long PLAN_DEFINITION = 1L;
    private static final long NEW_HANDLER_DEFINITION = 2L;
    private static final long RECORD_DEFINITION = 3L;
    private static final long MISSING_DEFINITION = 99L;

    private static final List<Path> PLAN_PATHS =
            List.of(
                    Path.plainInput("PLAN", List.of("fromEquipmentId")),
                    Path.plainInput("PLAN", List.of("items", "*", "workOrderId")),
                    Path.input(
                            "PLAN", List.of("items", "*", "operationCode"), "Operation", "op_seq"),
                    Path.plainResult("PLAN", List.of("planId")),
                    Path.result("PLAN", List.of("assignee"), "Equipment", "manager"),
                    Path.plainChange("plan_item", List.of("work_order_id")),
                    Path.plainChange(
                            "plan_item", List.of("source_snapshot", "operations", "*", "objectId")),
                    Path.change(
                            "plan_item",
                            List.of("source_snapshot", "workOrderNo"),
                            "WorkOrder",
                            "wo_no"),
                    Path.change(
                            "plan_item",
                            List.of("source_snapshot", "operations", "*", "sequence"),
                            "Operation",
                            "op_seq"),
                    Path.change(
                            "plan_item",
                            List.of("source_snapshot", "operations", "*", "status"),
                            "Operation",
                            "status"),
                    Path.plainChange("definition_table", List.of()));

    private final DataAccess access = mock(DataAccess.class);
    private final ActionDefinitionRepository definitions = mock(ActionDefinitionRepository.class);
    private final AuditValueMasker masker =
            new AuditValueMasker(List.of(() -> PLAN_PATHS), definitions);

    @BeforeEach
    void definitions() {
        definition(PLAN_DEFINITION, "PLAN");
        definition(NEW_HANDLER_DEFINITION, "NEW_HANDLER");
        definition(RECORD_DEFINITION, null);
        when(definitions.findById(MISSING_DEFINITION)).thenReturn(Optional.empty());
        when(access.canView(anyString())).thenReturn(true);
    }

    @Test
    void hidesRestrictedValuesInChangesAndKeepsKeys() {
        when(access.hidesValue("WorkOrder", "wo_no")).thenReturn(true);
        when(access.hidesValue("Operation", "status")).thenReturn(true);
        AuditEntityChangeResponse change = itemChange();

        Masked<List<AuditEntityChangeResponse>> masked =
                masker.maskChanges(List.of(change), access);

        Map<String, Object> snapshot = snapshotOf(masked.value().getFirst());
        assertThat(snapshot).containsEntry("workOrderNo", null);
        assertThat((List<?>) snapshot.get("operations"))
                .map(operation -> asMap(operation).get("sequence"))
                .containsExactly("20", "30");
        assertThat((List<?>) snapshot.get("operations"))
                .allSatisfy(
                        operation ->
                                assertThat(asMap(operation))
                                        .containsEntry("status", null)
                                        .containsKey("objectId"));
        assertThat(masked.restrictedPropertyCodes()).containsExactly("status", "wo_no");
        assertThat(masked.value().getFirst().afterData()).containsEntry("work_order_id", 7);
        assertThat(snapshotOf(change)).containsEntry("workOrderNo", "WO-1");
    }

    /** 보는 사람이 볼 수 있어 가리지 않은 민감 자리에 값이 있으면 그 코드를 알린다(394 열람 기록). */
    @Test
    void reportsRestrictedPathsLeftVisible() {
        when(access.revealsValue("WorkOrder", "wo_no")).thenReturn(true);

        Masked<List<AuditEntityChangeResponse>> masked =
                masker.maskChanges(List.of(itemChange()), access);

        assertThat(masked.restrictedPropertyCodes()).isEmpty();
        assertThat(masked.revealedPropertyCodes()).containsExactly("wo_no");
        assertThat(snapshotOf(masked.value().getFirst())).containsEntry("workOrderNo", "WO-1");
    }

    @Test
    void masksInputAndChangesSeparatelyForASingleExecution() {
        when(access.hidesValue("Operation", "op_seq")).thenReturn(true);
        when(access.hidesValue("WorkOrder", "wo_no")).thenReturn(true);

        Masked<Map<String, Object>> input = masker.maskInput(PLAN_DEFINITION, input(), access);
        Masked<List<AuditEntityChangeResponse>> changes =
                masker.maskChanges(List.of(itemChange()), access);

        assertThat((List<?>) input.value().get("items"))
                .allSatisfy(
                        item ->
                                assertThat(asMap(item))
                                        .containsEntry("operationCode", null)
                                        .containsEntry("workOrderId", 7));
        assertThat(input.restrictedPropertyCodes()).containsExactly("op_seq");
        assertThat(asMap(changes.value().getFirst().afterData().get("source_snapshot")))
                .containsEntry("workOrderNo", null);
        assertThat(changes.restrictedPropertyCodes()).containsExactly("op_seq", "wo_no");
        assertThat(masker.maskInput(null, null, access))
                .isEqualTo(new Masked<Map<String, Object>>(null, List.of(), List.of()));
    }

    /** 404 완료 기준: 새 Handler가 자리를 알리지 않으면 입력 값이 모두 가려진다. 키는 남는다. */
    @Test
    void hidesEveryInputValueOfAHandlerThatDeclaredNothing() {
        Masked<Map<String, Object>> plan = masker.maskInput(PLAN_DEFINITION, input(), access);
        Masked<Map<String, Object>> newHandler =
                masker.maskInput(NEW_HANDLER_DEFINITION, input(), access);
        Masked<Map<String, Object>> missing = masker.maskInput(MISSING_DEFINITION, input(), access);

        assertThat(plan.value())
                .containsEntry("fromEquipmentId", 301)
                .containsEntry("secret", null);
        assertThat(newHandler.value())
                .containsOnlyKeys("fromEquipmentId", "items", "secret")
                .containsEntry("fromEquipmentId", null)
                .containsEntry("items", null)
                .containsEntry("secret", null);
        assertThat(newHandler.restrictedPropertyCodes()).isEmpty();
        assertThat(missing.value()).isEqualTo(newHandler.value());
    }

    /** 알리지 않은 표의 변경은 전후 값이 모두 가려진다. 키와 행 키는 남는다. */
    @Test
    void hidesEveryValueOfAnUndeclaredTable() {
        AuditEntityChangeResponse change =
                new AuditEntityChangeResponse(
                        "new_table",
                        Map.of("id", 5),
                        ChangeKind.UPDATE,
                        Map.of("technician", "T-KIM"),
                        Map.of("technician", "T-LEE"));

        AuditEntityChangeResponse masked =
                masker.maskChanges(List.of(change), access).value().getFirst();

        assertThat(masked.rowKey()).isEqualTo(Map.of("id", 5));
        assertThat(masked.beforeData()).containsEntry("technician", null);
        assertThat(masked.afterData()).containsEntry("technician", null);
        assertThat(masker.maskRow("new_table", Map.of("technician", "T-KIM"), access).value())
                .containsEntry("technician", null);
    }

    /** 알린 기록 안에서도 알리지 않은 자리(스냅샷에 새로 넣은 필드 등)는 가린다. */
    @Test
    void hidesUndeclaredSlotsInsideADeclaredRecord() {
        Map<String, Object> row = new HashMap<>(itemChange().afterData());
        row.put("note", "기록만 된 값");
        Map<String, Object> snapshot = new HashMap<>(asMap(row.get("source_snapshot")));
        snapshot.put("technician", "T-KIM");
        row.put("source_snapshot", snapshot);

        Map<String, Object> masked = masker.maskRow("plan_item", row, access).value();

        assertThat(masked).containsEntry("note", null).containsEntry("work_order_id", 7);
        assertThat(asMap(masked.get("source_snapshot")))
                .containsEntry("technician", null)
                .containsEntry("workOrderNo", "WO-1");
    }

    /** 관리 Action(Handler 없음)과 정의가 없는 기록의 입력, 행 전체를 알린 표는 그대로다. */
    @Test
    void keepsRecordModeInputAndWholePlainTables() {
        AuditEntityChangeResponse definitionChange =
                new AuditEntityChangeResponse(
                        "definition_table",
                        Map.of("id", 1),
                        ChangeKind.INSERT,
                        null,
                        Map.of("code", "WORK_ORDER", "nested", Map.of("name", "작업지시")));

        assertThat(masker.maskInput(RECORD_DEFINITION, input(), access).value()).isEqualTo(input());
        assertThat(masker.maskInput(null, input(), access).value()).isEqualTo(input());
        assertThat(masker.maskChanges(List.of(definitionChange), access).value())
                .containsExactly(definitionChange);
    }

    /** 보는 사람이 볼 수 없는 유형(모르는 유형 포함)의 속성 값은 민감 속성이 아니어도 가린다. */
    @Test
    void hidesPropertyOfATypeTheViewerCannotSee() {
        when(access.canView("WorkOrder")).thenReturn(false);

        Masked<List<AuditEntityChangeResponse>> masked =
                masker.maskChanges(List.of(itemChange()), access);

        assertThat(snapshotOf(masked.value().getFirst())).containsEntry("workOrderNo", null);
        assertThat(masked.restrictedPropertyCodes()).containsExactly("wo_no");
    }

    @Test
    void returnsValuesAsIsWhenNothingIsHidden() {
        AuditEntityChangeResponse change = itemChange();

        assertThat(masker.maskChanges(List.of(change), access))
                .isEqualTo(new Masked<>(List.of(change), List.of(), List.of()));
        assertThat(masker.maskInput(PLAN_DEFINITION, planInput(), access))
                .isEqualTo(new Masked<>(planInput(), List.of(), List.of()));
    }

    /**
     * 404 리뷰: Handler 결과도 허용 목록이다. 알리지 않은 Handler의 결과와 알리지 않은 자리는 가리고, 알린 속성 자리는 보는 사람의 범위로 가린다.
     * 실패의 결과는 실행기가 남긴 고정 문구라 그대로 둔다.
     */
    @Test
    void masksHandlerResultsLikeInputs() {
        when(access.hidesValue("Equipment", "manager")).thenReturn(true);
        Map<String, Object> result = Map.of("planId", 5, "assignee", "T-KIM", "note", "기록만 된 값");

        Masked<Map<String, Object>> plan =
                masker.maskResult(PLAN_DEFINITION, ExecutionStatus.SUCCEEDED, result, access);
        Masked<Map<String, Object>> newHandler =
                masker.maskResult(
                        NEW_HANDLER_DEFINITION, ExecutionStatus.SUCCEEDED, result, access);
        Map<String, Object> failure = Map.of("errorMessage", "Action을 실행하지 못했습니다.");

        assertThat(plan.value())
                .containsEntry("planId", 5)
                .containsEntry("assignee", null)
                .containsEntry("note", null);
        assertThat(plan.restrictedPropertyCodes()).containsExactly("manager");
        assertThat(newHandler.value())
                .containsEntry("planId", null)
                .containsEntry("assignee", null);
        assertThat(
                        masker.maskResult(
                                        NEW_HANDLER_DEFINITION,
                                        ExecutionStatus.FAILED,
                                        failure,
                                        access)
                                .value())
                .isEqualTo(failure);
        assertThat(
                        masker.maskResult(
                                        RECORD_DEFINITION,
                                        ExecutionStatus.SUCCEEDED,
                                        result,
                                        access)
                                .value())
                .isEqualTo(result);
    }

    /**
     * 404 리뷰: 차단 이유는 자리별로 가릴 수 없어 통째로 보이거나 가려진다. 실행기가 막은 문구는 늘 보이고, Handler가 막은 문구는 그 Handler가 읽는
     * 유형을 모두 볼 수 있어야 보이며, 차단 주체가 없는 404 이전 기록은 그 유형의 민감 값까지 볼 수 있어야 보인다.
     */
    @Test
    void showsBlockedReasonOnlyToViewersWhoSeeEveryTypeTheHandlerReads() {
        AuditValueMasker withTypes =
                new AuditValueMasker(
                        List.of(
                                new RecordedPropertyPaths() {
                                    @Override
                                    public List<Path> recordedPropertyPaths() {
                                        return PLAN_PATHS;
                                    }

                                    @Override
                                    public Map<String, java.util.Set<String>>
                                            blockedReasonObjectTypes() {
                                        return Map.of(
                                                "PLAN", java.util.Set.of("WorkOrder", "Routing"));
                                    }
                                }),
                        definitions);
        Map<String, Object> handler = Map.of(AuditValueMasker.BLOCKED_BY, "HANDLER");
        Map<String, Object> gate = Map.of(AuditValueMasker.BLOCKED_BY, "GATE");
        when(access.canViewRestricted("WorkOrder")).thenReturn(true);
        when(access.canViewRestricted("Routing")).thenReturn(true);

        assertThat(blocked(withTypes, PLAN_DEFINITION, handler)).isEqualTo("작업지시 WO-1");
        assertThat(blocked(withTypes, PLAN_DEFINITION, null)).isEqualTo("작업지시 WO-1");
        assertThat(blocked(withTypes, NEW_HANDLER_DEFINITION, handler))
                .isEqualTo(AuditValueMasker.HIDDEN_BLOCKED_REASON);
        assertThat(blocked(withTypes, NEW_HANDLER_DEFINITION, gate)).isEqualTo("작업지시 WO-1");
        assertThat(blocked(withTypes, RECORD_DEFINITION, null)).isEqualTo("작업지시 WO-1");

        when(access.canViewRestricted("Routing")).thenReturn(false);
        assertThat(blocked(withTypes, PLAN_DEFINITION, handler)).isEqualTo("작업지시 WO-1");
        assertThat(blocked(withTypes, PLAN_DEFINITION, null))
                .isEqualTo(AuditValueMasker.HIDDEN_BLOCKED_REASON);

        when(access.canView("Routing")).thenReturn(false);
        assertThat(blocked(withTypes, PLAN_DEFINITION, handler))
                .isEqualTo(AuditValueMasker.HIDDEN_BLOCKED_REASON);
        assertThat(blocked(withTypes, PLAN_DEFINITION, gate)).isEqualTo("작업지시 WO-1");

        assertThat(
                        masker.maskResult(PLAN_DEFINITION, ExecutionStatus.BLOCKED, handler, access)
                                .value())
                .isNull();
    }

    private String blocked(AuditValueMasker target, long definitionId, Map<String, Object> result) {
        return target.maskBlockedReason(
                definitionId, ExecutionStatus.BLOCKED, "작업지시 WO-1", result, access);
    }

    /** 속성 자리가 보이면 그 아래 값은 통째로 나가므로, 그 아래에 다른 자리를 알리면 시작하지 않는다. */
    @Test
    void rejectsSlotsDeclaredBelowAPropertySlot() {
        List<Path> nested =
                List.of(
                        Path.change("plan_item", List.of("snapshot"), "WorkOrder", "wo_no"),
                        Path.plainChange("plan_item", List.of("snapshot", "objectId")));

        assertThatThrownBy(() -> new AuditValueMasker(List.of(() -> nested), definitions))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsASlotDeclaredTwiceWithDifferentMeaning() {
        List<Path> twice =
                List.of(
                        Path.plainChange("plan_item", List.of("operation_code")),
                        Path.change("plan_item", List.of("operation_code"), "Operation", "op_seq"));

        assertThatThrownBy(() -> new AuditValueMasker(List.of(() -> twice), definitions))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Path.change("t", List.of(), "Type", "code"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> snapshotOf(AuditEntityChangeResponse change) {
        return asMap(change.afterData().get("source_snapshot"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** PLAN Handler가 알린 자리만 든 입력. */
    private static Map<String, Object> planInput() {
        return Map.of(
                "fromEquipmentId",
                301,
                "items",
                List.of(Map.of("workOrderId", 7, "operationCode", "20")));
    }

    /** PLAN Handler가 알리지 않은 secret이 든 입력. */
    private static Map<String, Object> input() {
        Map<String, Object> input = new HashMap<>(planInput());
        input.put("secret", "T-KIM");
        return input;
    }

    private static AuditEntityChangeResponse itemChange() {
        return new AuditEntityChangeResponse(
                "plan_item",
                Map.of("id", 1),
                ChangeKind.INSERT,
                null,
                Map.of(
                        "work_order_id",
                        7,
                        "source_snapshot",
                        Map.of(
                                "workOrderNo",
                                "WO-1",
                                "operations",
                                List.of(
                                        Map.of("objectId", 11, "sequence", "20", "status", "중단"),
                                        Map.of(
                                                "objectId",
                                                12,
                                                "sequence",
                                                "30",
                                                "status",
                                                "대기")))));
    }

    private void definition(long id, String handlerKey) {
        ActionDefinition definition = mock(ActionDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getHandlerKey()).thenReturn(handlerKey);
        when(definitions.findById(id)).thenReturn(Optional.of(definition));
    }
}

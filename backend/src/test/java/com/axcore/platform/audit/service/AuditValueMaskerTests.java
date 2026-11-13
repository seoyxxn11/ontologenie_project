package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.dto.AuditEntityChangeResponse;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.auth.service.DataAccess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditValueMaskerTests {

    private static final long PLAN_DEFINITION = 1L;
    private static final long OTHER_DEFINITION = 2L;

    private final DataAccess access = mock(DataAccess.class);
    private final ActionDefinitionRepository definitions = mock(ActionDefinitionRepository.class);
    private final AuditValueMasker masker =
            new AuditValueMasker(
                    List.of(
                            () ->
                                    List.of(
                                            Path.input(
                                                    "PLAN",
                                                    List.of("items", "*", "operationCode"),
                                                    "Operation",
                                                    "op_seq"),
                                            Path.change(
                                                    "plan_item",
                                                    List.of("source_snapshot", "workOrderNo"),
                                                    "WorkOrder",
                                                    "wo_no"),
                                            Path.change(
                                                    "plan_item",
                                                    List.of(
                                                            "source_snapshot",
                                                            "operations",
                                                            "*",
                                                            "status"),
                                                    "Operation",
                                                    "status"))),
                    definitions);

    @BeforeEach
    void definitions() {
        ActionDefinition plan = definition(PLAN_DEFINITION, "PLAN");
        ActionDefinition other = definition(OTHER_DEFINITION, "OTHER");
        when(definitions.findById(PLAN_DEFINITION)).thenReturn(Optional.of(plan));
        when(definitions.findById(OTHER_DEFINITION)).thenReturn(Optional.of(other));
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
                        operation -> assertThat(asMap(operation)).containsEntry("status", null));
        assertThat(masked.restrictedPropertyCodes()).containsExactly("status", "wo_no");
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
                .allSatisfy(item -> assertThat(asMap(item)).containsEntry("operationCode", null));
        assertThat(input.restrictedPropertyCodes()).containsExactly("op_seq");
        assertThat(asMap(changes.value().getFirst().afterData().get("source_snapshot")))
                .containsEntry("workOrderNo", null);
        assertThat(changes.restrictedPropertyCodes()).containsExactly("wo_no");
        assertThat(masker.maskInput(null, null, access))
                .isEqualTo(new Masked<Map<String, Object>>(null, List.of(), List.of()));
    }

    @Test
    void hidesInputOnlyForTheDeclaringHandler() {
        when(access.hidesValue("Operation", "op_seq")).thenReturn(true);

        Masked<Map<String, Object>> plan = masker.maskInput(PLAN_DEFINITION, input(), access);
        Masked<Map<String, Object>> other = masker.maskInput(OTHER_DEFINITION, input(), access);

        assertThat((List<?>) plan.value().get("items"))
                .allSatisfy(
                        item ->
                                assertThat(asMap(item))
                                        .containsEntry("operationCode", null)
                                        .containsEntry("workOrderId", 7));
        assertThat(plan.restrictedPropertyCodes()).containsExactly("op_seq");
        assertThat(other.value()).isEqualTo(input());
        assertThat(other.restrictedPropertyCodes()).isEmpty();
    }

    @Test
    void returnsValuesAsIsWhenNothingIsHidden() {
        AuditEntityChangeResponse change = itemChange();

        assertThat(masker.maskChanges(List.of(change), access))
                .isEqualTo(new Masked<>(List.of(change), List.of(), List.of()));
        assertThat(masker.maskInput(PLAN_DEFINITION, input(), access))
                .isEqualTo(new Masked<>(input(), List.of(), List.of()));
    }

    private static Map<String, Object> snapshotOf(AuditEntityChangeResponse change) {
        return asMap(change.afterData().get("source_snapshot"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> input() {
        return Map.of(
                "fromEquipmentId",
                301,
                "items",
                List.of(Map.of("workOrderId", 7, "operationCode", "20")));
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

    private static ActionDefinition definition(long id, String handlerKey) {
        ActionDefinition definition = mock(ActionDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getHandlerKey()).thenReturn(handlerKey);
        return definition;
    }
}

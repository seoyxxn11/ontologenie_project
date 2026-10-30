package com.axcore.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DB에 가기 전에 잘못 만든 기록을 막는다. */
class AuditEntryTests {

    @Test
    void fillsEmptyValues() {
        AuditEntry entry = base().build();

        assertThat(entry.input()).isEmpty();
        assertThat(entry.result()).isEmpty();
        assertThat(entry.changes()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredValues() {
        assertThatThrownBy(() -> base().status(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> base().actor(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsRunningStatus() {
        assertThatThrownBy(() -> base().status(ExecutionStatus.RUNNING).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresTargetTypeAndIdTogether() {
        assertThatThrownBy(() -> base().targetType("sync_run").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().targetId(1L).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresDefinitionForAction() {
        assertThatThrownBy(() -> base().eventKind(EventKind.ACTION).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsBlockedOnlyForActionWithReason() {
        assertThatThrownBy(
                        () -> base().status(ExecutionStatus.BLOCKED).blockedReason("권한 없음").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                base().eventKind(EventKind.ACTION)
                                        .actionDefinitionId(1L)
                                        .status(ExecutionStatus.BLOCKED)
                                        .blockedReason(" ")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

        AuditEntry blocked =
                base().eventKind(EventKind.ACTION)
                        .actionDefinitionId(1L)
                        .status(ExecutionStatus.BLOCKED)
                        .blockedReason("권한 없음")
                        .build();
        assertThat(blocked.status()).isEqualTo(ExecutionStatus.BLOCKED);
    }

    @Test
    void checksActorAccount() {
        assertThat(Actor.system().accountId()).isNull();
        assertThatThrownBy(() -> new Actor(ActorType.USER, null, "ADMIN"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Actor(ActorType.SYSTEM, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksChangeShape() {
        Map<String, Object> key = Map.of("id", 1);
        Map<String, Object> row = Map.of("name", "a");

        assertThat(ChangeEntry.delete("object_type", key, row).kind()).isEqualTo(ChangeKind.DELETE);
        assertThatThrownBy(() -> new ChangeEntry("object_type", key, ChangeKind.INSERT, row, row))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChangeEntry("object_type", key, ChangeKind.DELETE, row, row))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeEntry.update("object_type", Map.of(), row, row))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeEntry.update("object_type", key, null, row))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesChanges() {
        AuditEntry entry =
                base().changes(List.of(ChangeEntry.insert("sync_run", Map.of("id", 1), Map.of())))
                        .build();

        assertThatThrownBy(() -> entry.changes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AuditEntry.AuditEntryBuilder base() {
        return AuditEntry.builder()
                .eventKind(EventKind.SYNC_RUN)
                .actor(Actor.system())
                .entryPoint(EntryPoint.BATCH)
                .status(ExecutionStatus.SUCCEEDED);
    }
}

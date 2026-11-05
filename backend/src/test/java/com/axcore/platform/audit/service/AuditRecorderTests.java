package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 공통 감사 기록(212)을 실제 DB 제약과 트랜잭션으로 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AuditRecorderTests {

    @Autowired AuditRecorder auditRecorder;

    @Autowired JdbcClient jdbcClient;

    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void recordsUserActionWithChangesAndWithoutSecrets() {
        LoginAccount admin = createAccount("ADMIN");
        Long definitionId = createActionDefinition();

        Long id =
                auditRecorder.record(
                        AuditEntry.builder()
                                .eventKind(EventKind.ACTION)
                                .actor(Actor.user(admin))
                                .entryPoint(EntryPoint.WEB)
                                .status(ExecutionStatus.SUCCEEDED)
                                .actionDefinitionId(definitionId)
                                .checkedPermissionCode("ACCOUNT_MANAGE")
                                .targetType("account")
                                .targetId(admin.id())
                                .input(
                                        Map.of(
                                                "loginId", "user01",
                                                "password", "Init!Passw0rd",
                                                "options", Map.of("apiKey", "k-1", "note", "메모")))
                                .result(Map.of("accountId", 7))
                                .changes(
                                        List.of(
                                                ChangeEntry.insert(
                                                        "account",
                                                        Map.of("id", 7),
                                                        Map.of(
                                                                "login_id", "user01",
                                                                "password_hash", "$2a$10$x")),
                                                ChangeEntry.update(
                                                        "account",
                                                        Map.of("id", 8),
                                                        Map.of("status", "ACTIVE"),
                                                        Map.of("status", "LOCKED"))))
                                .build());

        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
                                select event_kind, actor_type, actor_id, actor_role_code, entry_point,
                                       checked_permission_code, target_type, target_id, status,
                                       input::text as input, result::text as result,
                                       request_id is not null as has_request_id,
                                       requested_at = finished_at as same_time
                                from action_execution where id = :id
                                """)
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("event_kind", "ACTION")
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_id", admin.id())
                .containsEntry("actor_role_code", "ADMIN")
                .containsEntry("entry_point", "WEB")
                .containsEntry("checked_permission_code", "ACCOUNT_MANAGE")
                .containsEntry("target_type", "account")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("has_request_id", true)
                .containsEntry("same_time", true);
        assertThat((String) row.get("input"))
                .contains("user01", "메모")
                .doesNotContain("password", "Init!Passw0rd", "apiKey", "k-1");
        assertThat((String) row.get("result")).contains("accountId");

        List<Map<String, Object>> changes =
                jdbcClient
                        .sql(
                                """
                                select table_name, row_key::text as row_key, change_kind,
                                       before_data::text as before_data, after_data::text as after_data
                                from entity_change where action_execution_id = :id order by id
                                """)
                        .param("id", id)
                        .query()
                        .listOfRows();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0))
                .containsEntry("table_name", "account")
                .containsEntry("change_kind", "INSERT")
                .containsEntry("before_data", null);
        assertThat((String) changes.get(0).get("after_data"))
                .contains("user01")
                .doesNotContain("password_hash");
        assertThat(changes.get(1)).containsEntry("change_kind", "UPDATE");
        assertThat((String) changes.get(1).get("row_key")).contains("8");
        assertThat((String) changes.get(1).get("before_data")).contains("ACTIVE");
        assertThat((String) changes.get(1).get("after_data")).contains("LOCKED");
    }

    @Test
    void recordsSystemRowFromBatchWithoutAccount() {
        Instant requestedAt = Instant.now().minusSeconds(2);

        Long id =
                auditRecorder.record(
                        AuditEntry.builder()
                                .eventKind(EventKind.SYNC_RUN)
                                .actor(Actor.system())
                                .entryPoint(EntryPoint.BATCH)
                                .status(ExecutionStatus.FAILED)
                                .targetType("sync_run")
                                .targetId(10L)
                                .result(Map.of("errorMessage", "원천 접속 실패"))
                                .requestedAt(requestedAt)
                                .build());

        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
                                select actor_type, actor_id, actor_role_code, entry_point, status,
                                       duration_ms
                                from action_execution where id = :id
                                """)
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("actor_id", null)
                .containsEntry("actor_role_code", null)
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "FAILED");
        assertThat((Integer) row.get("duration_ms")).isGreaterThanOrEqualTo(2000);
    }

    @Test
    void recordIsRolledBackWithBusinessTransaction() {
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                transaction()
                                        .executeWithoutResult(
                                                status -> {
                                                    auditRecorder.record(jobEntry(requestId));
                                                    throw new IllegalStateException("업무 실패");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countByRequestId(requestId)).isZero();
    }

    @Test
    void separateRecordSurvivesBusinessRollback() {
        LoginAccount planner = createAccount("PLANNER");
        Long definitionId = createActionDefinition();
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                transaction()
                                        .executeWithoutResult(
                                                status -> {
                                                    auditRecorder.recordSeparately(
                                                            AuditEntry.builder()
                                                                    .eventKind(EventKind.ACTION)
                                                                    .actor(Actor.user(planner))
                                                                    .entryPoint(EntryPoint.WEB)
                                                                    .status(ExecutionStatus.BLOCKED)
                                                                    .actionDefinitionId(
                                                                            definitionId)
                                                                    .blockedReason("권한 없음")
                                                                    .requestId(requestId)
                                                                    .build());
                                                    throw new IllegalStateException("업무 실패");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> row =
                jdbcClient
                        .sql(
                                "select status, blocked_reason from action_execution where"
                                        + " request_id = :requestId")
                        .param("requestId", requestId)
                        .query()
                        .singleRow();
        assertThat(row).containsEntry("status", "BLOCKED").containsEntry("blocked_reason", "권한 없음");
    }

    @Test
    void sameRequestIdIsRejectedByDatabase() {
        UUID requestId = UUID.randomUUID();
        auditRecorder.record(jobEntry(requestId));

        assertThatThrownBy(() -> auditRecorder.record(jobEntry(requestId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countByRequestId(requestId)).isEqualTo(1);
    }

    private static AuditEntry jobEntry(UUID requestId) {
        return AuditEntry.builder()
                .eventKind(EventKind.CHECK_RUN)
                .actor(Actor.system())
                .entryPoint(EntryPoint.BATCH)
                .status(ExecutionStatus.SUCCEEDED)
                .targetType("check_run")
                .targetId(1L)
                .requestId(requestId)
                .build();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long countByRequestId(UUID requestId) {
        return jdbcClient
                .sql("select count(*) from action_execution where request_id = :requestId")
                .param("requestId", requestId)
                .query(Long.class)
                .single();
    }

    private LoginAccount createAccount(String roleCode) {
        String loginId = "audit-" + UUID.randomUUID();
        Long id =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id)
                                values (:loginId, '감사 테스트', 'x',
                                        (select id from role where code = :roleCode))
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("roleCode", roleCode)
                        .query(Long.class)
                        .single();
        return new LoginAccount(id, loginId, roleCode, false);
    }

    private Long createActionDefinition() {
        String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcClient
                .sql("insert into permission (code, name) values (:code, '테스트 권한')")
                .param("code", code)
                .update();
        return jdbcClient
                .sql(
                        """
                        insert into action_definition (code, name, description, target_kind, action_mode,
                                                       handler_key, action_kind, required_permission)
                        values (:code, '테스트 Action', '감사 테스트', 'SYSTEM', 'EXECUTE', 'test', 'WRITE', :code)
                        returning id
                        """)
                .param("code", code)
                .query(Long.class)
                .single();
    }
}

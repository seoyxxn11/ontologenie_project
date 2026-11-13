package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * ERD의 온톨로지(V1)·운영(V2)·교차 FK(V3)·세션(V4)·CDC 수집(V5)·Spring Batch(V6)·전체 읽기 경계(V11)·CDC 소비 상태와 확인
 * 대기(V12)·역할별 조회 범위 초기값(V13) migration이 PostgreSQL 18에 그대로 적용되는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationTests {

    // V1 18개 + V5 cdc_offset + V11 source_delete_mark + V12 source_load_failure
    private static final int ONTOLOGY_TABLES = 21;
    // V2 23개 + V12 link_check_pending
    private static final int OPERATION_TABLES = 24;
    private static final int SESSION_TABLES = 2;
    private static final int SPRING_BATCH_TABLES = 6;

    @Autowired Flyway flyway;

    @Autowired JdbcClient jdbcClient;

    @Test
    void appliesAllMigrations() {
        // 스키마를 만든 기록처럼 Flyway가 스스로 남기는 줄은 버전이 없어 뺀다.
        MigrationInfo[] applied =
                Arrays.stream(flyway.info().applied())
                        .filter(info -> !info.getType().isSynthetic())
                        .toArray(MigrationInfo[]::new);

        assertThat(Arrays.stream(applied).map(info -> info.getVersion().getVersion()))
                .containsExactly(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                        "15", "16", "17", "18", "19", "20");
        assertThat(applied).allMatch(info -> info.getState() == MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @Transactional
    void agentConversationKeepsOnlyOneRunningAnswer() {
        long accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account
                                    (login_id, display_name, password_hash, role_id, must_change_password)
                                values ('agent-' || gen_random_uuid(), '대화 사용자', 'test-hash',
                                        (select id from role where code = 'VIEWER'), false)
                                returning id
                                """)
                        .query(Long.class)
                        .single();
        long conversationId =
                jdbcClient
                        .sql(
                                "insert into agent_conversation (account_id) values (:account) returning id")
                        .param("account", accountId)
                        .query(Long.class)
                        .single();
        String runningAnswer =
                """
                insert into agent_message (conversation_id, sender, content, status)
                values (:conversation, 'AGENT', '', 'RUNNING')
                """;
        jdbcClient.sql(runningAnswer).param("conversation", conversationId).update();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(runningAnswer)
                                        .param("conversation", conversationId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_agent_message_running");
    }

    @Test
    void createsAllTablesInPlatformSchema() {
        Integer tables =
                jdbcClient
                        .sql(
                                """
                                select count(*) from information_schema.tables
                                where table_schema = 'platform' and table_type = 'BASE TABLE'
                                  and table_name <> 'flyway_schema_history'
                                """)
                        .query(Integer.class)
                        .single();

        assertThat(tables)
                .isEqualTo(
                        ONTOLOGY_TABLES + OPERATION_TABLES + SESSION_TABLES + SPRING_BATCH_TABLES);
    }

    @Test
    void connectionUsesPlatformSchema() {
        String schema = jdbcClient.sql("select current_schema()").query(String.class).single();

        assertThat(schema).isEqualTo("platform");
    }

    @Test
    void seedsFixedRoles() {
        assertThat(jdbcClient.sql("select code from role order by id").query(String.class).list())
                .containsExactly("ADMIN", "PLANNER", "MAINTENANCE", "VIEWER");
    }

    @Test
    void seedsRoleDataScopeForDecidedGroups() {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select r.code || ':' || s.access_group || ':' || s.can_view_restricted
                                        from role_data_scope s join role r on r.id = s.role_id
                                        where s.access_group in ('PRODUCTION', 'SALES_SUPPLY', 'FINANCE', 'HR')
                                        order by r.code, s.access_group
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "MAINTENANCE:PRODUCTION:false",
                        "MAINTENANCE:SALES_SUPPLY:false",
                        "PLANNER:PRODUCTION:true",
                        "PLANNER:SALES_SUPPLY:false",
                        "VIEWER:PRODUCTION:false",
                        "VIEWER:SALES_SUPPLY:true");
    }

    @Test
    @Transactional
    void crossDomainForeignKeyRejectsMissingAccount() {
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into object_type (code, name, created_by)
                                                values ('EQUIPMENT', '설비', -1)
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_object_type_created_by");
    }

    @Test
    void dropsChangeSequenceColumns() {
        Integer remaining =
                jdbcClient
                        .sql(
                                """
                                select count(*) from information_schema.columns
                                where table_schema = 'platform'
                                  and column_name in ('last_applied_change_seq', 'initial_loaded_at',
                                                      'from_change_seq', 'to_change_seq',
                                                      'change_seq_from', 'change_seq_to')
                                """)
                        .query(Integer.class)
                        .single();

        assertThat(remaining).isZero();
    }

    @Test
    void commentsFollowDecidedCheckRules() {
        String checkResult =
                jdbcClient
                        .sql("select obj_description('check_result'::regclass, 'pg_class')")
                        .query(String.class)
                        .single();
        String cardinality =
                jdbcClient
                        .sql(
                                """
                                select col_description('link_type'::regclass, attnum)
                                from pg_attribute
                                where attrelid = 'link_type'::regclass and attname = 'cardinality'
                                """)
                        .query(String.class)
                        .single();

        assertThat(checkResult).contains("도착 쪽");
        assertThat(cardinality).contains("출발 기준");
    }

    @Test
    @Transactional
    void sourceResourceDefaultsToFullCompare() {
        long resourceId = insertSourceResource("TABLE");

        assertThat(
                        jdbcClient
                                .sql("select sync_strategy from source_resource where id = :id")
                                .param("id", resourceId)
                                .query(String.class)
                                .single())
                .isEqualTo("FULL_COMPARE");
    }

    @Test
    @Transactional
    void cdcTableKeepsLoadedLsn() {
        long resourceId = insertSourceResource("TABLE");

        // 배치가 전체 읽기 직전에 남기는 값과 같은 식으로 LSN을 숫자로 바꿔 넣는다.
        jdbcClient
                .sql(
                        """
                        update source_resource
                        set sync_strategy = 'CDC',
                            loaded_lsn = (pg_current_wal_lsn() - '0/0'::pg_lsn)::bigint
                        where id = :id
                        """)
                .param("id", resourceId)
                .update();

        assertThat(
                        jdbcClient
                                .sql("select loaded_lsn from source_resource where id = :id")
                                .param("id", resourceId)
                                .query(Long.class)
                                .single())
                .isPositive();
    }

    @Test
    @Transactional
    void cdcRejectsView() {
        long resourceId = insertSourceResource("VIEW");

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                "update source_resource set sync_strategy = 'CDC' where id = :id")
                                        .param("id", resourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_source_resource_cdc_kind");
    }

    @Test
    @Transactional
    void loadedLsnRequiresCdc() {
        long resourceId = insertSourceResource("TABLE");

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                "update source_resource set loaded_lsn = 100 where id = :id")
                                        .param("id", resourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_source_resource_lsn");
    }

    @Test
    @Transactional
    void cdcRunNeedsNoRequesterAndRecordsLsnRange() {
        long resourceId = insertSourceResource("TABLE");
        long runId = insertCdcRun(resourceId);

        jdbcClient
                .sql(
                        """
                        insert into sync_run_resource
                            (sync_run_id, data_source_id, source_resource_id, mode, status,
                             from_lsn, to_lsn, rows_read, rows_upserted, started_at, finished_at)
                        select :run, data_source_id, id, 'INCREMENTAL', 'SUCCEEDED',
                               100, 200, 3, 3, now(), now()
                        from source_resource where id = :resource
                        """)
                .param("run", runId)
                .param("resource", resourceId)
                .update();

        assertThat(
                        jdbcClient
                                .sql(
                                        "select to_lsn from sync_run_resource where sync_run_id = :run")
                                .param("run", runId)
                                .query(Long.class)
                                .single())
                .isEqualTo(200L);
    }

    @Test
    @Transactional
    void lsnRangeMustBeOrdered() {
        long resourceId = insertSourceResource("TABLE");
        long runId = insertCdcRun(resourceId);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into sync_run_resource
                                                    (sync_run_id, data_source_id, source_resource_id,
                                                     mode, status, from_lsn, to_lsn, started_at,
                                                     finished_at)
                                                select :run, data_source_id, id, 'INCREMENTAL',
                                                       'SUCCEEDED', 200, 100, now(), now()
                                                from source_resource where id = :resource
                                                """)
                                        .param("run", runId)
                                        .param("resource", resourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_sync_run_resource_range");
    }

    @Test
    @Transactional
    void manualRunStillNeedsRequester() {
        long sourceId = insertDataSource();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into sync_run (data_source_id, trigger_kind)
                                                values (:source, 'MANUAL')
                                                """)
                                        .param("source", sourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_sync_run_requester");
    }

    @Test
    @Transactional
    void platformObjectHasNoSourceLsn() {
        long typeId =
                jdbcClient
                        .sql(
                                """
                                insert into object_type (code, name)
                                values ('TYPE-' || gen_random_uuid(), '설비')
                                returning id
                                """)
                        .query(Long.class)
                        .single();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into object_instance
                                                    (object_type_id, display_name, source_lsn)
                                                values (:type, '플랫폼 객체', 100)
                                                """)
                                        .param("type", typeId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_object_instance_lsn");
    }

    @Test
    @Transactional
    void cdcOffsetKeepsPositionPerPartition() {
        long sourceId = insertDataSource();

        jdbcClient
                .sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset)
                        values ('ERP.public.work_order', 0, :source, 42)
                        """)
                .param("source", sourceId)
                .update();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into cdc_offset
                                                    (topic, partition_no, data_source_id, next_offset)
                                                values ('ERP.public.work_order', 0, :source, 43)
                                                """)
                                        .param("source", sourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cdc_offset_pkey");
    }

    @Test
    @Transactional
    void recoveryRunNeedsNoRequester() {
        long sourceId = insertDataSource();

        jdbcClient
                .sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind)
                        values (:source, 'RECOVERY')
                        """)
                .param("source", sourceId)
                .update();

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select count(*) from sync_run
                                        where data_source_id = :source and trigger_kind = 'RECOVERY'
                                          and requested_by is null
                                        """)
                                .param("source", sourceId)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    @Transactional
    void fullLoadGenerationRequiresCdc() {
        long resourceId = insertSourceResource("TABLE");

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                update source_resource set full_load_done_gen = 5
                                                where id = :id
                                                """)
                                        .param("id", resourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_source_resource_load_gen");
    }

    @Test
    @Transactional
    void platformObjectHasNoLoadSnapshot() {
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into object_instance
                                                    (object_type_id, display_name, source_txid)
                                                values (:type, '플랫폼 객체', pg_current_xact_id())
                                                """)
                                        .param("type", insertObjectType())
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_object_instance_load");
    }

    @Test
    @Transactional
    void loadedSnapshotNeedsGeneration() {
        long mappingId = insertObjectMapping();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into object_instance
                                                    (object_type_id, source_mapping_id, source_key, display_name,
                                                     loaded_snapshot)
                                                select object_type_id, id, '{"id": 1}', '작업지시 1',
                                                       pg_current_snapshot()
                                                from object_source_mapping where id = :mapping
                                                """)
                                        .param("mapping", mappingId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_object_instance_load_pair");
    }

    @Test
    @Transactional
    void storedLoadSnapshotJudgesTransactionVisibility() {
        long mappingId = insertObjectMapping();

        // 전체 읽기가 남기는 값처럼 스냅샷을 저장하고, 저장한 스냅샷으로 트랜잭션 번호의 가시성을 판단한다.
        // 이 테스트 트랜잭션은 스냅샷을 찍을 때 아직 끝나지 않았으므로 안 보이고, 그보다 앞서 끝난 번호는 보인다.
        jdbcClient
                .sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name,
                             source_txid, loaded_snapshot, loaded_gen)
                        select object_type_id, id, '{"id": 1}', '작업지시 1',
                               pg_current_xact_id(), pg_current_snapshot(), 1
                        from object_source_mapping where id = :mapping
                        """)
                .param("mapping", mappingId)
                .update();

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select pg_visible_in_snapshot(source_txid, loaded_snapshot)
                                        from object_instance where source_mapping_id = :mapping
                                        """)
                                .param("mapping", mappingId)
                                .query(Boolean.class)
                                .single())
                .isFalse();
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select pg_visible_in_snapshot(
                                                   (pg_snapshot_xmin(loaded_snapshot)::text::bigint - 1)::text::xid8,
                                                   loaded_snapshot)
                                        from object_instance where source_mapping_id = :mapping
                                        """)
                                .param("mapping", mappingId)
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    @Transactional
    void deleteMarkKeepsOnePerSourceKey() {
        long resourceId = insertSourceResource("TABLE");
        String mark =
                """
                insert into source_delete_mark (source_resource_id, source_key, source_txid, source_lsn)
                values (:resource, '{"id": 10}', pg_current_xact_id(), 100)
                """;
        jdbcClient.sql(mark).param("resource", resourceId).update();

        assertThatThrownBy(() -> jdbcClient.sql(mark).param("resource", resourceId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("source_delete_mark_pkey");
    }

    @Test
    @Transactional
    void deleteMarkKeyMustBeObject() {
        long resourceId = insertSourceResource("TABLE");

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into source_delete_mark
                                                    (source_resource_id, source_key, source_txid, source_lsn)
                                                values (:resource, '"WO-10"', pg_current_xact_id(), 100)
                                                """)
                                        .param("resource", resourceId)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_source_delete_mark_key");
    }

    @Test
    @Transactional
    void cdcOffsetKeepsConsumerState() {
        long sourceId = insertDataSource();

        jdbcClient
                .sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset, last_txid,
                                                last_commit_at, last_message_at, processing_lag_ms, healthy_since)
                        values ('ERP.all', 0, :source, 42, pg_current_xact_id(), now(), now(), 15, now())
                        """)
                .param("source", sourceId)
                .update();

        assertThat(
                        jdbcClient
                                .sql(
                                        "select last_txid = pg_current_xact_id() from cdc_offset where topic = 'ERP.all'")
                                .query(Boolean.class)
                                .single())
                .isTrue();
        violates(
                "ck_cdc_offset_recovery\"",
                () -> updateCdcOffset("recovery_reason = 'SLOT_LOST', healthy_since = null"));
        violates(
                "ck_cdc_offset_recovery_unhealthy",
                () -> updateCdcOffset("recovery_reason = 'SLOT_LOST', recovery_at = now()"));
        violates(
                "ck_cdc_offset_recovery_reason",
                () ->
                        updateCdcOffset(
                                "recovery_reason = 'BROKEN', recovery_at = now(), healthy_since = null"));
        updateCdcOffset(
                "recovery_reason = 'EPOCH_UNCERTAIN', recovery_at = now(), healthy_since = null");
        updateCdcOffset(
                "recovery_reason = 'SOURCE_CHANGED', recovery_at = now(), healthy_since = null");
        // V14: 이어 받을 기준 시각은 복구가 풀린 뒤에만 있다
        violates("ck_cdc_offset_resume", () -> updateCdcOffset("resume_after = now()"));
        updateCdcOffset("recovery_reason = null, recovery_at = null, resume_after = now()");
    }

    @Test
    @Transactional
    void loadFailureKeepsOneOpenRecordEvenWithoutKey() {
        long mappingId = insertObjectMapping();
        long resourceId = resourceOf(mappingId);
        long runId = insertCdcRun(resourceId);

        long first = insertLoadFailure(mappingId, resourceId, runId, null);
        violates(
                "uq_source_load_failure_open",
                () -> insertLoadFailure(mappingId, resourceId, runId, null));
        insertLoadFailure(mappingId, resourceId, runId, "{\"id\": \"7\"}");

        jdbcClient
                .sql(
                        "update source_load_failure set status = 'CLOSED', closed_at = now() where id = :id")
                .param("id", first)
                .update();
        insertLoadFailure(mappingId, resourceId, runId, null);
        violates(
                "ck_source_load_failure_closed",
                () ->
                        jdbcClient
                                .sql(
                                        "update source_load_failure set closed_at = null where id = :id")
                                .param("id", first)
                                .update());
    }

    @Test
    @Transactional
    void pendingCheckWaitsOnceAndPromotesOnlyWithIssue() {
        long mappingId = insertObjectMapping();
        long objectId = insertObject(mappingId);
        long ruleId = insertLinkRule();
        long checkId = insertSyncCheck();

        long pending = insertPending(ruleId, objectId, checkId);
        violates("uq_link_check_pending_waiting", () -> insertPending(ruleId, objectId, checkId));
        violates(
                "ck_link_check_pending_issue",
                () ->
                        jdbcClient
                                .sql(
                                        """
                                                update link_check_pending
                                                set status = 'PROMOTED', closed_at = now(), close_reason = 'PROMOTED'
                                                where id = :id
                                                """)
                                .param("id", pending)
                                .update());

        jdbcClient
                .sql(
                        """
                        update link_check_pending
                        set status = 'TRANSIENT', closed_at = now(), close_reason = 'LINKED'
                        where id = :id
                        """)
                .param("id", pending)
                .update();
        long next = insertPending(ruleId, objectId, checkId);
        long issueId = insertIssue(ruleId, checkId);
        jdbcClient
                .sql(
                        """
                        update link_check_pending
                        set status = 'PROMOTED', closed_at = now(), close_reason = 'PROMOTED', issue_id = :issue
                        where id = :id
                        """)
                .param("issue", issueId)
                .param("id", next)
                .update();
    }

    @Test
    @Transactional
    void syncCheckMayOmitSyncRunButOthersMayNotHaveOne() {
        insertSyncCheck();
        long runId = insertCdcRun(insertSourceResource("TABLE"));

        violates(
                "ck_check_run_sync",
                () ->
                        jdbcClient
                                .sql(
                                        """
                                                insert into check_run (trigger_kind, work_key, sync_run_id)
                                                values ('CANDIDATE', 'CANDIDATE:' || gen_random_uuid(), :run)
                                                """)
                                .param("run", runId)
                                .update());
    }

    // 같은 트랜잭션 안에서 위반을 여러 번 확인하려고 savepoint로 되돌린다(위반 뒤에는 트랜잭션이 중단된다).
    private void violates(String constraint, Runnable statement) {
        jdbcClient.sql("savepoint before_violation").update();
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
        jdbcClient.sql("rollback to savepoint before_violation").update();
    }

    private void updateCdcOffset(String set) {
        jdbcClient.sql("update cdc_offset set " + set + " where topic = 'ERP.all'").update();
    }

    private long resourceOf(long mappingId) {
        return jdbcClient
                .sql("select source_resource_id from object_source_mapping where id = :id")
                .param("id", mappingId)
                .query(Long.class)
                .single();
    }

    private long insertLoadFailure(long mappingId, long resourceId, long runId, String sourceKey) {
        return jdbcClient
                .sql(
                        """
                        insert into source_load_failure (source_resource_id, object_source_mapping_id, source_key,
                                                         column_name, reason, last_sync_run_id)
                        values (:resource, :mapping, cast(:key as jsonb), 'id', 'KEY_MISSING', :run)
                        returning id
                        """)
                .param("resource", resourceId)
                .param("mapping", mappingId)
                .param("key", sourceKey)
                .param("run", runId)
                .query(Long.class)
                .single();
    }

    private long insertObject(long mappingId) {
        return jdbcClient
                .sql(
                        """
                        insert into object_instance (object_type_id, source_mapping_id, source_key, display_name)
                        select object_type_id, id, '{"id": "7"}', 'WO-7' from object_source_mapping where id = :id
                        returning id
                        """)
                .param("id", mappingId)
                .query(Long.class)
                .single();
    }

    private long insertLinkRule() {
        long linkTypeId =
                jdbcClient
                        .sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality,
                                                       description)
                                values ('LT-' || gen_random_uuid(), '작업 설비', :source, :target, 'MANY_TO_ONE',
                                        '시험')
                                returning id
                                """)
                        .param("source", insertObjectType())
                        .param("target", insertObjectType())
                        .query(Long.class)
                        .single();
        return jdbcClient
                .sql(
                        """
                        insert into link_mapping (link_type_id, code, revision)
                        values (:type, 'RULE-' || gen_random_uuid(), 1)
                        returning id
                        """)
                .param("type", linkTypeId)
                .query(Long.class)
                .single();
    }

    private long insertSyncCheck() {
        return jdbcClient
                .sql(
                        """
                        insert into check_run (trigger_kind, work_key)
                        values ('SYNC', 'SYNC:' || gen_random_uuid())
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private long insertPending(long ruleId, long objectId, long checkId) {
        return jdbcClient
                .sql(
                        """
                        insert into link_check_pending (link_mapping_id, source_object_id, last_check_run_id)
                        values (:rule, :object, :check)
                        returning id
                        """)
                .param("rule", ruleId)
                .param("object", objectId)
                .param("check", checkId)
                .query(Long.class)
                .single();
    }

    private long insertIssue(long ruleId, long checkId) {
        return jdbcClient
                .sql(
                        """
                        insert into issue (issue_kind, dedupe_key, link_mapping_id, failure_reason, evidence,
                                           detected_check_run_id)
                        values ('UNMATCHED', 'UNMATCHED:' || gen_random_uuid(), :rule,
                                '수집 정상 상태에서 10분 넘게 연결 대상 없음', '{}', :check)
                        returning id
                        """)
                .param("rule", ruleId)
                .param("check", checkId)
                .query(Long.class)
                .single();
    }

    private long insertObjectType() {
        return jdbcClient
                .sql(
                        """
                        insert into object_type (code, name)
                        values ('TYPE-' || gen_random_uuid(), '작업지시')
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private long insertObjectMapping() {
        return jdbcClient
                .sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, '["id"]')
                        returning id
                        """)
                .param("resource", insertSourceResource("TABLE"))
                .param("type", insertObjectType())
                .query(Long.class)
                .single();
    }

    // 원천과 테이블은 테스트마다 새 코드로 만든다(트랜잭션이 끝나면 되돌린다).
    private long insertDataSource() {
        return jdbcClient
                .sql(
                        """
                        insert into data_source (code, name, source_kind)
                        values ('ERP-' || gen_random_uuid(), 'ERP', 'POSTGRES')
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private long insertSourceResource(String kind) {
        return jdbcClient
                .sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind)
                        values (:source, 'public.work_order', :kind)
                        returning id
                        """)
                .param("source", insertDataSource())
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    private long insertCdcRun(long resourceId) {
        return jdbcClient
                .sql(
                        """
                        insert into sync_run
                            (data_source_id, trigger_kind, status, started_at, finished_at)
                        select data_source_id, 'CDC', 'SUCCEEDED', now(), now()
                        from source_resource where id = :resource
                        returning id
                        """)
                .param("resource", resourceId)
                .query(Long.class)
                .single();
    }
}

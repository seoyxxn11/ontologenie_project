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
 * ERD의 온톨로지(V1)·운영(V2)·교차 FK(V3)·세션(V4)·CDC 수집(V5)과 Spring Batch(V6) migration이 PostgreSQL 18에 그대로
 * 적용되는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationTests {

    // V1 18개 + V5 cdc_offset
    private static final int ONTOLOGY_TABLES = 19;
    private static final int OPERATION_TABLES = 23;
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
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(applied).allMatch(info -> info.getState() == MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
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

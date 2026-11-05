package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.dto.ConnectionCheckResultResponse;
import com.axcore.platform.source.dto.ConnectionCheckResultResponse.AccountResult;
import com.axcore.platform.source.repository.SourceQueryRepository;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 연결 검사 작업을 실제 PostgreSQL로 확인한다. 같은 컨테이너 안의 mes_src 스키마를 원천으로 쓰고, 원천 권한은 조회 전용 역할의 표·컬럼 GRANT로 만든다.
 * work_order.phone은 금지 컬럼이라 두 계정 모두 권한이 없다. CDC publication에는 실제 원천의 change_log처럼 빠진 표가 있도록
 * work_order만 넣는다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "axcore.source-check.cdc-publication=" + SourceConnectionCheckJobTests.PUBLICATION,
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class SourceConnectionCheckJobTests {

    static final String PUBLICATION = "axc_src_cdc";
    private static final String SYNC_USER = "axc_src_sync";
    private static final String AI_USER = "axc_src_ai";
    private static final String SYNC_PASSWORD = "sync-Pa55!";
    private static final String AI_PASSWORD = "ai-Pa55!";

    // 주기 실행이 테스트가 넣은 작업을 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;

    @Autowired QueuedJobRunner queuedJobRunner;

    @Autowired SourceConnectionCheckJob job;

    @Autowired SourceCredentialService credentialService;

    @Autowired SourceQueryRepository queryRepository;

    @Autowired JdbcClient jdbcClient;

    @Autowired PostgreSQLContainer postgres;

    private long accountId;

    @BeforeEach
    void prepareSource() {
        jdbcClient.sql("drop publication if exists " + PUBLICATION).update();
        jdbcClient.sql("drop schema if exists mes_src cascade").update();
        jdbcClient.sql("drop role if exists " + SYNC_USER).update();
        jdbcClient.sql("drop role if exists " + AI_USER).update();
        jdbcClient
                .sql(
                        """
                        create schema mes_src;
                        create table mes_src.work_order (id bigint primary key, code varchar(20) not null,
                                                         phone varchar(20));
                        comment on column mes_src.work_order.code is '작업지시 번호';
                        create table mes_src.equipment (id bigint primary key, name text);
                        create table mes_src.payroll (id bigint primary key);
                        create view mes_src.open_work_order as select id, code from mes_src.work_order;
                        create role %1$s login password '%3$s';
                        create role %2$s login password '%4$s';
                        grant usage on schema mes_src to %1$s, %2$s;
                        grant select (id, code) on mes_src.work_order to %1$s, %2$s;
                        grant select on mes_src.equipment, mes_src.open_work_order to %1$s;
                        create publication %5$s for table mes_src.work_order;
                        """
                                .formatted(
                                        SYNC_USER,
                                        AI_USER,
                                        SYNC_PASSWORD,
                                        AI_PASSWORD,
                                        PUBLICATION))
                .update();
        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '관리자', 'not-used',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("loginId", "admin-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void connectsWithBothAccountsAndStoresSyncCatalog() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        long checkId = queueCheck(sourceId);

        assertThat(queuedJobRunner.runNext(job)).isTrue();

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.finishedAt()).isNotNull();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.result().accounts())
                .extracting(
                        AccountResult::purpose,
                        AccountResult::status,
                        AccountResult::readableTableCount,
                        AccountResult::readableColumnCount)
                .containsExactly(
                        tuple("SYNC", "SUCCEEDED", 3L, 6L), tuple("AI", "SUCCEEDED", 1L, 2L));

        assertThat(source(sourceId)).containsEntry("connection_status", "CONNECTED");
        assertThat(source(sourceId).get("last_checked_at")).isNotNull();
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select resource_key, resource_kind, sync_strategy, is_readable
                                        from source_resource where data_source_id = :id order by resource_key
                                        """)
                                .param("id", sourceId)
                                .query(
                                        (rs, row) ->
                                                tuple(
                                                        rs.getString(1),
                                                        rs.getString(2),
                                                        rs.getString(3),
                                                        rs.getBoolean(4)))
                                .list())
                .containsExactly(
                        tuple("mes_src.equipment", "TABLE", "FULL_COMPARE", true),
                        tuple("mes_src.open_work_order", "VIEW", "FULL_COMPARE", true),
                        tuple("mes_src.work_order", "TABLE", "CDC", true));
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select c.column_name, c.source_data_type, c.is_nullable, c.is_key_part,
                                               c.ordinal_position, c.source_comment
                                        from source_column c join source_resource r on r.id = c.source_resource_id
                                        where r.data_source_id = :id and r.resource_key = 'mes_src.work_order'
                                        order by c.ordinal_position
                                        """)
                                .param("id", sourceId)
                                .query(
                                        (rs, row) ->
                                                tuple(
                                                        rs.getString(1),
                                                        rs.getString(2),
                                                        rs.getBoolean(3),
                                                        rs.getBoolean(4),
                                                        rs.getInt(5),
                                                        rs.getString(6)))
                                .list())
                .containsExactly(
                        tuple("id", "bigint", false, true, 1, null),
                        tuple("code", "character varying(20)", false, false, 2, "작업지시 번호"));

        Map<String, Object> audit = resultLine(checkId);
        assertThat(audit)
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "SUCCEEDED");
        assertThat(audit.get("result").toString()).contains("CONNECTED");
        assertNoSecret(checkId);
    }

    @Test
    void recheckKeepsIdsAndMarksOnlyRemovedColumns() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        queueCheck(sourceId);
        queuedJobRunner.runNext(job);
        long workOrderId = resourceId(sourceId, "mes_src.work_order");
        long equipmentId = resourceId(sourceId, "mes_src.equipment");

        jdbcClient
                .sql(
                        """
                        revoke select on mes_src.equipment from %1$s;
                        revoke select (code) on mes_src.work_order from %1$s;
                        alter table mes_src.equipment drop column name;
                        alter table mes_src.work_order add column qty integer;
                        grant select (qty) on mes_src.work_order to %1$s;
                        """
                                .formatted(SYNC_USER))
                .update();
        queueCheck(sourceId);
        queuedJobRunner.runNext(job);

        assertThat(resourceId(sourceId, "mes_src.work_order")).isEqualTo(workOrderId);
        assertThat(resourceId(sourceId, "mes_src.equipment")).isEqualTo(equipmentId);
        assertThat(readable(equipmentId)).isFalse();
        assertThat(readable(workOrderId)).isTrue();
        // 권한만 빠진 code는 그대로 두고, 새로 생긴 qty만 더한다.
        assertThat(presentColumns(workOrderId)).containsExactly("id", "code", "qty");
        // 읽을 수 없게 된 표는 이번 목록에 없으므로 컬럼도 건드리지 않는다.
        assertThat(presentColumns(equipmentId)).containsExactly("id", "name");
    }

    @Test
    void syncStrategyFollowsCdcPublication() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        queueCheck(sourceId);
        queuedJobRunner.runNext(job);
        long workOrderId = resourceId(sourceId, "mes_src.work_order");
        long equipmentId = resourceId(sourceId, "mes_src.equipment");
        // 첫 적재(197)가 끝나 기준 LSN이 있는 상태.
        jdbcClient
                .sql("update source_resource set loaded_lsn = 100 where id = :id")
                .param("id", workOrderId)
                .update();

        jdbcClient
                .sql(
                        """
                        alter publication %1$s drop table mes_src.work_order;
                        alter publication %1$s add table mes_src.equipment;
                        """
                                .formatted(PUBLICATION))
                .update();
        queueCheck(sourceId);
        queuedJobRunner.runNext(job);

        // CDC에서 빠진 표는 기준 LSN을 비우고, 새로 들어온 표는 전체 읽기 전이라 기준 LSN이 없다.
        assertThat(strategy(workOrderId)).containsEntry("sync_strategy", "FULL_COMPARE");
        assertThat(strategy(workOrderId).get("loaded_lsn")).isNull();
        assertThat(strategy(equipmentId)).containsEntry("sync_strategy", "CDC");
        assertThat(strategy(equipmentId).get("loaded_lsn")).isNull();
    }

    @Test
    void keepsLoadedLsnWhileTableStaysInCdc() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        queueCheck(sourceId);
        queuedJobRunner.runNext(job);
        long workOrderId = resourceId(sourceId, "mes_src.work_order");
        jdbcClient
                .sql("update source_resource set loaded_lsn = 100 where id = :id")
                .param("id", workOrderId)
                .update();

        queueCheck(sourceId);
        queuedJobRunner.runNext(job);

        assertThat(strategy(workOrderId))
                .containsEntry("sync_strategy", "CDC")
                .containsEntry("loaded_lsn", 100L);
    }

    @Test
    void wrongSyncPasswordMarksSourceFailedWithoutCatalog() {
        long sourceId = createSource();
        storeAccounts(sourceId, "wrong-Pa55!", AI_PASSWORD);
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        AccountResult sync = result.result().accounts().getFirst();
        assertThat(sync.purpose()).isEqualTo("SYNC");
        assertThat(sync.status()).isEqualTo("FAILED");
        assertThat(sync.readableTableCount()).isNull();
        assertThat(sync.errorMessage()).contains("계정 인증에 실패했습니다");
        assertThat(source(sourceId)).containsEntry("connection_status", "FAILED");
        assertThat(resourceCount(sourceId)).isZero();
        assertThat(resultLine(checkId).get("result").toString()).contains("FAILED");
        assertNoSecret(checkId);
    }

    @Test
    void aiFailureStillConnectsWithSync() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, "wrong-Pa55!");
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        assertThat(result(sourceId, checkId).result().accounts())
                .extracting(AccountResult::purpose, AccountResult::status)
                .containsExactly(tuple("SYNC", "SUCCEEDED"), tuple("AI", "FAILED"));
        assertThat(source(sourceId)).containsEntry("connection_status", "CONNECTED");
        assertThat(resourceCount(sourceId)).isEqualTo(3);
    }

    @Test
    void unreachableSourceReportsConnectionFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        long sourceId = createSource("POSTGRES", config(closedPort));
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        AccountResult sync = result(sourceId, checkId).result().accounts().getFirst();
        assertThat(sync.status()).isEqualTo("FAILED");
        assertThat(sync.errorMessage()).contains("원천 DB에 접속하지 못했습니다");
        assertThat(source(sourceId)).containsEntry("connection_status", "FAILED");
    }

    @Test
    void failsWithoutSyncAccount() {
        long sourceId = createSource();
        credentialService.store(sourceId, CredentialPurpose.AI, AI_USER, AI_PASSWORD, null);
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).isEqualTo("SYNC 계정이 등록되지 않아 연결을 검사할 수 없습니다.");
        assertThat(source(sourceId)).containsEntry("connection_status", "UNVERIFIED");
        assertThat(resultLine(checkId)).containsEntry("status", "FAILED");
    }

    @Test
    void failsForSourceKindOtherThanPostgres() {
        long sourceId = createSource("CSV", "{\"path\": \"/data/erp\"}");
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("PostgreSQL 원천만 지원합니다");
    }

    @Test
    void failsWhenConnectionConfigIsMalformed() {
        long sourceId = createSource("POSTGRES", "{\"host\": \"db/../evil\", \"port\": 5432}");
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        long checkId = queueCheck(sourceId);

        queuedJobRunner.runNext(job);

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).isEqualTo("접속 설정의 host가 없거나 형식이 맞지 않습니다.");
    }

    @Test
    void failsWhenConnectionInfoChangesDuringCheck() {
        long sourceId = createSource();
        storeAccounts(sourceId, SYNC_PASSWORD, AI_PASSWORD);
        long checkId = queueCheck(sourceId);

        // 원천을 읽는 사이에 관리자가 SYNC 비밀번호를 바꾼 상황.
        queuedJobRunner.runNext(
                new QueuedJobHandler<SourceConnectionCheckJob.Outcome>() {
                    @Override
                    public QueuedJobType type() {
                        return job.type();
                    }

                    @Override
                    public SourceConnectionCheckJob.Outcome run(long jobId) {
                        SourceConnectionCheckJob.Outcome outcome = job.run(jobId);
                        credentialService.store(
                                sourceId, CredentialPurpose.SYNC, SYNC_USER, "changed-Pa55!", null);
                        return outcome;
                    }

                    @Override
                    public void complete(long jobId, SourceConnectionCheckJob.Outcome result) {
                        job.complete(jobId, result);
                    }
                });

        ConnectionCheckResultResponse result = result(sourceId, checkId);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).isEqualTo("검사하는 동안 원천 접속 정보가 바뀌었습니다. 다시 검사해 주세요.");
        assertThat(source(sourceId)).containsEntry("connection_status", "UNVERIFIED");
        assertThat(resourceCount(sourceId)).isZero();
    }

    private long createSource() {
        return createSource("POSTGRES", config(postgres.getMappedPort(5432)));
    }

    private String config(int port) {
        return """
                {"host": "%s", "port": %d, "database": "%s"}
                """
                .formatted(postgres.getHost(), port, postgres.getDatabaseName());
    }

    private long createSource(String kind, String config) {
        return jdbcClient
                .sql(
                        """
                        insert into data_source (code, name, source_kind, connection_config)
                        values (:code, '테스트 MES', :kind, cast(:config as jsonb))
                        returning id
                        """)
                .param("code", "SRC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .param("kind", kind)
                .param("config", config)
                .query(Long.class)
                .single();
    }

    private void storeAccounts(long sourceId, String syncPassword, String aiPassword) {
        credentialService.store(sourceId, CredentialPurpose.SYNC, SYNC_USER, syncPassword, null);
        credentialService.store(sourceId, CredentialPurpose.AI, AI_USER, aiPassword, null);
    }

    private long queueCheck(long sourceId) {
        return jdbcClient
                .sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by, requested_at)
                        values (:sourceId, :accountId, clock_timestamp())
                        returning id
                        """)
                .param("sourceId", sourceId)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private ConnectionCheckResultResponse result(long sourceId, long checkId) {
        return ConnectionCheckResultResponse.from(
                queryRepository.findCheck(sourceId, checkId).orElseThrow());
    }

    private Map<String, Object> source(long sourceId) {
        return jdbcClient
                .sql("select connection_status, last_checked_at from data_source where id = :id")
                .param("id", sourceId)
                .query()
                .singleRow();
    }

    private long resourceId(long sourceId, String resourceKey) {
        return jdbcClient
                .sql(
                        "select id from source_resource where data_source_id = :id and resource_key = :key")
                .param("id", sourceId)
                .param("key", resourceKey)
                .query(Long.class)
                .single();
    }

    private int resourceCount(long sourceId) {
        return jdbcClient
                .sql("select count(*) from source_resource where data_source_id = :id")
                .param("id", sourceId)
                .query(Integer.class)
                .single();
    }

    private Map<String, Object> strategy(long resourceId) {
        return jdbcClient
                .sql("select sync_strategy, loaded_lsn from source_resource where id = :id")
                .param("id", resourceId)
                .query()
                .singleRow();
    }

    private boolean readable(long resourceId) {
        return jdbcClient
                .sql("select is_readable from source_resource where id = :id")
                .param("id", resourceId)
                .query(Boolean.class)
                .single();
    }

    private List<String> presentColumns(long resourceId) {
        return jdbcClient
                .sql(
                        """
                        select column_name from source_column
                        where source_resource_id = :id and is_present order by ordinal_position
                        """)
                .param("id", resourceId)
                .query(String.class)
                .list();
    }

    private Map<String, Object> resultLine(long checkId) {
        return jdbcClient
                .sql(
                        """
                        select actor_type, entry_point, status, result from action_execution
                        where target_type = 'source_connection_check' and target_id = :id
                          and actor_type = 'SYSTEM'
                        """)
                .param("id", checkId)
                .query()
                .singleRow();
    }

    private void assertNoSecret(long checkId) {
        String stored =
                jdbcClient
                        .sql(
                                """
                                select c.result::text || coalesce(c.error_message, '') || a.result::text
                                from source_connection_check c
                                join action_execution a on a.target_type = 'source_connection_check'
                                                       and a.target_id = c.id
                                where c.id = :id
                                """)
                        .param("id", checkId)
                        .query(String.class)
                        .single();
        assertThat(stored).doesNotContain(SYNC_PASSWORD, AI_PASSWORD, "wrong-Pa55!");
    }
}

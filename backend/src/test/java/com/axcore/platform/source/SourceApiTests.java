package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.service.SourceCredentialService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 원천 목록·등록·상세·수정(API 009~012)을 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourceApiTests extends SourceApiTestSupport {

    @Autowired SourceCredentialService credentialService;

    @Test
    void createsAndReadsSource() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String code = uniqueCode();

        send(admin, post(BASE_PATH), postgresBody(code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.sourceKind").value("POSTGRES"))
                .andExpect(jsonPath("$.data.connectionStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.lastCheckedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.syncEnabled").value(false))
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.connectionConfig.host").value("10.0.0.5"))
                .andExpect(jsonPath("$.data.connectionConfig.port").value(15432))
                .andExpect(jsonPath("$.data.connectionConfig.database").value("erp"))
                .andExpect(jsonPath("$.data.credentials[*].purpose", contains("SYNC", "AI")))
                .andExpect(
                        jsonPath(
                                "$.data.credentials[*].username",
                                contains("platform_ro", "platform_ai")))
                .andExpect(jsonPath("$.data.credentials[0].password").doesNotExist())
                .andExpect(content().string(not(containsString(SYNC_PASSWORD))))
                .andExpect(jsonPath("$.data.rowVersion").value(0));
        long id = idOf(code);

        mockMvc.perform(get(BASE_PATH + "/" + id).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.latestConnectionCheck").value(nullValue()));
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("sourceKind", "POSTGRES")
                                .param("connectionStatus", "UNVERIFIED")
                                .param("size", "200")
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code", hasItem(code)))
                .andExpect(jsonPath("$.meta.page").value(0));
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("connectionStatus", "CONNECTED")
                                .param("size", "200")
                                .cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].code", not(hasItem(code))));
    }

    @Test
    void registersFileSourceWithSyncInterval() throws Exception {
        LoginSession admin = loginAs("ADMIN");

        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "작업 메모", "sourceKind": "DOCUMENT",
                         "syncEnabled": true, "syncIntervalMinutes": 60, "connectionConfig": {}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.syncEnabled").value(true))
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(60))
                .andExpect(jsonPath("$.data.connectionConfig").isEmpty());
    }

    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        LoginSession admin = loginAs("ADMIN");

        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 1, "database": "d", "Password": "x"}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("connectionConfig"))
                .andExpect(content().string(not(containsString("\"x\""))));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 1}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("connectionConfig"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 70000, "database": "d"}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("connectionConfig.port"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "메모", "sourceKind": "CSV",
                         "syncEnabled": true, "connectionConfig": {}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("syncIntervalMinutes"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "erp-main", "name": "ERP", "sourceKind": "CSV", "connectionConfig": {}}
                        """)
                .andExpect(status().isBadRequest());
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "MYSQL", "connectionConfig": {}}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storesCredentialsEncrypted() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());

        byte[] ciphertext =
                jdbcClient
                        .sql(
                                "select secret_ciphertext from data_source_credential where data_source_id = :id and purpose = 'SYNC'")
                        .param("id", id)
                        .query(byte[].class)
                        .single();
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(SYNC_PASSWORD);
        assertThat(credentialService.findAccount(id, CredentialPurpose.SYNC))
                .contains(new SourceAccount("platform_ro", SYNC_PASSWORD));
        assertThat(credentialService.findAccount(id, CredentialPurpose.AI))
                .contains(new SourceAccount("platform_ai", AI_PASSWORD));
        assertThat(
                        jdbcClient
                                .sql(
                                        "select count(*) from data_source_credential where data_source_id = :id and created_by = :account")
                                .param("id", id)
                                .param("account", admin.accountId())
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void rejectsInvalidCredentials() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String onlySync = uniqueCode();

        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 1, "database": "d"},
                         "credentials": [{"purpose": "SYNC", "username": "platform_ro", "password": "s3cret!"}]}
                        """
                                .formatted(onlySync))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("credentials"))
                .andExpect(content().string(not(containsString("s3cret!"))));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 1, "database": "d"},
                         "credentials": [{"purpose": "SYNC", "username": "a", "password": "p"},
                                         {"purpose": "SYNC", "username": "b", "password": "p"},
                                         {"purpose": "AI", "username": "c", "password": "p"}]}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("credentials"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "메모", "sourceKind": "CSV", "connectionConfig": {},
                         "credentials": [{"purpose": "SYNC", "username": "a", "password": "p"}]}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("credentials"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "ERP", "sourceKind": "POSTGRES",
                         "connectionConfig": {"host": "h", "port": 1, "database": "d"},
                         "credentials": [{"purpose": "ETL", "username": "a", "password": "p"},
                                         {"purpose": "AI", "username": "c", "password": ""}]}
                        """
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH).param("size", "200").cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].code", not(hasItem(onlySync))));
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String code = uniqueCode();
        createPostgresSource(admin, code);

        send(admin, post(BASE_PATH), postgresBody(code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void replacesOnlySentCredentialAndResetsStatus() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());
        markConnected(id);

        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"credentials": [{"purpose": "AI", "username": "platform_ai2", "password": "new-ai-Pa55!"}],
                         "rowVersion": 0}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.rowVersion").value(1))
                .andExpect(
                        jsonPath(
                                "$.data.credentials[*].username",
                                contains("platform_ro", "platform_ai2")))
                .andExpect(content().string(not(containsString("new-ai-Pa55!"))));

        assertThat(credentialService.findAccount(id, CredentialPurpose.AI))
                .contains(new SourceAccount("platform_ai2", "new-ai-Pa55!"));
        assertThat(credentialService.findAccount(id, CredentialPurpose.SYNC))
                .contains(new SourceAccount("platform_ro", SYNC_PASSWORD));
        // 계정만 바꿔도 버전이 올라 이전 화면의 수정은 충돌한다.
        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"다른 이름\", \"rowVersion\": 0}")
                .andExpect(status().isConflict());
    }

    @Test
    void updatesNameAndResetsStatusOnlyWhenConfigChanges() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());
        markConnected(id);

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"본사 ERP\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("본사 ERP"))
                .andExpect(jsonPath("$.data.connectionStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));
        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"connectionConfig": {"host": "10.0.0.5", "port": 15432, "database": "erp"},
                         "rowVersion": 1}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("CONNECTED"));
        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"connectionConfig": {"host": "10.0.0.6", "port": 15432, "database": "erp"},
                         "rowVersion": 1}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionConfig.host").value("10.0.0.6"))
                .andExpect(jsonPath("$.data.connectionStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.rowVersion").value(2));
    }

    @Test
    void rejectsInvalidUpdates() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"새 이름\", \"rowVersion\": 5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.actual").value(0));
        send(admin, patch(BASE_PATH + "/" + id), "{\"rowVersion\": 0}")
                .andExpect(status().isBadRequest());
        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \" \", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest());
        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        "{\"connectionConfig\": {\"host\": \"h\"}, \"rowVersion\": 0}")
                .andExpect(status().isBadRequest());
        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"credentials": [{"purpose": "AI", "username": "a", "password": "p"},
                                         {"purpose": "AI", "username": "b", "password": "p"}],
                         "rowVersion": 0}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("credentials"));
        send(admin, patch(BASE_PATH + "/" + id), "{\"credentials\": [], \"rowVersion\": 0}")
                .andExpect(status().isBadRequest());
        send(admin, patch(BASE_PATH + "/999999999"), "{\"name\": \"x\", \"rowVersion\": 0}")
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE_PATH + "/999999999").cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    /**
     * 199: 접속 설정(host·port·database)이 실제로 바뀌면 소비 중인 원천의 CDC 반영을 멈춘다(SOURCE_CHANGED). 다시 읽기 표시는 하지
     * 않는다(커넥터가 아직 옛 DB를 본다). 이름만 바꾸거나 소비 전 원천이면 그대로다.
     */
    @Test
    void configChangeStopsCdcOfConsumedSourceWithoutReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());
        long unconsumed = createPostgresSource(admin, uniqueCode());
        long table = cdcTable(id, 7L);
        String topic = cdcOffset(id);

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"본사 ERP\", \"rowVersion\": 0}")
                .andExpect(status().isOk());
        assertThat(recovery(topic)).containsEntry("recovery_reason", null);

        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"connectionConfig": {"host": "10.0.0.9", "port": 15432, "database": "erp"},
                         "rowVersion": 1}
                        """)
                .andExpect(status().isOk());
        send(
                        admin,
                        patch(BASE_PATH + "/" + unconsumed),
                        """
                        {"connectionConfig": {"host": "10.0.0.9", "port": 15432, "database": "erp"},
                         "rowVersion": 0}
                        """)
                .andExpect(status().isOk());

        Map<String, Object> state = recovery(topic);
        assertThat(state.get("recovery_reason")).isEqualTo("SOURCE_CHANGED");
        assertThat(state.get("recovery_at")).isNotNull();
        assertThat(state.get("healthy_since")).isNull();
        assertThat(loadedLsn(table)).isEqualTo(7L);
        assertThat(
                        jdbcClient
                                .sql("select count(*) from cdc_offset where data_source_id = :id")
                                .param("id", unconsumed)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    /**
     * 385: 아직 소비하지 않은 원천이라도 CDC 테이블이 있으면 접속 정보를 바꿀 때 소비 상태 행을 원천 교체로 만든다. 토픽에 쌓인 옛 DB 이벤트를 새 DB 스냅샷
     * 위에 반영하지 않도록, 재연결 전까지 첫 적재·반영을 멈춘다. 재연결하면 다른 원천과 같이 다시 읽는다.
     */
    @Test
    void configChangeStopsCdcOfSourceNotConsumedYet() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String code = uniqueCode();
        long id = createPostgresSource(admin, code);
        long table = cdcTable(id, 7L);

        send(
                        admin,
                        patch(BASE_PATH + "/" + id),
                        """
                        {"connectionConfig": {"host": "10.0.0.9", "port": 15432, "database": "erp"},
                         "rowVersion": 0}
                        """)
                .andExpect(status().isOk());

        Map<String, Object> state = recovery(code + ".all");
        assertThat(state.get("recovery_reason")).isEqualTo("SOURCE_CHANGED");
        assertThat(state.get("recovery_at")).isNotNull();
        assertThat(state.get("healthy_since")).isNull();
        assertThat(loadedLsn(table)).isEqualTo(7L);

        send(admin, post(BASE_PATH + "/" + id + "/cdc/reconnected"), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.recoveryReason").value("SLOT_LOST"))
                .andExpect(jsonPath("$.data.reloadTables").value(1));
        assertThat(recovery(code + ".all").get("recovery_reason")).isEqualTo("SLOT_LOST");
        assertThat(loadedLsn(table)).isNull();
    }

    /** 199: 원천 교체 뒤 커넥터를 맞췄다고 알리면 슬롯 재생성 복구로 바꾸고 CDC 테이블을 모두 다시 읽기 대상으로 표시한다. 교체 중이 아니면 거절한다. */
    @Test
    void reconnectAfterSourceChangeRequestsReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createPostgresSource(admin, uniqueCode());
        long table = cdcTable(id, 7L);
        String topic = cdcOffset(id);

        send(admin, post(BASE_PATH + "/" + id + "/cdc/reconnected"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.expected").value("SOURCE_CHANGED"));
        assertThat(loadedLsn(table)).isEqualTo(7L);

        jdbcClient
                .sql(
                        """
                        update cdc_offset set recovery_reason = 'SOURCE_CHANGED',
                                              recovery_at = now() - interval '1 hour', healthy_since = null
                        where topic = :t
                        """)
                .param("t", topic)
                .update();
        send(admin, post(BASE_PATH + "/" + id + "/cdc/reconnected"), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.dataSourceId").value(id))
                .andExpect(jsonPath("$.data.recoveryReason").value("SLOT_LOST"))
                .andExpect(jsonPath("$.data.reloadTables").value(1));

        Map<String, Object> state = recovery(topic);
        assertThat(state.get("recovery_reason")).isEqualTo("SLOT_LOST");
        assertThat(((java.sql.Timestamp) state.get("recovery_at")).toInstant())
                .isAfter(java.time.Instant.now().minusSeconds(60));
        assertThat(loadedLsn(table)).isNull();

        LoginSession viewer = loginAs("VIEWER");
        send(viewer, post(BASE_PATH + "/" + id + "/cdc/reconnected"), null)
                .andExpect(status().isForbidden());
        send(admin, post(BASE_PATH + "/999999999/cdc/reconnected"), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        mockMvc.perform(get(BASE_PATH).cookie(viewer.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        send(viewer, post(BASE_PATH), postgresBody(uniqueCode())).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
    }

    private long idOf(String code) {
        return jdbcClient
                .sql("select id from data_source where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long cdcTable(long sourceId, long loadedLsn) {
        return jdbcClient
                .sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind, sync_strategy,
                                                     is_readable, loaded_lsn)
                        values (:s, 'erp.work_order', 'TABLE', 'CDC', true, :lsn) returning id
                        """)
                .param("s", sourceId)
                .param("lsn", loadedLsn)
                .query(Long.class)
                .single();
    }

    /** 소비 중인 원천(정상). */
    private String cdcOffset(long sourceId) {
        String topic = "T_" + UUID.randomUUID() + ".all";
        jdbcClient
                .sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset, healthy_since)
                        values (:t, 0, :s, 3, now())
                        """)
                .param("t", topic)
                .param("s", sourceId)
                .update();
        return topic;
    }

    private Map<String, Object> recovery(String topic) {
        return jdbcClient
                .sql(
                        "select recovery_reason, recovery_at, healthy_since from cdc_offset where topic = :t")
                .param("t", topic)
                .query()
                .singleRow();
    }

    private Long loadedLsn(long resourceId) {
        return jdbcClient
                .sql("select loaded_lsn from source_resource where id = :id")
                .param("id", resourceId)
                .query((rs, row) -> (Long) rs.getObject("loaded_lsn"))
                .list()
                .getFirst();
    }

    private void markConnected(long id) {
        jdbcClient
                .sql("update data_source set connection_status = 'CONNECTED' where id = :id")
                .param("id", id)
                .update();
    }
}

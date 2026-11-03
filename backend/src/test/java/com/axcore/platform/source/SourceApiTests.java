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

    private void markConnected(long id) {
        jdbcClient
                .sql("update data_source set connection_status = 'CONNECTED' where id = :id")
                .param("id", id)
                .update();
    }
}

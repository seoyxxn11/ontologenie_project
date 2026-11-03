package com.axcore.platform.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import com.axcore.platform.source.service.SourceCredentialCipher;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 018 접수 뒤 FastAPI 시작 알림(API 018 하단). 가짜 AI 서버로 보낸 본문과 실패 시 FAILED 전환을 확인한다. 키·비밀번호는 시험용 값이다. */
@SpringBootTest(
        properties = {
            "axcore.internal-api.token=analysis-start-test-token",
            "axcore.ai.read-timeout=500ms",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AnalysisStartApiTests {
    private static final String TOKEN = "analysis-start-test-token";
    private static final String PASSWORD = "ai-password-for-start-test";
    private static final FakeAi AI = FakeAi.start();

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired SourceCredentialCipher cipher;
    long accountId;
    String code;
    long source;

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("axcore.ai.base-url", AI::baseUrl);
    }

    @AfterAll
    static void stop() {
        AI.server.stop(0);
    }

    @BeforeEach
    void fixtures() {
        AI.reset();
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '분석 관리자', 'test-hash', (select id from role where code='ADMIN'), false) returning id
                """)
                        .param("login", "start-analysis-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        code = "START_" + UUID.randomUUID();
        source =
                dataSource(
                        code,
                        "POSTGRES",
                        "{\"host\":\"erp-db\",\"port\":5432,\"database\":\"erp\"}");
    }

    @Test
    void sendsDecryptedAiAccountAndSelectedTargetsOnceAfterCommit(CapturedOutput output)
            throws Exception {
        credential(source, CredentialPurpose.AI, "erp_ai", PASSWORD);
        credential(source, CredentialPurpose.SYNC, "erp_sync", "sync-" + PASSWORD);
        long item = resource(source, "erp.item", "{\"description\":\"품목\"}");
        long customer = resource(source, "erp.customer", "{}");

        long id = runId(send(item, customer));

        assertThat(AI.requests).hasSize(1);
        var request = AI.requests.peek();
        assertThat(request.token()).isEqualTo(TOKEN);
        JsonNode body = json.readTree(request.body());
        assertThat(body.path("runId").asLong()).isEqualTo(id);
        assertThat(body.path("runKind").asString()).isEqualTo("INITIAL");
        assertThat(body.path("targets")).hasSize(2);
        JsonNode first = body.path("targets").get(0);
        assertThat(first.path("sourceResourceId").asLong()).isEqualTo(item);
        assertThat(first.path("dataSourceCode").asString()).isEqualTo(code);
        assertThat(first.path("resourceKey").asString()).isEqualTo("erp.item");
        assertThat(first.path("columns").toString()).isEqualTo("[\"code\",\"name\"]");
        assertThat(first.path("sourceMetadata").path("description").asString()).isEqualTo("품목");
        assertThat(body.path("targets").get(1).has("columns")).isFalse();
        assertThat(body.path("sources")).hasSize(1);
        JsonNode sourceNode = body.path("sources").get(0);
        assertThat(sourceNode.path("dataSourceCode").asString()).isEqualTo(code);
        assertThat(sourceNode.path("sourceKind").asString()).isEqualTo("POSTGRES");
        assertThat(sourceNode.path("schema").asString()).isEqualTo("erp");
        assertThat(sourceNode.has("path")).isFalse();
        JsonNode connection = sourceNode.path("connection");
        assertThat(connection.path("host").asString()).isEqualTo("erp-db");
        assertThat(connection.path("port").asInt()).isEqualTo(5432);
        assertThat(connection.path("database").asString()).isEqualTo("erp");
        assertThat(connection.path("sslmode").asString()).isEqualTo("require");
        assertThat(connection.path("username").asString()).isEqualTo("erp_ai");
        assertThat(connection.path("password").asString()).isEqualTo(PASSWORD);
        assertThat(runStatus(id)).isEqualTo("QUEUED");

        send(item, customer).andExpect(jsonPath("$.data.joined").value(true));
        assertThat(AI.requests).hasSize(1);
        assertThat(output).doesNotContain(PASSWORD).doesNotContain(TOKEN);
    }

    @Test
    void alreadyRunningAnswerKeepsRunQueued() throws Exception {
        credential(source, CredentialPurpose.AI, "erp_ai", PASSWORD);
        AI.respond(202, "{\"runId\":1,\"accepted\":false}", 0);

        long id = runId(send(resource(source, "erp.item", "{}")));

        assertThat(AI.requests).hasSize(1);
        assertThat(runStatus(id)).isEqualTo("QUEUED");
    }

    @Test
    void aiErrorFailsRunWithReasonShownIn019(CapturedOutput output) throws Exception {
        credential(source, CredentialPurpose.AI, "erp_ai", PASSWORD);
        AI.respond(500, "{\"detail\":\"boom\"}", 0);

        long id = runId(send(resource(source, "erp.item", "{}")));

        assertFailed(id, "AI 서비스에 분석 시작을 알리지 못했습니다");
        assertThat(output).doesNotContain(PASSWORD).doesNotContain(TOKEN);
    }

    @Test
    void aiTimeoutAndMalformedAnswerFailRun() throws Exception {
        credential(source, CredentialPurpose.AI, "erp_ai", PASSWORD);
        AI.respond(202, "{\"runId\":1,\"accepted\":true}", 1500);
        long slow = runId(send(resource(source, "erp.item", "{}")));
        assertFailed(slow, "AI 서비스에 분석 시작을 알리지 못했습니다");

        AI.respond(202, "{\"runId\":1}", 0);
        long malformed = runId(send(resource(source, "erp.customer", "{}")));
        assertFailed(malformed, "AI 서비스에 분석 시작을 알리지 못했습니다");
    }

    @Test
    void missingAiAccountFailsRunWithoutCallingAi() throws Exception {
        credential(source, CredentialPurpose.SYNC, "erp_sync", PASSWORD);

        long id = runId(send(resource(source, "erp.item", "{}")));

        assertThat(AI.requests).isEmpty();
        assertFailed(id, "원천 AI 계정이 등록되지 않았습니다 (" + code + ")");
    }

    @Test
    void tablesFromSeveralSchemasOfOneSourceFailRun() throws Exception {
        credential(source, CredentialPurpose.AI, "erp_ai", PASSWORD);

        long id =
                runId(send(resource(source, "erp.item", "{}"), resource(source, "mes.lot", "{}")));

        assertThat(AI.requests).isEmpty();
        assertFailed(id, "한 원천에서 여러 스키마의 테이블을 함께 분석할 수 없습니다 (" + code + ")");
    }

    @Test
    void csvSendsFolderPathAndFailsWithoutIt() throws Exception {
        String withPath = "CSV_" + UUID.randomUUID();
        long csv = dataSource(withPath, "CSV", "{\"path\":\"/data/erp\"}");
        long id = runId(send(resource(csv, "erp.item", "{}")));
        JsonNode sourceNode = json.readTree(AI.requests.peek().body()).path("sources").get(0);
        assertThat(sourceNode.path("sourceKind").asString()).isEqualTo("CSV");
        assertThat(sourceNode.path("path").asString()).isEqualTo("/data/erp");
        assertThat(sourceNode.path("connection").isEmpty()).isTrue();
        assertThat(sourceNode.has("schema")).isFalse();
        assertThat(runStatus(id)).isEqualTo("QUEUED");

        String withoutPath = "CSV_" + UUID.randomUUID();
        long noPath = dataSource(withoutPath, "CSV", "{}");
        long failed = runId(send(resource(noPath, "erp.item", "{}")));
        assertThat(AI.requests).hasSize(1);
        assertFailed(failed, "CSV 원천의 폴더 경로가 없습니다 (" + withoutPath + ")");
    }

    private void assertFailed(long id, String reason) throws Exception {
        mvc.perform(get("/api/v1/analyses/{id}", id).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorMessage").value(reason))
                .andExpect(jsonPath("$.data.finishedAt").isNotEmpty());
        assertThat(
                        jdbc.sql(
                                        """
                        select count(*) from action_execution
                        where target_type='analysis_run' and target_id=:id and event_kind='ANALYSIS_RUN'
                          and actor_type='SYSTEM' and entry_point='SYSTEM' and status='FAILED'
                        """)
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    /** 첫 자원은 컬럼 두 개만, 나머지는 전체 컬럼으로 분석을 요청한다. */
    private ResultActions send(long withColumns, long... others) throws Exception {
        StringBuilder targets =
                new StringBuilder("{\"sourceResourceId\":")
                        .append(withColumns)
                        .append(",\"columns\":[\"name\",\"code\"]}");
        for (long other : others) {
            targets.append(",{\"sourceResourceId\":").append(other).append('}');
        }
        return mvc.perform(
                        post("/api/v1/analyses")
                                .with(authentication(admin()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"runKind\":\"INITIAL\",\"targets\":[" + targets + "]}"))
                .andExpect(status().isAccepted());
    }

    private long runId(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    private Authentication admin() {
        return new LoginAccount(accountId, "analysis-start-test", "ADMIN", false)
                .toAuthentication();
    }

    private String runStatus(long id) {
        return jdbc.sql("select status from analysis_run where id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private long dataSource(String code, String kind, String config) {
        return jdbc.sql(
                        """
                insert into data_source(code, name, source_kind, connection_config)
                values (:code, '시작 알림 원천', :kind, cast(:config as jsonb)) returning id
                """)
                .param("code", code)
                .param("kind", kind)
                .param("config", config)
                .query(Long.class)
                .single();
    }

    private long resource(long source, String key, String metadata) {
        long id =
                jdbc.sql(
                                """
                insert into source_resource(data_source_id, resource_key, resource_kind, source_metadata)
                values (:source, :key, 'TABLE', cast(:metadata as jsonb)) returning id
                """)
                        .param("source", source)
                        .param("key", key)
                        .param("metadata", metadata)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into source_column(source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                values (:id, 'code', 'text', false, 1), (:id, 'name', 'text', true, 2)
                """)
                .param("id", id)
                .update();
        return id;
    }

    private void credential(
            long dataSourceId, CredentialPurpose purpose, String username, String password) {
        EncryptedSecret secret = cipher.encrypt(dataSourceId, purpose, password);
        jdbc.sql(
                        """
                insert into data_source_credential(data_source_id, purpose, username,
                                                   secret_ciphertext, secret_nonce, key_version)
                values (:dataSourceId, :purpose, :username, :ciphertext, :nonce, :keyVersion)
                """)
                .param("dataSourceId", dataSourceId)
                .param("purpose", purpose.name())
                .param("username", username)
                .param("ciphertext", secret.ciphertext())
                .param("nonce", secret.nonce())
                .param("keyVersion", secret.keyVersion())
                .update();
    }

    /** 시작 알림을 받는 가짜 FastAPI. 받은 요청을 쌓고 정해 둔 상태 코드·본문·지연으로 답한다. */
    static final class FakeAi {
        final HttpServer server;
        final Queue<Received> requests = new ConcurrentLinkedQueue<>();
        volatile int status;
        volatile String answer;
        volatile long delayMillis;

        private FakeAi(HttpServer server) {
            this.server = server;
        }

        static FakeAi start() {
            try {
                var fake = new FakeAi(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
                fake.server.createContext(
                        "/internal/v1/analysis-runs",
                        exchange -> {
                            fake.requests.add(
                                    new Received(
                                            exchange.getRequestHeaders()
                                                    .getFirst("X-Internal-Token"),
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8)));
                            try {
                                Thread.sleep(fake.delayMillis);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            byte[] bytes = fake.answer.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            try {
                                exchange.sendResponseHeaders(fake.status, bytes.length);
                                exchange.getResponseBody().write(bytes);
                            } catch (IOException ignored) {
                                // 시간 제한으로 끊긴 연결
                            }
                            exchange.close();
                        });
                fake.server.setExecutor(Executors.newCachedThreadPool());
                fake.server.start();
                fake.reset();
                return fake;
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            requests.clear();
            respond(202, "{\"runId\":1,\"accepted\":true}", 0);
        }

        void respond(int status, String answer, long delayMillis) {
            this.status = status;
            this.answer = answer;
            this.delayMillis = delayMillis;
        }
    }

    record Received(String token, String body) {}
}

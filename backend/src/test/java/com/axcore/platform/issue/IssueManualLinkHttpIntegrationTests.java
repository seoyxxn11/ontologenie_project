package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.service.CheckRunJob;
import com.axcore.platform.source.service.CdcReloadJob;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 239의 요청 계약을 루프백 HTTP·실제 세션/CSRF·PostgreSQL·검사 배치로 확인한다. 업무 서비스는 대체하지 않고 스케줄러의 자동 가져오기만 막아 테스트가 실제
 * 실행기로 큐를 처리한다. fixture는 컨텍스트 전용 임시 DB에만 만든다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.main.keep-alive=false",
            "spring.main.web-application-type=servlet",
            "spring.ai.model.chat=none",
            "axcore.check.sync.enabled=false",
            "axcore.ai.base-url=",
            "axcore.action-notify.webhook-url="
        })
@ActiveProfiles({"api", "batch"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IssueManualLinkHttpIntegrationTests {

    private static final String TLS_PASSWORD = UUID.randomUUID().toString();
    private static final Path TLS_STORE = createTlsStore();

    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> TLS_STORE.toUri().toString());
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.key-store-password", () -> TLS_PASSWORD);
        properties.add("server.ssl.key-alias", () -> "issue239");
    }

    private static Path createTlsStore() {
        try {
            Path store = Files.createTempDirectory("issue239-https-").resolve("localhost.p12");
            String executable =
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            Process process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", executable)
                                            .toString(),
                                    "-genkeypair",
                                    "-alias",
                                    "issue239",
                                    "-keyalg",
                                    "RSA",
                                    "-keysize",
                                    "2048",
                                    "-validity",
                                    "1",
                                    "-dname",
                                    "CN=localhost",
                                    "-ext",
                                    "SAN=IP:127.0.0.1,DNS:localhost",
                                    "-storetype",
                                    "PKCS12",
                                    "-keystore",
                                    store.toString(),
                                    "-storepass",
                                    TLS_PASSWORD,
                                    "-noprompt")
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("테스트 TLS 인증서 생성 시간 초과");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("테스트 TLS 인증서 생성 실패");
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("루프백 HTTPS 테스트 준비 실패", failure);
        }
    }

    @AfterAll
    static void removeTlsStore() throws Exception {
        Files.deleteIfExists(TLS_STORE);
        Files.deleteIfExists(TLS_STORE.getParent());
    }

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;

    @Test
    void searchesLinksReverifiesAndResolvesThroughRealHttp() throws Exception {
        var fixture = new CheckRunTestFixtures(jdbc);
        long adminId = fixture.insertAdmin();
        var admin = login(adminId, "ADMIN");
        var viewer = login(fixture.insertAdmin(), "VIEWER");
        var anonymous = session();
        exchange(anonymous, "GET", "/issues", null, false, 401);

        long sourceType = fixture.insertObjectType("HTTP239_WO");
        long targetType = fixture.insertObjectType("HTTP239_EQ");
        jdbc.sql(
                        "update object_type set access_group = 'PRODUCTION' where id in (:source, :target)")
                .param("source", sourceType)
                .param("target", targetType)
                .update();
        long sourceProperty = fixture.insertProperty(sourceType, "eq_code");
        long targetProperty = fixture.insertProperty(targetType, "code");
        // ADMIN도 저장된 표시 이름을 그대로 받지 않는다. 공개 대표 속성을 갖춘 합성 유형을 쓴다.
        jdbc.sql("update object_type set display_property_id = :property where id = :type")
                .param("property", targetProperty)
                .param("type", targetType)
                .update();
        long dataSource = fixture.insertDataSource();
        long sourceResource = fixture.insertResource(dataSource, "mes.work_order");
        long targetResource = fixture.insertResource(dataSource, "mes.equipment");
        long sourceMapping = fixture.insertObjectMapping(sourceResource, sourceType, true);
        long targetMapping = fixture.insertObjectMapping(targetResource, targetType, true);
        fixture.insertSync(dataSource, sourceResource, adminId, "SUCCEEDED", Instant.now());
        fixture.insertSync(dataSource, targetResource, adminId, "SUCCEEDED", Instant.now());
        long linkType = fixture.insertLinkType(sourceType, targetType, "MANY_TO_ONE");
        long rule =
                new TransactionTemplate(transactions)
                        .execute(
                                tx -> {
                                    long id = fixture.insertLinkMapping(linkType, "ACTIVE", "{}");
                                    fixture.insertCondition(
                                            id, 1, sourceProperty, targetProperty, "[]", "[]");
                                    return id;
                                });
        long matchedSource =
                fixture.insertObject(
                        sourceMapping, sourceType, "HTTP239_WO1", "{\"eq_code\":\"E1\"}", "ACTIVE");
        long unmatchedSource =
                fixture.insertObject(
                        sourceMapping, sourceType, "HTTP239_WO2", "{\"eq_code\":\"E2\"}", "ACTIVE");
        fixture.insertObject(
                targetMapping, targetType, "HTTP239_EQ1", "{\"code\":\"E1\"}", "ACTIVE");
        long target =
                fixture.insertObject(
                        targetMapping, targetType, "HTTP239_EQ3", "{\"code\":\"E3\"}", "ACTIVE");
        long deleted =
                fixture.insertObject(
                        targetMapping,
                        targetType,
                        "HTTP239_DELETED",
                        "{\"code\":\"E4\"}",
                        "DELETED_AT_SOURCE");

        long baseline =
                exchange(
                                admin,
                                "POST",
                                "/checks/runs",
                                Map.of(
                                        "triggerKind",
                                        "MANUAL",
                                        "scope",
                                        Map.of(
                                                "kind",
                                                "LINK_MAPPINGS",
                                                "linkMappingIds",
                                                new long[] {rule})),
                                true,
                                202)
                        .path("data")
                        .path("id")
                        .asLong();
        assertThat(runner.runNext(job)).isTrue();
        assertThat(
                        exchange(admin, "GET", "/checks/runs/" + baseline, null, false, 200)
                                .path("data")
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        JsonNode before =
                exchange(
                                admin,
                                "GET",
                                "/checks/runs/" + baseline + "/results?linkMappingId=" + rule,
                                null,
                                false,
                                200)
                        .path("data")
                        .get(0);
        assertThat(before.path("matchedCount").asInt()).isEqualTo(1);
        assertThat(before.path("unmatchedCount").asInt()).isEqualTo(1);

        // 239는 이미 확인중인 문제에서 시작한다. 문제 등록 정책을 바꾸지 않고 합성 문제를 준비한다.
        long issue =
                new IssueTestFixtures(jdbc)
                        .insertIssue(baseline, rule, "UNMATCHED", "CHECKING", "{}");
        jdbc.sql("update issue set object_instance_id = :source where id = :id")
                .param("source", unmatchedSource)
                .param("id", issue)
                .update();
        String path = "/issues/" + issue;
        JsonNode detail = exchange(admin, "GET", path, null, false, 200).path("data");
        assertThat(detail.path("objectInstanceId").asLong()).isEqualTo(unmatchedSource);
        assertThat(detail.path("rowVersion").asInt()).isZero();

        JsonNode types = exchange(admin, "GET", "/objects/types", null, false, 200).path("data");
        boolean targetTypeVisible = false;
        for (JsonNode type : types) {
            if (type.path("id").asLong() == targetType) targetTypeVisible = true;
        }
        assertThat(targetTypeVisible).isTrue();
        JsonNode search =
                exchange(
                        admin,
                        "GET",
                        "/objects?objectTypeId=" + targetType + "&q=E3&page=0&size=8",
                        null,
                        false,
                        200);
        assertThat(search.path("data").size()).isEqualTo(1);
        assertThat(search.path("data").get(0).path("id").asLong()).isEqualTo(target);
        assertThat(search.path("data").get(0).path("displayName").asText()).isEqualTo("E3");

        Map<String, Object> body =
                Map.of("targetObjectId", target, "reason", "239 합성 HTTP 검증", "rowVersion", 0);
        exchange(viewer, "POST", path + "/manual-links", body, true, 403);
        exchange(admin, "POST", path + "/manual-links", body, false, 403);
        exchange(
                admin,
                "POST",
                path + "/manual-links",
                Map.of("targetObjectId", target, "rowVersion", 0),
                true,
                400);
        assertThat(
                        exchange(
                                        admin,
                                        "POST",
                                        path + "/manual-links",
                                        Map.of(
                                                "targetObjectId",
                                                target,
                                                "reason",
                                                "버전 충돌 검증",
                                                "rowVersion",
                                                1),
                                        true,
                                        409)
                                .path("error")
                                .path("code")
                                .asText())
                .isEqualTo("VERSION_CONFLICT");
        assertThat(
                        exchange(
                                        admin,
                                        "POST",
                                        path + "/manual-links",
                                        Map.of(
                                                "targetObjectId",
                                                matchedSource,
                                                "reason",
                                                "유형 오류 검증",
                                                "rowVersion",
                                                0),
                                        true,
                                        422)
                                .path("error")
                                .path("code")
                                .asText())
                .isEqualTo("LINK_END_TYPE_MISMATCH");
        assertThat(
                        exchange(
                                        admin,
                                        "POST",
                                        path + "/manual-links",
                                        Map.of(
                                                "targetObjectId",
                                                deleted,
                                                "reason",
                                                "삭제 객체 검증",
                                                "rowVersion",
                                                0),
                                        true,
                                        422)
                                .path("error")
                                .path("code")
                                .asText())
                .isEqualTo("LINK_END_NOT_ACTIVE");
        exchange(
                admin,
                "POST",
                path + "/manual-links",
                Map.of("targetObjectId", Long.MAX_VALUE, "reason", "없는 객체 검증", "rowVersion", 0),
                true,
                404);

        JsonNode saved =
                exchange(admin, "POST", path + "/manual-links", body, true, 200).path("data");
        assertThat(saved.path("issueId").asLong()).isEqualTo(issue);
        assertThat(saved.path("outcome").asText()).isEqualTo("CREATED");
        long linkId = saved.path("linkId").asLong();
        long auditId = saved.path("actionExecutionId").asLong();
        assertThat(linkId).isPositive();
        assertThat(auditId).isPositive();
        assertThat(
                        exchange(admin, "GET", path, null, false, 200)
                                .path("data")
                                .path("status")
                                .asText())
                .isEqualTo("CHECKING");
        assertThat(
                        exchange(admin, "POST", path + "/manual-links", body, true, 409)
                                .path("error")
                                .path("code")
                                .asText())
                .isEqualTo("LINK_ALREADY_EXISTS");
        assertThat(
                        jdbc.sql("select origin from object_link where id = :id")
                                .param("id", linkId)
                                .query(String.class)
                                .single())
                .isEqualTo("MANUAL");
        assertThat(
                        jdbc.sql(
                                        "select count(*) from entity_change where action_execution_id = :id and table_name = 'object_link'")
                                .param("id", auditId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
        exchange(admin, "GET", "/audit/actions/" + auditId, null, false, 200);
        exchange(
                admin,
                "GET",
                "/objects/" + unmatchedSource + "/links?page=0&size=100",
                null,
                false,
                200);

        JsonNode fixed =
                exchange(
                                admin,
                                "PATCH",
                                path + "/status",
                                Map.of(
                                        "status",
                                        "FIXED_PENDING_REVERIFY",
                                        "reason",
                                        "수동 연결 조치완료",
                                        "rowVersion",
                                        0),
                                true,
                                200)
                        .path("data");
        assertThat(fixed.path("status").asText()).isEqualTo("FIXED_PENDING_REVERIFY");
        long version = fixed.path("rowVersion").asLong();
        // 통과 기록 없이 해결할 수 없어야 한다.
        exchange(
                admin,
                "PATCH",
                path + "/status",
                Map.of(
                        "status",
                        "RESOLVED",
                        "reason",
                        "미통과 검사 차단 확인",
                        "rowVersion",
                        version,
                        "resolvedCheckRunId",
                        baseline),
                true,
                422);

        long reverify =
                exchange(
                                admin,
                                "POST",
                                "/checks/runs",
                                Map.of(
                                        "triggerKind",
                                        "REVERIFY",
                                        "scope",
                                        Map.of("kind", "ISSUES", "issueIds", new long[] {issue}),
                                        "comparedToRunId",
                                        baseline),
                                true,
                                202)
                        .path("data")
                        .path("id")
                        .asLong();
        assertThat(runner.runNext(job)).isTrue();
        assertThat(
                        exchange(admin, "GET", "/checks/runs/" + reverify, null, false, 200)
                                .path("data")
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        JsonNode after =
                exchange(
                                admin,
                                "GET",
                                "/checks/runs/" + reverify + "/results?linkMappingId=" + rule,
                                null,
                                false,
                                200)
                        .path("data")
                        .get(0);
        assertThat(after.path("matchedCount").asInt()).isEqualTo(2);
        assertThat(after.path("unmatchedCount").asInt()).isZero();
        assertThat(after.path("sampleRows").isArray()).isTrue();
        assertThat(after.path("sampleRows").size()).isZero();
        JsonNode verification =
                exchange(admin, "GET", path + "/verifications", null, false, 200)
                        .path("data")
                        .get(0);
        assertThat(verification.path("checkRunId").asLong()).isEqualTo(reverify);
        assertThat(verification.path("outcome").asText()).isEqualTo("PASSED");
        assertThat(verification.path("detail").path("matchedBy").asText()).isEqualTo("MANUAL_LINK");
        assertThat(
                        exchange(admin, "GET", path, null, false, 200)
                                .path("data")
                                .path("status")
                                .asText())
                .isEqualTo("FIXED_PENDING_REVERIFY");

        JsonNode resolved =
                exchange(
                                admin,
                                "PATCH",
                                path + "/status",
                                Map.of(
                                        "status",
                                        "RESOLVED",
                                        "reason",
                                        "수동 연결 재검증 통과",
                                        "rowVersion",
                                        version,
                                        "resolvedCheckRunId",
                                        reverify),
                                true,
                                200)
                        .path("data");
        assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.path("resolvedCheckRunId").asLong()).isEqualTo(reverify);
        JsonNode history = exchange(admin, "GET", path + "/history", null, false, 200).path("data");
        var statuses = new ArrayList<String>();
        for (JsonNode entry : history) statuses.add(entry.path("afterStatus").asText());
        assertThat(statuses).contains("FIXED_PENDING_REVERIFY", "RESOLVED");
    }

    private Session login(long accountId, String role) throws Exception {
        String password = UUID.randomUUID() + "!A1";
        jdbc.sql(
                        "update account set password_hash = :hash, role_id = (select id from role where code = :role) where id = :id")
                .param("hash", passwords.encode(password))
                .param("role", role)
                .param("id", accountId)
                .update();
        String loginId =
                jdbc.sql("select login_id from account where id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .single();
        Session session = session();
        exchange(
                session,
                "POST",
                "/auth/login",
                Map.of("loginId", loginId, "password", password),
                false,
                200);
        exchange(session, "GET", "/auth/me", null, false, 200);
        assertThat(session.cookies().getCookieStore().getCookies())
                .anyMatch(cookie -> "XSRF-TOKEN".equals(cookie.getName()));
        return session;
    }

    private Session session() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(TLS_STORE)) {
            trustStore.load(input, TLS_PASSWORD.toCharArray());
        }
        TrustManagerFactory trust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        return new Session(
                HttpClient.newBuilder()
                        .sslContext(tls)
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                cookies);
    }

    private JsonNode exchange(
            Session session, String method, String path, Object body, boolean csrf, int expected)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/api/v1" + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json");
        if (csrf) {
            String token =
                    session.cookies().getCookieStore().getCookies().stream()
                            .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getValue();
            request.header("X-XSRF-TOKEN", URLDecoder.decode(token, StandardCharsets.UTF_8));
        }
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response =
                session.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private record Session(HttpClient client, CookieManager cookies) {}
}

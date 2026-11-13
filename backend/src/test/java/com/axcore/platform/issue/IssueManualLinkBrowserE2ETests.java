package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.service.CheckRunJob;
import com.axcore.platform.source.service.CdcReloadJob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 239 수동 브라우저 E2E 전용 임시 API·DB. AXCORE_ISSUE_BROWSER_E2E=true일 때만 실행한다. 브라우저 조작은 외부에서 하고, 이 테스트는 합성
 * fixture와 실제 검사 실행기만 제공한다. build/issue239-e2e/done 파일로 종료하면 최종 DB 상태와 검사 집계를 검증한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.main.web-application-type=servlet",
            "spring.main.keep-alive=false",
            "spring.ai.model.chat=none",
            "axcore.check.sync.enabled=false",
            "axcore.ai.base-url=",
            "axcore.action-notify.webhook-url="
        })
@ActiveProfiles({"api", "batch"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "AXCORE_ISSUE_BROWSER_E2E", matches = "true")
class IssueManualLinkBrowserE2ETests {
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;
    @LocalServerPort int port;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void servesSyntheticScenarioUntilBrowserFinishes() throws Exception {
        Path directory = Path.of("build", "issue239-e2e").toAbsolutePath();
        Files.createDirectories(directory);
        for (String file : new String[] {"ready.json", "state.json", "done", "abort"}) {
            Files.deleteIfExists(directory.resolve(file));
        }
        try {
            runScenario(directory);
        } finally {
            // 로컬 합성 계정의 자격 증명도 실행 종료 후 남기지 않는다.
            Files.deleteIfExists(directory.resolve("ready.json"));
        }
    }

    private void runScenario(Path directory) throws Exception {
        var fixture = new CheckRunTestFixtures(jdbc);
        String password = UUID.randomUUID() + "!A1";
        long adminId = fixture.insertAdmin();
        String login = prepareAccount(adminId, "ADMIN", password);
        String viewer = prepareAccount(fixture.insertAdmin(), "VIEWER", password);
        long sourceType = fixture.insertObjectType("E2E239_WO");
        long targetType = fixture.insertObjectType("E2E239_EQ");
        jdbc.sql(
                        "update object_type set access_group = 'PRODUCTION' where id in (:source, :target)")
                .param("source", sourceType)
                .param("target", targetType)
                .update();
        long sourceProperty = fixture.insertProperty(sourceType, "eq_code");
        long targetProperty = fixture.insertProperty(targetType, "code");
        jdbc.sql("update object_type set display_property_id = :property where id = :type")
                .param("property", targetProperty)
                .param("type", targetType)
                .update();
        long source = fixture.insertDataSource();
        long woResource = fixture.insertResource(source, "mes.work_order");
        long eqResource = fixture.insertResource(source, "mes.equipment");
        long woMapping = fixture.insertObjectMapping(woResource, sourceType, true);
        long eqMapping = fixture.insertObjectMapping(eqResource, targetType, true);
        long woSync = fixture.insertSync(source, woResource, adminId, "SUCCEEDED", Instant.now());
        long eqSync = fixture.insertSync(source, eqResource, adminId, "SUCCEEDED", Instant.now());
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
        fixture.insertObject(woMapping, sourceType, "E2E239_WO1", "{\"eq_code\":\"E1\"}", "ACTIVE");
        long object =
                fixture.insertObject(
                        woMapping, sourceType, "E2E239_WO2", "{\"eq_code\":\"E2\"}", "ACTIVE");
        fixture.insertObject(eqMapping, targetType, "E2E239_EQ1", "{\"code\":\"E1\"}", "ACTIVE");
        long target =
                fixture.insertObject(
                        eqMapping, targetType, "E2E239_EQ3", "{\"code\":\"E3\"}", "ACTIVE");
        long baseline =
                fixture.insertQueuedRun(
                        "MANUAL",
                        "{\"kind\":\"LINK_MAPPINGS\",\"linkMappingIds\":[%d]}".formatted(rule),
                        "{\"resources\":[{\"sourceResourceId\":%d,\"lastSuccessfulSyncRunId\":%d},{\"sourceResourceId\":%d,\"lastSuccessfulSyncRunId\":%d}]}"
                                .formatted(woResource, woSync, eqResource, eqSync),
                        adminId);
        assertThat(runner.runNext(job)).isTrue();
        long issue =
                new IssueTestFixtures(jdbc)
                        .insertIssue(baseline, rule, "UNMATCHED", "CHECKING", "{}");
        jdbc.sql("update issue set object_instance_id = :object where id = :issue")
                .param("object", object)
                .param("issue", issue)
                .update();
        Files.writeString(
                directory.resolve("ready.json"),
                json.writeValueAsString(
                        Map.of(
                                "backendPort",
                                port,
                                "loginId",
                                login,
                                "password",
                                password,
                                "viewerLoginId",
                                viewer,
                                "issueId",
                                issue,
                                "sourceObjectId",
                                object,
                                "targetObjectId",
                                target,
                                "targetTypeName",
                                fixture.typeCode(targetType),
                                "baselineRunId",
                                baseline,
                                "ruleId",
                                rule)));

        while (!Files.exists(directory.resolve("done"))) {
            if (Files.exists(directory.resolve("abort")))
                throw new IllegalStateException("브라우저 검증 중단");
            runner.runNext(job);
            Files.writeString(
                    directory.resolve("state.json"),
                    json.writeValueAsString(
                            jdbc.sql(
                                            "select status, row_version, resolved_check_run_id from issue where id = :id")
                                    .param("id", issue)
                                    .query()
                                    .singleRow()));
            Thread.sleep(500);
        }
        assertThat(
                        jdbc.sql("select status from issue where id = :id")
                                .param("id", issue)
                                .query(String.class)
                                .single())
                .isEqualTo("RESOLVED");
        assertThat(
                        jdbc.sql(
                                        "select count(*) from object_link where source_object_id = :source and target_object_id = :target and origin = 'MANUAL' and status = 'ACTIVE'")
                                .param("source", object)
                                .param("target", target)
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
        assertThat(
                        jdbc.sql(
                                        "select matched_count, unmatched_count, sample_rows::text from check_result where check_run_id = (select resolved_check_run_id from issue where id = :id) and link_mapping_id = :rule")
                                .param("id", issue)
                                .param("rule", rule)
                                .query()
                                .singleRow())
                .containsEntry("matched_count", 2)
                .containsEntry("unmatched_count", 0)
                .containsEntry("sample_rows", "[]");
    }

    private String prepareAccount(long id, String role, String password) {
        jdbc.sql(
                        "update account set password_hash = :hash, role_id = (select id from role where code = :role) where id = :id")
                .param("hash", encoder.encode(password))
                .param("role", role)
                .param("id", id)
                .update();
        return jdbc.sql("select login_id from account where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}

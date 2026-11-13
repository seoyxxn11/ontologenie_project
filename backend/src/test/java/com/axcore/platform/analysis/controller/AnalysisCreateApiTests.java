package com.axcore.platform.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "axcore.internal-api.token=analysis-create-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AnalysisCreateApiTests {
    private static final String PATH = "/api/v1/analyses";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean AuditRecorder audits;
    long accountId;
    long first;
    long second;

    @BeforeEach
    void fixtures() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '분석 관리자', 'test-hash', (select id from role where code='ADMIN'), false) returning id
                """)
                        .param("login", "create-analysis-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '원천', 'POSTGRES') returning id")
                        .param("code", "create-source-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        first = resource(source, "public.first");
        second = resource(source, "public.second");
        for (long id : List.of(first, second)) {
            jdbc.sql(
                            """
                    insert into source_column(source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                    values (:id, 'code', 'text', false, 1), (:id, 'name', 'text', true, 2)
                    """)
                    .param("id", id)
                    .update();
        }
    }

    @Test
    void acceptsSelectedColumnsAndAllColumnsAndRecordsReception() throws Exception {
        String body =
                """
                {"runKind":"INITIAL","targets":[{"sourceResourceId":%d,"columns":["name","code"]},{"sourceResourceId":%d}],"analysisRange":{"sampleSize":100}}
                """
                        .formatted(first, second);
        var response =
                send(body)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.runKind").value("INITIAL"))
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.joined").value(false))
                        .andExpect(jsonPath("$.data.requestedBy").value(accountId))
                        .andExpect(jsonPath("$.data.targets.length()").value(2))
                        .andExpect(jsonPath("$.data.analysisRange.sampleSize").value(100))
                        .andExpect(
                                jsonPath("$.data.analysisRange.columns['" + first + "'][0]")
                                        .value("code"))
                        .andExpect(
                                jsonPath("$.data.analysisRange.columns['" + second + "']")
                                        .doesNotExist())
                        .andExpect(jsonPath("$.data.requestedAt").isNotEmpty())
                        .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                        .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                        .andExpect(jsonPath("$.data.resultSummary").isEmpty())
                        .andReturn();
        long id =
                json.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("id")
                        .asLong();
        assertThat(count("analysis_run_target", "analysis_run_id=" + id)).isEqualTo(2);
        assertThat(
                        count(
                                "action_execution",
                                "target_type='analysis_run' and target_id="
                                        + id
                                        + " and event_kind='ANALYSIS_RUN' and actor_type='USER' and actor_id="
                                        + accountId
                                        + " and entry_point='WEB' and status='SUCCEEDED'"))
                .isEqualTo(1);
    }

    @Test
    void canonicalizesOrderAndJoinsQueuedAndRunningWithoutReplacingOriginalRequest()
            throws Exception {
        long id =
                data(send(twoTargets(false)).andExpect(status().isAccepted())).path("id").asLong();
        send(twoTargets(true))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.joined").value(true));
        notify(id, "{\"status\":\"RUNNING\"}").andExpect(status().isOk());
        send(twoTargets(true))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.joined").value(true))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());
        assertThat(count("analysis_run", "requested_by=" + accountId)).isEqualTo(1);
        assertThat(count("analysis_run_target", "analysis_run_id=" + id)).isEqualTo(2);
        assertThat(
                        count(
                                "action_execution",
                                "target_type='analysis_run' and target_id="
                                        + id
                                        + " and entry_point='WEB'"))
                .isEqualTo(3);
    }

    @Test
    void differentColumnSelectionCreatesAnotherRun() throws Exception {
        long selected = data(send(oneTarget("INITIAL", "[\"code\"]"))).path("id").asLong();
        long all = data(send(oneTarget("INITIAL", null))).path("id").asLong();
        assertThat(all).isNotEqualTo(selected);
    }

    @Test
    void terminalRunAllowsNewReanalysisAndKeepsRangeWithoutChangeSequence() throws Exception {
        String initial = oneTarget("INITIAL", null);
        long previous = data(send(initial)).path("id").asLong();
        notify(previous, "{\"status\":\"FAILED\",\"errorMessage\":\"시험 실패\"}")
                .andExpect(status().isOk());
        long sync =
                jdbc.sql(
                                """
                insert into sync_run(data_source_id, trigger_kind, status, requested_by, started_at, finished_at)
                select data_source_id, 'MANUAL', 'SUCCEEDED', :account, now(), now() from source_resource where id=:resource returning id
                """)
                        .param("account", accountId)
                        .param("resource", first)
                        .query(Long.class)
                        .single();
        var next =
                data(
                        send("{\"runKind\":\"REANALYSIS\",\"targets\":[{\"sourceResourceId\":"
                                        + first
                                        + "}],\"basedOnSyncRunId\":"
                                        + sync
                                        + "}")
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.runKind").value("REANALYSIS"))
                                .andExpect(jsonPath("$.data.basedOnSyncRunId").value(sync))
                                .andExpect(jsonPath("$.data.joined").value(false)));
        assertThat(next.path("id").asLong()).isNotEqualTo(previous);
        assertThat(next.path("analysisRange").isObject()).isTrue();
    }

    @Test
    void concurrentRequestsCreateOnlyOneRunAndOneTargetSet() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return data(
                                        send(twoTargets(false)).andExpect(status().isAccepted()));
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return data(
                                        send(twoTargets(true)).andExpect(status().isAccepted()));
                            });
            start.countDown();
            var one = a.get(15, TimeUnit.SECONDS);
            var two = b.get(15, TimeUnit.SECONDS);
            assertThat(one.path("id").asLong()).isEqualTo(two.path("id").asLong());
            assertThat(List.of(one.path("joined").asBoolean(), two.path("joined").asBoolean()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(count("analysis_run_target", "analysis_run_id=" + one.path("id").asLong()))
                    .isEqualTo(2);
        }
        assertThat(count("analysis_run", "requested_by=" + accountId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"runKind\":\"INITIAL\",\"targets\":[]}",
                "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d},{\"sourceResourceId\":%d}]}",
                "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d,\"columns\":[]}]}",
                "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d,\"columns\":[\"missing_column\"]}]}",
                "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d}],\"analysisRange\":{\"columns\":{}}}",
                "{\"runKind\":\"REANALYSIS\",\"targets\":[{\"sourceResourceId\":%d,\"changeSeqFrom\":1,\"changeSeqTo\":2}]}"
            })
    void rejectsInvalidScopeWithoutCreatingRun(String template) throws Exception {
        send(template.formatted(first, first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();
    }

    @Test
    void rejectsColumnNoLongerPresentInSource() throws Exception {
        jdbc.sql(
                        """
                insert into source_column(source_resource_id, column_name, source_data_type, is_nullable, ordinal_position, is_present)
                values (:id, 'gone', 'text', true, 3, false)
                """)
                .param("id", first)
                .update();
        send("{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d,\"columns\":[\"gone\"]}]}"
                        .formatted(first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();
    }

    /** 원천 데이터 정책(399): 금지 표와, 금지·민감 컬럼을 빼면 남는 컬럼이 없는 대상은 접수하지 않는다. */
    @Test
    void rejectsForbiddenTableAndTargetsWithoutAnalyzableColumns() throws Exception {
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '원천', 'POSTGRES') returning id")
                        .param("code", "policy-source-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long attendance = resource(source, "erp.attendance");
        long worker = resource(source, "mes.worker");
        jdbc.sql(
                        """
                insert into source_column(source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                values (:worker, 'name', 'text', true, 1), (:worker, 'skill_level', 'text', true, 2),
                       (:worker, 'worker_code', 'text', false, 3)
                """)
                .param("worker", worker)
                .update();

        send("{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d},{\"sourceResourceId\":%d}]}"
                        .formatted(first, attendance))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.resourceKey").value("erp.attendance"))
                .andExpect(jsonPath("$.error.details.forbiddenColumns[0]").value("*"));
        send("{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d,\"columns\":[\"name\",\"skill_level\"]}]}"
                        .formatted(worker))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.reason").value("NO_ANALYZABLE_COLUMNS"));
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();

        // 민감 컬럼을 골라도 일반 컬럼이 남으면 접수한다. 빼는 일은 AI 시작 알림이 한다.
        send("{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d,\"columns\":[\"skill_level\",\"worker_code\"]}]}"
                        .formatted(worker))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsMissingResourceAndSyncReference() throws Exception {
        send("{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":9223372036854775807}]}")
                .andExpect(status().isNotFound());
        send("{\"runKind\":\"REANALYSIS\",\"targets\":[{\"sourceResourceId\":"
                        + first
                        + "}],\"basedOnSyncRunId\":9223372036854775807}")
                .andExpect(status().isNotFound());
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();
    }

    @Test
    void requiresSessionAdminAndCsrf() throws Exception {
        String body = oneTarget("INITIAL", null);
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH)
                                .with(authentication(account("VIEWER").toAuthentication()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(PATH)
                                .with(authentication(account("ADMIN").toAuthentication()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();
    }

    @Test
    void auditFailureRollsBackNewRunAndTargets() throws Exception {
        long before = count("analysis_run_target", "true");
        doThrow(new IllegalStateException("접수 감사 기록 실패 테스트")).when(audits).record(any());
        send(twoTargets(false)).andExpect(status().isInternalServerError());
        assertThat(count("analysis_run", "requested_by=" + accountId)).isZero();
        assertThat(count("analysis_run_target", "true")).isEqualTo(before);
    }

    private LoginAccount account(String role) {
        return new LoginAccount(accountId, "analysis-test", role, false);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(
                post(PATH)
                        .with(authentication(account("ADMIN").toAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions notify(long id, String body) throws Exception {
        return mvc.perform(
                post("/api/v1/internal/analyses/{id}/results", id)
                        .header("X-Internal-Token", "analysis-create-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String oneTarget(String kind, String columns) {
        return "{\"runKind\":\""
                + kind
                + "\",\"targets\":[{\"sourceResourceId\":"
                + first
                + (columns == null ? "" : ",\"columns\":" + columns)
                + "}]}";
    }

    private String twoTargets(boolean reversed) {
        return reversed
                ? "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":"
                        + second
                        + "},{\"sourceResourceId\":"
                        + first
                        + ",\"columns\":[\"code\",\"name\"]}]}"
                : "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":"
                        + first
                        + ",\"columns\":[\"name\",\"code\"]},{\"sourceResourceId\":"
                        + second
                        + "}]}";
    }

    private long resource(long source, String key) {
        return jdbc.sql(
                        "insert into source_resource(data_source_id, resource_key, resource_kind) values (:source, :key, 'TABLE') returning id")
                .param("source", source)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long count(String table, String condition) {
        return jdbc.sql("select count(*) from " + table + " where " + condition)
                .query(Long.class)
                .single();
    }
}

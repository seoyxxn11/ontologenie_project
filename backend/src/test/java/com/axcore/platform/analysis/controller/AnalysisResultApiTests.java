package com.axcore.platform.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.service.AuditRecorder;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "axcore.internal-api.token=analysis-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AnalysisResultApiTests {
    private static final String PATH = "/api/v1/internal/analyses/{id}/results";
    private static final String SUCCESS =
            """
            {"status":"SUCCEEDED","proposals":[
              {"itemKind":"OBJECT_TYPE","payload":{"code":"NEW_TYPE"},
               "evidence":{"reason":"검토용 근거"},"aiConfidence":0.92,
               "options":[{"optionNo":3,"payload":{"code":"OTHER_TYPE"},"aiConfidence":0.42}]}
            ]}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean AuditRecorder audits;
    long accountId;

    @BeforeEach
    void account() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '분석 테스트', 'test-hash', (select id from role where code='ADMIN'), false)
                returning id
                """)
                        .param("login", "analysis-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void startsQueuedAnalysisWithoutCsrfOrSession() throws Exception {
        long id = run("QUEUED");
        send(id, "{\"status\":\"RUNNING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisRunId").value(id))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.proposalItemIds").isEmpty());
        assertThat(state(id)).isEqualTo("RUNNING");
        assertThat(count("action_execution", "target_type='analysis_run' and target_id=" + id))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING"})
    void recordsAnalysisFailureAndInternalAudit(String initial) throws Exception {
        long id = run(initial);
        jdbc.sql("update analysis_run set result_summary='{\"retained\":true}'::jsonb where id=:id")
                .param("id", id)
                .update();
        send(id, "{\"status\":\"FAILED\",\"errorMessage\":\"분석 중 오류가 났습니다\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.proposalItemIds").isEmpty());
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(
                        count(
                                "analysis_run",
                                "id=" + id + " and result_summary='{\"retained\":true}'::jsonb"))
                .isEqualTo(1);
        assertThat(
                        jdbc.sql("select error_message from analysis_run where id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo("분석 중 오류가 났습니다");
        assertThat(
                        count(
                                "action_execution",
                                "target_type='analysis_run' and target_id="
                                        + id
                                        + " and event_kind='ANALYSIS_RUN' and actor_type='SYSTEM' and actor_id is null"
                                        + " and entry_point='INTERNAL' and status='FAILED' and result='{\"proposalCount\":0}'::jsonb"))
                .isEqualTo(1);
        if (initial.equals("QUEUED")) {
            assertThat(count("analysis_run", "id=" + id + " and started_at is null")).isEqualTo(1);
        }
    }

    @Test
    void storesAllCandidatePartsAndAutomaticCheck() throws Exception {
        long id = run("RUNNING");
        var response =
                send(id, SUCCESS)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.proposalItemIds.length()").value(1))
                        .andReturn();
        long proposal =
                json.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("proposalItemIds")
                        .get(0)
                        .asLong();
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThat(
                        count(
                                "proposal_item",
                                "id="
                                        + proposal
                                        + " and analysis_run_id="
                                        + id
                                        + " and origin='AI' and revision=1 and review_status='PENDING'"
                                        + " and apply_status='NOT_APPLIED' and created_by is null and updated_by is null"
                                        + " and ai_confidence=0.92 and evidence->>'reason'='검토용 근거'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "proposal_revision",
                                "proposal_item_id="
                                        + proposal
                                        + " and revision=1 and ai_confidence=0.92 and created_by is null"
                                        + " and payload->>'code'='NEW_TYPE'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "proposal_option",
                                "proposal_item_id="
                                        + proposal
                                        + " and revision=1 and option_no=3 and ai_confidence=0.42 and evidence='{}'::jsonb"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "check_run",
                                "trigger_kind='CANDIDATE' and status='QUEUED' and requested_by is null"
                                        + " and scope->'proposalItems' @> '[{\"proposalItemId\":"
                                        + proposal
                                        + ",\"revision\":1}]'::jsonb"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "analysis_run",
                                "id=" + id + " and result_summary=' {\"proposalCount\":1}'::jsonb"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "action_execution",
                                "target_type='analysis_run' and target_id="
                                        + id
                                        + " and status='SUCCEEDED' and result='{\"proposalCount\":1}'::jsonb"))
                .isEqualTo(1);
    }

    @Test
    void acceptsEmptySuccessAndPreservesSuppliedSummary() throws Exception {
        long id = run("RUNNING");
        send(id, "{\"status\":\"SUCCEEDED\",\"resultSummary\":{\"llmUsed\":false}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalItemIds").isEmpty());
        assertThat(
                        count(
                                "analysis_run",
                                "id=" + id + " and result_summary='{\"llmUsed\":false}'::jsonb"))
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicateCompletionWithoutAddingCandidatesOrAudits() throws Exception {
        long id = run("RUNNING");
        send(id, SUCCESS).andExpect(status().isOk());
        send(id, SUCCESS).andExpect(status().isConflict());
        assertThat(count("proposal_item", "analysis_run_id=" + id)).isEqualTo(1);
        assertThat(count("action_execution", "target_type='analysis_run' and target_id=" + id))
                .isEqualTo(1);
    }

    @Test
    void serializesConcurrentCompletion() throws Exception {
        long id = run("RUNNING");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return send(id, SUCCESS).andReturn().getResponse().getStatus();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return send(id, SUCCESS).andReturn().getResponse().getStatus();
                            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("proposal_item", "analysis_run_id=" + id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void rejectsUpdatesToTerminalRun(String initial) throws Exception {
        long id = run(initial);
        send(id, "{\"status\":\"RUNNING\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("허용되지 않는 상태 전이입니다."))
                .andExpect(jsonPath("$.error.details.currentStatus").value(initial))
                .andExpect(jsonPath("$.error.details.requestedStatus").value("RUNNING"))
                .andExpect(jsonPath("$.error.details.analysisRunId").doesNotExist());
        assertThat(state(id)).isEqualTo(initial);
    }

    @Test
    void rejectsRepeatedStartAndSuccessBeforeStart() throws Exception {
        long running = run("RUNNING");
        send(running, "{\"status\":\"RUNNING\"}").andExpect(status().isConflict());
        long queued = run("QUEUED");
        send(queued, SUCCESS).andExpect(status().isConflict());
        assertThat(state(queued)).isEqualTo("QUEUED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"status\":\"QUEUED\"}",
                "{\"status\":\"FAILED\"}",
                "{\"status\":\"RUNNING\",\"proposals\":[]}",
                "{\"status\":\"SUCCEEDED\",\"errorMessage\":\"wrong\"}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"OTHER\",\"payload\":{}}]}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"PROPERTY\",\"payload\":[]}]}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"PROPERTY\",\"payload\":{},\"aiConfidence\":1.01}]}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"PROPERTY\",\"payload\":{},\"options\":[{\"optionNo\":0,\"payload\":{}}]}]}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"PROPERTY\",\"payload\":{},\"options\":[{\"optionNo\":1.9,\"payload\":{}}]}]}",
                "{\"status\":\"SUCCEEDED\",\"proposals\":[{\"itemKind\":\"PROPERTY\",\"payload\":{},\"options\":[{\"optionNo\":1,\"payload\":{}},{\"optionNo\":1,\"payload\":{}}]}]}"
            })
    void rejectsInvalidInputWithoutChangingData(String body) throws Exception {
        long id = run("RUNNING");
        send(id, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(state(id)).isEqualTo("RUNNING");
        assertThat(count("proposal_item", "analysis_run_id=" + id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "SUCCEEDED"})
    void rollsBackStateCandidatesAndChecksWhenAuditFails(String next) throws Exception {
        long id = run("RUNNING");
        long checksBefore = count("check_run", "true");
        doThrow(new IllegalStateException("감사 저장 실패 테스트")).when(audits).record(any());
        String body =
                next.equals("SUCCEEDED")
                        ? SUCCESS
                        : "{\"status\":\"FAILED\",\"errorMessage\":\"분석 실패\"}";
        send(id, body).andExpect(status().isInternalServerError());
        assertThat(state(id)).isEqualTo("RUNNING");
        assertThat(count("proposal_item", "analysis_run_id=" + id)).isZero();
        assertThat(count("check_run", "true")).isEqualTo(checksBefore);
    }

    @Test
    void rejectsMissingRunAndUnauthenticatedCall() throws Exception {
        send(Long.MAX_VALUE, "{\"status\":\"RUNNING\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("analysis_run"));
        long id = run("QUEUED");
        mvc.perform(
                        post(PATH, id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(state(id)).isEqualTo("QUEUED");
    }

    @Test
    void acceptsAllSevenKindsAndKeepsReturnedIdsInInputOrder() throws Exception {
        long id = run("RUNNING");
        String body =
                """
                {"status":"SUCCEEDED","proposals":[
                  {"itemKind":"OBJECT_TYPE","payload":{}},
                  {"itemKind":"PROPERTY","payload":{},"aiConfidence":0},
                  {"itemKind":"LINK_TYPE","payload":{},"aiConfidence":1},
                  {"itemKind":"OBJECT_MAPPING","payload":{}},
                  {"itemKind":"PROPERTY_MAPPING","payload":{}},
                  {"itemKind":"LINK_RULE","payload":{"linkTypeCode":"NOT_APPROVED_YET"}},
                  {"itemKind":"DOCUMENT_LINK","payload":{}}
                ]}
                """;
        var response = send(id, body).andExpect(status().isOk()).andReturn();
        var ids =
                json.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("proposalItemIds");
        assertThat(ids.size()).isEqualTo(7);
        var kinds =
                List.of(
                        "OBJECT_TYPE",
                        "PROPERTY",
                        "LINK_TYPE",
                        "OBJECT_MAPPING",
                        "PROPERTY_MAPPING",
                        "LINK_RULE",
                        "DOCUMENT_LINK");
        for (int i = 0; i < kinds.size(); i++) {
            assertThat(
                            count(
                                    "proposal_item",
                                    "id="
                                            + ids.get(i).asLong()
                                            + " and item_kind='"
                                            + kinds.get(i)
                                            + "'"))
                    .isEqualTo(1);
        }
        assertThat(
                        count(
                                "proposal_revision",
                                "proposal_item_id="
                                        + ids.get(0).asLong()
                                        + " and evidence='{}'::jsonb and ai_confidence is null"))
                .isEqualTo(1);
    }

    @Test
    void includesMappingsLaterInTheBatchInEveryCandidateCheck() throws Exception {
        long id = run("RUNNING");
        var resource = resource();
        send(
                        id,
                        """
                {"status":"SUCCEEDED","proposals":[
                  {"itemKind":"OBJECT_TYPE","payload":{"code":"BATCH_NEW_TYPE"}},
                  {"itemKind":"OBJECT_MAPPING","payload":{"objectTypeCode":"BATCH_NEW_TYPE","sourceId":"%s","resourceKey":"public.test"}}
                ]}
                """
                                .formatted(resource.code()))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.sql(
                                        """
                select count(*) from check_run where
                    scope#>>'{proposalItems,0,proposalItemId}' in (select id::text from proposal_item where analysis_run_id=:id)
                    and data_basis->'resources' @> cast(:resource as jsonb)
                """)
                                .param("id", id)
                                .param("resource", "[{\"sourceResourceId\":" + resource.id() + "}]")
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void includesExistingObjectMappingsInAutomaticCheckBasis() throws Exception {
        long id = run("RUNNING");
        var resource = resource();
        String code = "EXISTING_" + UUID.randomUUID().toString().replace("-", "");
        long type =
                jdbc.sql("insert into object_type(code, name) values (:code, '기존 유형') returning id")
                        .param("code", code)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "insert into object_source_mapping(source_resource_id, object_type_id, key_columns) values (:resource, :type, '[\"id\"]'::jsonb)")
                .param("resource", resource.id())
                .param("type", type)
                .update();
        var response =
                send(
                                id,
                                """
                                {"status":"SUCCEEDED","proposals":[
                                  {"itemKind":"PROPERTY","payload":{"objectTypeCode":"%s","code":"new_property"}},
                                  {"itemKind":"LINK_TYPE","payload":{"code":"NEW_LINK","sourceTypeCode":"%s","targetTypeCode":"%s"}},
                                  {"itemKind":"LINK_RULE","payload":{"linkTypeCode":"NEW_LINK"}}
                                ]}
                """
                                        .formatted(code, code, code))
                        .andExpect(status().isOk())
                        .andReturn();
        long proposal =
                json.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("proposalItemIds")
                        .get(0)
                        .asLong();
        assertThat(
                        jdbc.sql(
                                        """
                select count(*) from check_run where scope#>>'{proposalItems,0,proposalItemId}'=:proposal
                    and data_basis->'resources' @> cast(:resource as jsonb)
                """)
                                .param("proposal", Long.toString(proposal))
                                .param("resource", "[{\"sourceResourceId\":" + resource.id() + "}]")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        """
                select count(*) from check_run where
                    scope#>>'{proposalItems,0,proposalItemId}' in (select id::text from proposal_item where analysis_run_id=:id)
                    and data_basis->'resources' @> cast(:resource as jsonb)
                """)
                                .param("id", id)
                                .param("resource", "[{\"sourceResourceId\":" + resource.id() + "}]")
                                .query(Long.class)
                                .single())
                .isEqualTo(3);
    }

    private Resource resource() {
        String code = "analysis_source_" + UUID.randomUUID();
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '테스트 원천', 'POSTGRES') returning id")
                        .param("code", code)
                        .query(Long.class)
                        .single();
        long resource =
                jdbc.sql(
                                "insert into source_resource(data_source_id, resource_key, resource_kind) values (:source, 'public.test', 'TABLE') returning id")
                        .param("source", source)
                        .query(Long.class)
                        .single();
        return new Resource(resource, code);
    }

    private record Resource(long id, String code) {}

    private ResultActions send(long id, String body) throws Exception {
        return mvc.perform(
                post(PATH, id)
                        .header("X-Internal-Token", "analysis-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long run(String state) {
        return jdbc.sql(
                        """
                insert into analysis_run(run_kind, work_key, requested_by, status, started_at, finished_at, error_message)
                values ('INITIAL', :key, :account, :state,
                    case when :state='QUEUED' then null else now() end,
                    case when :state in ('SUCCEEDED','FAILED') then now() else null end,
                    case when :state='FAILED' then '기존 실패' else null end) returning id
                """)
                .param("key", "analysis-result-" + UUID.randomUUID())
                .param("account", accountId)
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private String state(long id) {
        return jdbc.sql("select status from analysis_run where id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private long count(String table, String condition) {
        return jdbc.sql("select count(*) from " + table + " where " + condition)
                .query(Long.class)
                .single();
    }
}

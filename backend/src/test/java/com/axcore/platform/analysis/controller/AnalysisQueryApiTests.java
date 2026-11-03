package com.axcore.platform.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "axcore.internal-api.token=analysis-query-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class AnalysisQueryApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    long accountId;

    @BeforeEach
    void account() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '조회 관리자', 'test-hash', (select id from role where code='ADMIN'), false) returning id
                """)
                        .param("login", "query-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsReceptionAndResultThroughDetailAndExistingReviewResponse() throws Exception {
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '조회 원천', 'POSTGRES') returning id")
                        .param("code", "query-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long resource =
                jdbc.sql(
                                "insert into source_resource(data_source_id, resource_kind, resource_key) values (:source, 'TABLE', 'public.customer') returning id")
                        .param("source", source)
                        .query(Long.class)
                        .single();
        long id =
                data(mvc.perform(
                                        post("/api/v1/analyses")
                                                .with(admin())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d}],\"analysisRange\":{\"sampleSize\":100}}"
                                                                .formatted(resource)))
                                .andExpect(status().isAccepted()))
                        .path("id")
                        .asLong();
        read("/api/v1/analyses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.targets[0].sourceResourceId").value(resource))
                .andExpect(jsonPath("$.data.analysisRange.sampleSize").value(100))
                .andExpect(jsonPath("$.data.requestedBy").value(accountId))
                .andExpect(jsonPath("$.data.requestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.basedOnSyncRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.joined").doesNotExist());
        notify(id, "{\"status\":\"RUNNING\"}").andExpect(status().isOk());
        read("/api/v1/analyses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()));
        notify(
                        id,
                        """
                {"status":"SUCCEEDED","proposals":[{"itemKind":"OBJECT_TYPE","payload":{"code":"QUERY_CUSTOMER"},"evidence":{"reason":"식별 정보"},"aiConfidence":0.92}]}
                """)
                .andExpect(status().isOk());
        read("/api/v1/analyses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resultSummary.proposalCount").value(1))
                .andExpect(jsonPath("$.data.finishedAt").isNotEmpty());
        JsonNode proposals =
                data(
                        read("/api/v1/analyses/" + id + "/proposals")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data[0].analysisRunId").value(id))
                                .andExpect(jsonPath("$.data[0].origin").value("AI"))
                                .andExpect(jsonPath("$.data[0].aiConfidence").value(0.92))
                                .andExpect(jsonPath("$.data[0].latestCheck.status").value("QUEUED"))
                                .andExpect(jsonPath("$.meta.totalElements").value(1)));
        long proposalId = proposals.get(0).path("id").asLong();
        assertThat(proposals.get(0))
                .isEqualTo(
                        data(read("/api/v1/proposals/" + proposalId).andExpect(status().isOk())));
    }

    @Test
    void returnsFailedAnalysisAsSuccessfulReadWithReasonAndEmptyCandidates() throws Exception {
        long id = run("INITIAL", "QUEUED");
        notify(id, "{\"status\":\"FAILED\",\"errorMessage\":\"AI 분석 실패\"}")
                .andExpect(status().isOk());
        read("/api/v1/analyses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorMessage").value("AI 분석 실패"))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.finishedAt").isNotEmpty());
        read("/api/v1/analyses/" + id + "/proposals")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void filtersRunKindAndStatusAndPaginatesWithoutDuplicatingTargets() throws Exception {
        long first = run("REANALYSIS", "FAILED");
        long second = run("REANALYSIS", "FAILED");
        run("INITIAL", "FAILED");
        run("REANALYSIS", "QUEUED");
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '목록 원천', 'POSTGRES') returning id")
                        .param("code", "list-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        var resources =
                jdbc.sql(
                                "insert into source_resource(data_source_id, resource_kind, resource_key) values (:source, 'TABLE', 'public.a'), (:source, 'TABLE', 'public.b'), (:source, 'TABLE', 'public.c') returning id")
                        .param("source", source)
                        .query(Long.class)
                        .list();
        jdbc.sql(
                        "insert into analysis_run_target(analysis_run_id, source_resource_id) values (:first, :a), (:first, :b), (:second, :c)")
                .param("first", first)
                .param("second", second)
                .param("a", resources.get(0))
                .param("b", resources.get(1))
                .param("c", resources.get(2))
                .update();
        read("/api/v1/analyses?runKind=REANALYSIS&status=FAILED&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[0].joined").doesNotExist())
                .andExpect(jsonPath("$.data[0].targets.length()").value(1))
                .andExpect(
                        jsonPath("$.data[0].targets[0].sourceResourceId").value(resources.get(2)))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        read("/api/v1/analyses?runKind=REANALYSIS&status=FAILED&size=1&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.data[0].targets.length()").value(2))
                .andExpect(
                        jsonPath("$.data[0].targets[0].sourceResourceId").value(resources.get(0)))
                .andExpect(
                        jsonPath("$.data[0].targets[1].sourceResourceId").value(resources.get(1)))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        read("/api/v1/analyses?runKind=REANALYSIS&status=FAILED&size=1&page=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(2));
        read("/api/v1/analyses")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void restrictsCandidatesToAnalysisAndCombinesFiltersBeforePaging() throws Exception {
        long id = run("INITIAL", "SUCCEEDED");
        long other = run("INITIAL", "SUCCEEDED");
        long first = proposal(id, "OBJECT_TYPE", "HELD", "FAILED");
        long second = proposal(id, "OBJECT_TYPE", "HELD", "FAILED");
        proposal(id, "PROPERTY", "HELD", "FAILED");
        proposal(id, "OBJECT_TYPE", "PENDING", "FAILED");
        proposal(id, "OBJECT_TYPE", "HELD", "NOT_APPLIED");
        proposal(other, "OBJECT_TYPE", "HELD", "FAILED");
        String path =
                "/api/v1/analyses/"
                        + id
                        + "/proposals?itemKind=OBJECT_TYPE&reviewStatus=HELD&applyStatus=FAILED&size=1";
        read(path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[0].applyError").value("반영 실패"))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        read(path + "&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/analyses/9223372036854775807",
                "/api/v1/analyses/9223372036854775807/proposals"
            })
    void rejectsMissingAnalysis(String path) throws Exception {
        read(path)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("analysis_run"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/analyses?runKind=OTHER",
                "/api/v1/analyses?status=DONE",
                "/api/v1/analyses?page=-1",
                "/api/v1/analyses?size=0",
                "/api/v1/analyses/0",
                "/api/v1/analyses/0/proposals",
                "/api/v1/analyses/1/proposals?itemKind=OTHER",
                "/api/v1/analyses/1/proposals?reviewStatus=OTHER",
                "/api/v1/analyses/1/proposals?applyStatus=OTHER",
                "/api/v1/analyses/1/proposals?page=-1",
                "/api/v1/analyses/1/proposals?size=0"
            })
    void rejectsInvalidQueryOrId(String path) throws Exception {
        read(path)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/analyses", "/api/v1/analyses/1", "/api/v1/analyses/1/proposals"})
    void requiresAuthenticatedAdminOnEveryRead(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(path)
                                .with(
                                        authentication(
                                                new LoginAccount(accountId, "reader", "USER", false)
                                                        .toAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
    }

    private long run(String kind, String status) {
        return jdbc.sql(
                        """
                insert into analysis_run(run_kind, work_key, status, requested_by, started_at, finished_at, error_message)
                values (:kind, :key, :status, :account,
                    case when :status='QUEUED' then null else now() end,
                    case when :status in ('SUCCEEDED','FAILED') then now() else null end,
                    case when :status='FAILED' then '테스트 실패' else null end) returning id
                """)
                .param("kind", kind)
                .param("key", UUID.randomUUID().toString())
                .param("status", status)
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    private long proposal(long run, String kind, String review, String apply) {
        long id =
                jdbc.sql(
                                """
                insert into proposal_item(analysis_run_id, origin, item_kind, payload, review_status, apply_status, apply_error)
                values (:run, 'AI', :kind, '{}', :review, :apply,
                    case when :apply='FAILED' then '반영 실패' else null end) returning id
                """)
                        .param("run", run)
                        .param("kind", kind)
                        .param("review", review)
                        .param("apply", apply)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "insert into proposal_revision(proposal_item_id, revision, payload) values (:id, 1, '{}')")
                .param("id", id)
                .update();
        return id;
    }

    private ResultActions read(String path) throws Exception {
        return mvc.perform(get(path).with(admin()));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(
                new LoginAccount(accountId, "admin", "ADMIN", false).toAuthentication());
    }

    private ResultActions notify(long id, String body) throws Exception {
        return mvc.perform(
                post("/api/v1/internal/analyses/{id}/results", id)
                        .header("X-Internal-Token", "analysis-query-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}

package com.axcore.platform.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 테스트 분석 지우기 임시 API(S15P31C106-307). 기능을 없앨 때 이 테스트도 함께 지운다. */
@SpringBootTest(properties = "axcore.internal-api.token=test-cleanup-token")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class TestAnalysisCleanupApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    long accountId;
    long resource;

    @BeforeEach
    void fixtures() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '정리 관리자', 'test-hash', (select id from role where code='ADMIN'), false) returning id
                """)
                        .param("login", "cleanup-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '정리 원천', 'POSTGRES') returning id")
                        .param("code", "cleanup-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        resource =
                jdbc.sql(
                                "insert into source_resource(data_source_id, resource_kind, resource_key) values (:source, 'TABLE', 'public.work_order') returning id")
                        .param("source", source)
                        .query(Long.class)
                        .single();
    }

    @Test
    void deletesAllFinishedTestAnalysesAndSkipsUsedOrRunningOnes() throws Exception {
        long free = succeededRun();
        // 거절 결정만 있고 반영되지 않은 테스트 제안은 결정과 함께 지운다
        long decided = succeededRun();
        jdbc.sql(
                        """
                insert into review_decision(proposal_item_id, item_revision, decision, reason, decided_by)
                select min(id), 1, 'REJECT', '테스트 거절', :account from proposal_item where analysis_run_id=:run
                """)
                .param("account", accountId)
                .param("run", decided)
                .update();
        long queued = created();
        long used = succeededRun();
        long proposal =
                jdbc.sql("select min(id) from proposal_item where analysis_run_id=:run")
                        .param("run", used)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "insert into object_type(code, name, origin, proposal_item_id) values (:code, '쓰인 객체', 'AI_PROPOSAL', :proposal)")
                .param("code", "USED_" + UUID.randomUUID().toString().substring(0, 8))
                .param("proposal", proposal)
                .update();
        long running = succeededRun();
        jdbc.sql(
                        """
                update check_run set status='RUNNING', started_at=now() where id = (
                    select min(c.id) from check_run c, jsonb_array_elements(c.scope->'proposalItems') e
                    where (e->>'proposalItemId')::bigint in (select id from proposal_item where analysis_run_id=:run))
                """)
                .param("run", running)
                .update();
        assertThat(candidateChecks(free)).isEqualTo(2);

        String body =
                mvc.perform(delete("/api/v1/dev/test-analyses").with(admin()).with(csrf()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var data = json.readTree(body).path("data");
        assertThat(data.path("deletedRuns").toString())
                .contains(String.valueOf(free))
                .contains(String.valueOf(decided));
        assertThat(
                        count(
                                "review_decision where proposal_item_id in (select id from proposal_item where analysis_run_id="
                                        + decided
                                        + ")"))
                .isZero();
        assertThat(count("analysis_run where id=" + decided)).isZero();
        assertThat(data.path("skippedRuns").toString())
                .contains(String.valueOf(used))
                .contains(String.valueOf(running));

        assertThat(count("analysis_run where id=" + free)).isZero();
        assertThat(count("proposal_item where analysis_run_id=" + free)).isZero();
        assertThat(candidateChecks(free)).isZero();
        assertThat(count("analysis_run_target where analysis_run_id=" + free)).isZero();
        // 끝나지 않은 분석, 쓰인 제안이 있는 분석, 검사가 실행 중인 분석은 그대로
        assertThat(count("analysis_run where id=" + queued)).isEqualTo(1);
        assertThat(count("proposal_item where analysis_run_id=" + used)).isEqualTo(2);
        assertThat(count("proposal_item where analysis_run_id=" + running)).isEqualTo(2);
        // 개정본 수정·삭제 금지 트리거는 다시 켜져 있다
        assertThat(
                        jdbc.sql(
                                        "select string_agg(tgenabled::text, '') from pg_trigger where tgname in ('trg_proposal_revision_immutable','trg_proposal_option_immutable')")
                                .query(String.class)
                                .single())
                .isEqualTo("OO");
        assertThat(
                        count(
                                "action_execution where target_type='analysis_run' and target_id="
                                        + free
                                        + " and input->>'action'='TEST_ANALYSIS_DELETE'"))
                .isEqualTo(1);
    }

    @Test
    void requiresAdminAndCsrf() throws Exception {
        long run = succeededRun();
        long viewer =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '조회자', 'test-hash', (select id from role where code='VIEWER'), false) returning id
                """)
                        .param("login", "cleanup-viewer-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        mvc.perform(
                        delete("/api/v1/dev/test-analyses")
                                .with(
                                        authentication(
                                                new LoginAccount(viewer, "viewer", "VIEWER", false)
                                                        .toAuthentication()))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/dev/test-analyses").with(admin()))
                .andExpect(status().isForbidden()); // CSRF 없음
        assertThat(count("proposal_item where analysis_run_id=" + run)).isEqualTo(2);
    }

    private long created() throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/analyses")
                                        .with(admin())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"runKind\":\"INITIAL\",\"targets\":[{\"sourceResourceId\":%d}]}"
                                                        .formatted(resource)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("data").path("id").asLong();
    }

    // 실제 흐름(018 접수 → 105 결과 저장)으로 제안 두 건과 후보 검사 두 건을 만든다.
    private long succeededRun() throws Exception {
        long run = created();
        notify(run, "{\"status\":\"RUNNING\"}").andExpect(status().isOk());
        notify(
                        run,
                        """
                {"status":"SUCCEEDED","proposals":[
                  {"itemKind":"OBJECT_TYPE","payload":{"code":"CLEANUP_WO","properties":[{"code":"wo_no"}]},"evidence":{"reason":"표"},"aiConfidence":0.5},
                  {"itemKind":"LINK_TYPE","payload":{"code":"CLEANUP_LT","sourceTypeCode":"CLEANUP_WO","targetTypeCode":"CLEANUP_WO"},"evidence":{"reason":"관계"},"aiConfidence":0.8}
                ]}
                """)
                .andExpect(status().isOk());
        return run;
    }

    private ResultActions notify(long id, String body) throws Exception {
        return mvc.perform(
                post("/api/v1/internal/analyses/{id}/results", id)
                        .header("X-Internal-Token", "test-cleanup-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long candidateChecks(long run) {
        return jdbc.sql(
                        """
                select count(distinct c.id) from check_run c, jsonb_array_elements(c.scope -> 'proposalItems') e
                where c.trigger_kind = 'CANDIDATE'
                  and (e ->> 'proposalItemId')::bigint in (select id from proposal_item where analysis_run_id = :run)
                """)
                .param("run", run)
                .query(Long.class)
                .single();
    }

    private long count(String from) {
        return jdbc.sql("select count(*) from " + from).query(Long.class).single();
    }

    private RequestPostProcessor admin() {
        return authentication(
                new LoginAccount(accountId, "admin", "ADMIN", false).toAuthentication());
    }
}

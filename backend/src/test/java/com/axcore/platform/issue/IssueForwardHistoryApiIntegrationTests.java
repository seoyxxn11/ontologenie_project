package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.IssueDedupeKey;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.NewIssue;
import com.axcore.platform.issue.service.IssueRegistrationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** API 068 원천 담당자 전달 기록과 069 상태 이력을 실제 PostgreSQL까지 연결해 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueForwardHistoryApiIntegrationTests {

    private static final String FORWARD = "/api/v1/issues/{id}/forward";
    private static final String HISTORY = "/api/v1/issues/{id}/history";
    private static final String STATUS = "/api/v1/issues/{id}/status";
    private static final String EVIDENCE = "{\"source\": \"MES\"}";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired IssueRegistrationService registrationService;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long detectedRunId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        accountId = fixtures.insertAdmin();
        detectedRunId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
    }

    @Test
    void recordsForwardWithoutChangingStatus() throws Exception {
        long issueId = newIssue();
        fixtures.setStatus(issueId, "CHECKING");

        forward(issueId, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(issueId))
                .andExpect(jsonPath("$.data.status").value("CHECKING"))
                .andExpect(jsonPath("$.data.forwardedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.forwardedBy").value(accountId))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        // 전달은 상태 변경이 아니므로 상태 이력을 남기지 않는다.
        assertThat(historyCount(issueId)).isZero();
    }

    @Test
    void keepsLatestForwardWhenSentAgain() throws Exception {
        long issueId = newIssue();
        forward(issueId, 0).andExpect(status().isOk());
        jdbcClient
                .sql("update issue set forwarded_at = :earlier where id = :id")
                .param("earlier", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")))
                .param("id", issueId)
                .update();
        long otherAdminId = fixtures.insertAdmin();

        mockMvc.perform(
                        post(FORWARD, issueId)
                                .with(loggedIn(otherAdminId, "ADMIN"))
                                .with(IssueTestFixtures.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rowVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.forwardedBy").value(otherAdminId))
                .andExpect(jsonPath("$.data.rowVersion").value(2));

        Instant forwardedAt =
                jdbcClient
                        .sql("select forwarded_at from issue where id = :id")
                        .param("id", issueId)
                        .query(Timestamp.class)
                        .single()
                        .toInstant();
        assertThat(forwardedAt).isAfter(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void rejectsStaleRowVersion() throws Exception {
        long issueId = newIssue();
        forward(issueId, 0).andExpect(status().isOk());

        forward(issueId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.field").value("rowVersion"))
                .andExpect(jsonPath("$.error.details.actual").value(1));
    }

    @Test
    void rejectsResolvedIssue() throws Exception {
        long issueId = newIssue();
        fixtures.markFixedAt(issueId, accountId, Instant.parse("2026-11-01T00:00:00Z"));
        fixtures.resolve(issueId, fixtures.insertReverifyRun(detectedRunId, accountId));

        forward(issueId, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ISSUE_ALREADY_RESOLVED"))
                .andExpect(jsonPath("$.error.details.currentStatus").value("RESOLVED"));

        long forwarded =
                jdbcClient
                        .sql(
                                "select count(*) from issue where id = :id and forwarded_by is not null")
                        .param("id", issueId)
                        .query(Long.class)
                        .single();
        assertThat(forwarded).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"rowVersion\":-1}", "{\"rowVersion\":\"x\"}", "not-json"})
    void rejectsInvalidForwardBody(String json) throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        post(FORWARD, issueId)
                                .with(loggedIn(accountId, "ADMIN"))
                                .with(IssueTestFixtures.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForUnknownIssue() throws Exception {
        forward(Long.MAX_VALUE, 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("issue"));
        mockMvc.perform(get(HISTORY, Long.MAX_VALUE).with(loggedIn(accountId, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("issue"));
    }

    @Test
    void listsHistoryInOrderFromDetectionToResolution() throws Exception {
        long issueId = registeredIssue();
        changeStatus(issueId, "CHECKING", "검사 결과를 확인했습니다.", null, 0);
        changeStatus(issueId, "FIXED_PENDING_REVERIFY", "원천 수정 완료 알림", null, 1);
        long reverifyId =
                fixtures.insertRun(
                        "REVERIFY",
                        "SUCCEEDED",
                        Instant.now().plusSeconds(60),
                        detectedRunId,
                        accountId);
        fixtures.insertVerification(issueId, reverifyId, "PASSED");
        changeStatus(issueId, "RESOLVED", "재검증 통과 확인", reverifyId, 2);

        mockMvc.perform(get(HISTORY, issueId).with(loggedIn(accountId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                // 검사가 처음 등록한 행
                .andExpect(jsonPath("$.data[0].issueId").value(issueId))
                .andExpect(jsonPath("$.data[0].beforeStatus").value(nullValue()))
                .andExpect(jsonPath("$.data[0].afterStatus").value("NEW"))
                .andExpect(jsonPath("$.data[0].changedBy").value(nullValue()))
                .andExpect(jsonPath("$.data[0].checkRunId").value(detectedRunId))
                .andExpect(jsonPath("$.data[0].note").value(nullValue()))
                .andExpect(jsonPath("$.data[0].changedAt").isNotEmpty())
                // 사람이 바꾼 행
                .andExpect(jsonPath("$.data[1].beforeStatus").value("NEW"))
                .andExpect(jsonPath("$.data[1].afterStatus").value("CHECKING"))
                .andExpect(jsonPath("$.data[1].changedBy").value(accountId))
                .andExpect(jsonPath("$.data[1].checkRunId").value(nullValue()))
                .andExpect(jsonPath("$.data[1].note").value("검사 결과를 확인했습니다."))
                .andExpect(jsonPath("$.data[2].afterStatus").value("FIXED_PENDING_REVERIFY"))
                // 해결 행은 관리자와 근거 재검증을 함께 갖는다.
                .andExpect(jsonPath("$.data[3].beforeStatus").value("FIXED_PENDING_REVERIFY"))
                .andExpect(jsonPath("$.data[3].afterStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data[3].changedBy").value(accountId))
                .andExpect(jsonPath("$.data[3].checkRunId").value(reverifyId))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(4))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void pagesHistoryAndKeepsOtherIssuesOut() throws Exception {
        long issueId = registeredIssue();
        changeStatus(issueId, "CHECKING", "확인", null, 0);
        changeStatus(issueId, "FIXED_PENDING_REVERIFY", "수정 완료", null, 1);
        long otherIssueId = registeredIssue();
        changeStatus(otherIssueId, "CHECKING", "다른 문제", null, 0);

        mockMvc.perform(
                        get(HISTORY, issueId)
                                .with(loggedIn(accountId, "ADMIN"))
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].afterStatus").value("FIXED_PENDING_REVERIFY"))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void returnsEmptyHistoryForIssueWithoutRows() throws Exception {
        long issueId = newIssue();

        mockMvc.perform(get(HISTORY, issueId).with(loggedIn(accountId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "page=x"})
    void rejectsInvalidHistoryQuery(String query) throws Exception {
        long issueId = newIssue();

        mockMvc.perform(get(HISTORY + "?" + query, issueId).with(loggedIn(accountId, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        post(FORWARD, issueId)
                                .with(IssueTestFixtures.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rowVersion\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(HISTORY, issueId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void requiresCsrfTokenForForward() throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        post(FORWARD, issueId)
                                .with(loggedIn(accountId, "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rowVersion\":0}"))
                .andExpect(status().isForbidden());

        assertThat(rowVersion(issueId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        post(FORWARD, issueId)
                                .with(loggedIn(accountId, roleCode))
                                .with(IssueTestFixtures.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rowVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get(HISTORY, issueId).with(loggedIn(accountId, roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(rowVersion(issueId)).isZero();
    }

    /** 이력 없이 직접 넣은 문제. */
    private long newIssue() {
        return fixtures.insertIssue(detectedRunId, mappingId, "UNMATCHED", "NEW", EVIDENCE);
    }

    /** 검사가 등록 서비스로 만든 문제. 등록 이력 한 행이 있다. */
    private long registeredIssue() {
        return registrationService
                .register(
                        new NewIssue(
                                IssueKind.UNMATCHED,
                                IssueDedupeKey.forRule(
                                        IssueKind.UNMATCHED, "W2E-" + UUID.randomUUID(), 501),
                                detectedRunId,
                                "변환 값 E023과 같은 직원이 없습니다.",
                                mappingId,
                                null,
                                null,
                                "W23",
                                "E023",
                                Map.of()))
                .issueId();
    }

    private ResultActions forward(long issueId, long rowVersion) throws Exception {
        return mockMvc.perform(
                post(FORWARD, issueId)
                        .with(loggedIn(accountId, "ADMIN"))
                        .with(IssueTestFixtures.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":" + rowVersion + "}"));
    }

    private void changeStatus(
            long issueId, String status, String reason, Long runId, long rowVersion)
            throws Exception {
        String run = runId == null ? "" : ",\"resolvedCheckRunId\":" + runId;
        mockMvc.perform(
                        patch(STATUS, issueId)
                                .with(loggedIn(accountId, "ADMIN"))
                                .with(IssueTestFixtures.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"status\":\""
                                                + status
                                                + "\",\"reason\":\""
                                                + reason
                                                + "\""
                                                + run
                                                + ",\"rowVersion\":"
                                                + rowVersion
                                                + "}"))
                .andExpect(status().isOk());
    }

    private long historyCount(long issueId) {
        return jdbcClient
                .sql("select count(*) from issue_history where issue_id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }

    private long rowVersion(long issueId) {
        return jdbcClient
                .sql("select row_version from issue where id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }

    private static RequestPostProcessor loggedIn(long id, String roleCode) {
        LoginAccount account = new LoginAccount(id, "issue-test", roleCode, false);
        return authentication(account.toAuthentication());
    }
}

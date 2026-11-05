package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.servlet.http.Cookie;
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

/** API 067 상태 변경과 해결 전환 검증을 실제 PostgreSQL 제약까지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueStatusApiIntegrationTests {

    private static final String PATH = "/api/v1/issues/{id}/status";
    private static final String EVIDENCE = "{\"source\": \"MES\", \"causeConfirmed\": true}";
    private static final Instant FIXED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER_FIX = Instant.parse("2026-11-01T01:00:00Z");
    private static final Instant BEFORE_FIX = Instant.parse("2026-10-31T23:00:00Z");

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

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
    void startsChecking() throws Exception {
        long issueId = newIssue();

        change(issueId, body("CHECKING", "검사 결과를 확인했습니다.", null, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(issueId))
                .andExpect(jsonPath("$.data.status").value("CHECKING"))
                .andExpect(jsonPath("$.data.resolvedCheckRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.rowVersion").value(1))
                .andExpect(jsonPath("$.data.evidence.causeConfirmed").value(true));

        assertThat(lastHistory(issueId))
                .containsEntry("from_status", "NEW")
                .containsEntry("to_status", "CHECKING")
                .containsEntry("note", "검사 결과를 확인했습니다.")
                .containsEntry("check_run_id", null)
                .containsEntry("changed_by", accountId);
    }

    @Test
    void walksForwardAndBackBeforeResolution() throws Exception {
        long issueId = newIssue();

        change(issueId, body("CHECKING", "확인 시작", null, 0)).andExpect(status().isOk());
        change(issueId, body("FIXED_PENDING_REVERIFY", "원천 담당자가 수정 완료를 알림", null, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FIXED_PENDING_REVERIFY"))
                .andExpect(jsonPath("$.data.rowVersion").value(2));
        // 재검증에서 다시 실패하면 확인중으로 되돌린다.
        change(issueId, body("CHECKING", "재검증 실패로 다시 확인", null, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKING"))
                .andExpect(jsonPath("$.data.rowVersion").value(3));

        assertThat(historyCount(issueId)).isEqualTo(3);
    }

    @Test
    void resolvesWithPassedReverificationAfterFix() throws Exception {
        long issueId = fixedIssue();
        long reverifyId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(issueId, reverifyId, "PASSED");

        change(issueId, body("RESOLVED", "재검증 통과 확인", reverifyId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedCheckRunId").value(reverifyId))
                .andExpect(jsonPath("$.data.rowVersion").value(2));

        assertThat(lastHistory(issueId))
                .containsEntry("from_status", "FIXED_PENDING_REVERIFY")
                .containsEntry("to_status", "RESOLVED")
                .containsEntry("note", "재검증 통과 확인")
                .containsEntry("check_run_id", reverifyId)
                .containsEntry("changed_by", accountId);
        String outcome =
                jdbcClient
                        .sql("select resolved_outcome from issue where id = :id")
                        .param("id", issueId)
                        .query(String.class)
                        .single();
        assertThat(outcome).isEqualTo("PASSED");
    }

    @Test
    void acceptsPassAfterEarlierFailure() throws Exception {
        long issueId = fixedIssue();
        long failedId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(issueId, failedId, "FAILED");
        long passedId = reverify("SUCCEEDED", AFTER_FIX.plusSeconds(600));
        fixtures.insertVerification(issueId, passedId, "PASSED");

        change(issueId, body("RESOLVED", "두 번째 재검증 통과", passedId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void rejectsPassStartedBeforeFix() throws Exception {
        long issueId = fixedIssue();
        long staleId = reverify("SUCCEEDED", BEFORE_FIX);
        fixtures.insertVerification(issueId, staleId, "PASSED");

        expectReverificationRequired(issueId, staleId);
    }

    @Test
    void rejectsPassFollowedByLaterFailure() throws Exception {
        long issueId = fixedIssue();
        long passedId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(issueId, passedId, "PASSED");
        long failedId = reverify("SUCCEEDED", AFTER_FIX.plusSeconds(600));
        fixtures.insertVerification(issueId, failedId, "FAILED");

        expectReverificationRequired(issueId, passedId);
    }

    @Test
    void rejectsFailedVerification() throws Exception {
        long issueId = fixedIssue();
        long runId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(issueId, runId, "FAILED");

        expectReverificationRequired(issueId, runId);
    }

    @Test
    void rejectsRunThatDidNotCheckThisIssue() throws Exception {
        long issueId = fixedIssue();
        long otherIssueId = newIssue();
        long runId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(otherIssueId, runId, "PASSED");

        expectReverificationRequired(issueId, runId);
    }

    @Test
    void rejectsRunThatIsNotSucceededReverification() throws Exception {
        long issueId = fixedIssue();
        long manualId =
                fixtures.insertRun("MANUAL", "SUCCEEDED", AFTER_FIX, detectedRunId, accountId);
        fixtures.insertVerification(issueId, manualId, "PASSED");
        long runningId = reverify("RUNNING", AFTER_FIX);
        fixtures.insertVerification(issueId, runningId, "PASSED");

        expectReverificationRequired(issueId, manualId);
        expectReverificationRequired(issueId, runningId);
    }

    @Test
    void rejectsUnknownRun() throws Exception {
        long issueId = fixedIssue();

        expectReverificationRequired(issueId, Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "NEW:FIXED_PENDING_REVERIFY",
                "NEW:RESOLVED",
                "CHECKING:CHECKING",
                "CHECKING:RESOLVED"
            })
    void rejectsTransitionOutsideFlow(String transition) throws Exception {
        String[] parts = transition.split(":");
        long issueId = newIssue();
        fixtures.setStatus(issueId, parts[0]);
        Long runId = "RESOLVED".equals(parts[1]) ? reverify("SUCCEEDED", AFTER_FIX) : null;

        change(issueId, body(parts[1], "흐름 밖 전환", runId, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.error.details.currentStatus").value(parts[0]))
                .andExpect(jsonPath("$.error.details.requestedStatus").value(parts[1]));

        assertThat(historyCount(issueId)).isZero();
    }

    @Test
    void keepsResolvedIssueClosed() throws Exception {
        long issueId = fixedIssue();
        long reverifyId = reverify("SUCCEEDED", AFTER_FIX);
        fixtures.insertVerification(issueId, reverifyId, "PASSED");
        change(issueId, body("RESOLVED", "해결", reverifyId, 1)).andExpect(status().isOk());

        change(issueId, body("CHECKING", "다시 열기", null, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.error.details.allowedStatuses").isEmpty());
    }

    @Test
    void rejectsStaleRowVersion() throws Exception {
        long issueId = newIssue();
        change(issueId, body("CHECKING", "확인 시작", null, 0)).andExpect(status().isOk());

        change(issueId, body("FIXED_PENDING_REVERIFY", "예전 화면에서 보낸 변경", null, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.field").value("rowVersion"))
                .andExpect(jsonPath("$.error.details.actual").value(1));

        assertThat(historyCount(issueId)).isEqualTo(1);
    }

    @Test
    void rejectsRunIdForNonResolution() throws Exception {
        long issueId = newIssue();
        long runId = reverify("SUCCEEDED", AFTER_FIX);

        change(issueId, body("CHECKING", "확인", runId, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("resolvedCheckRunId"));
    }

    @Test
    void requiresRunIdForResolution() throws Exception {
        long issueId = fixedIssue();

        change(issueId, body("RESOLVED", "해결", null, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("resolvedCheckRunId"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"reason\":\"사유\",\"rowVersion\":0}",
                "{\"status\":\"NEW\",\"reason\":\"사유\",\"rowVersion\":0}",
                "{\"status\":\"DONE\",\"reason\":\"사유\",\"rowVersion\":0}",
                "{\"status\":\"CHECKING\",\"rowVersion\":0}",
                "{\"status\":\"CHECKING\",\"reason\":\"   \",\"rowVersion\":0}",
                "{\"status\":\"CHECKING\",\"reason\":\"사유\"}",
                "{\"status\":\"CHECKING\",\"reason\":\"사유\",\"rowVersion\":-1}",
                "{\"status\":\"RESOLVED\",\"reason\":\"사유\",\"resolvedCheckRunId\":0,\"rowVersion\":0}",
                "not-json"
            })
    void rejectsInvalidBody(String json) throws Exception {
        long issueId = newIssue();

        change(issueId, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsTooLongReason() throws Exception {
        long issueId = newIssue();

        change(issueId, body("CHECKING", "가".repeat(1001), null, 0))
                .andExpect(status().isBadRequest());
        change(issueId, body("CHECKING", "가".repeat(1000), null, 0)).andExpect(status().isOk());
    }

    @Test
    void returnsNotFoundForUnknownIssue() throws Exception {
        change(Long.MAX_VALUE, body("CHECKING", "확인", null, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("issue"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        patch(PATH, issueId)
                                .with(csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CHECKING", "확인", null, 0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void requiresCsrfToken() throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        patch(PATH, issueId)
                                .with(loggedIn("ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CHECKING", "확인", null, 0)))
                .andExpect(status().isForbidden());

        assertThat(historyCount(issueId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        long issueId = newIssue();

        mockMvc.perform(
                        patch(PATH, issueId)
                                .with(loggedIn(roleCode))
                                .with(csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CHECKING", "확인", null, 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(historyCount(issueId)).isZero();
    }

    @Test
    void listsAllowedStatusesOnConflict() throws Exception {
        long issueId = fixedIssue();

        change(issueId, body("FIXED_PENDING_REVERIFY", "같은 상태", null, 1))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.details.allowedStatuses")
                                .value(containsInAnyOrder("CHECKING", "RESOLVED")));
    }

    private void expectReverificationRequired(long issueId, long runId) throws Exception {
        change(issueId, body("RESOLVED", "해결", runId, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("REVERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.error.details.field").value("resolvedCheckRunId"));

        // 거절된 요청은 상태·버전·이력을 바꾸지 않는다.
        Map<String, Object> row =
                jdbcClient
                        .sql("select status, row_version from issue where id = :id")
                        .param("id", issueId)
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("status", "FIXED_PENDING_REVERIFY")
                .containsEntry("row_version", 1);
        assertThat(historyCount(issueId)).isEqualTo(1);
    }

    private long newIssue() {
        return fixtures.insertIssue(detectedRunId, mappingId, "UNMATCHED", "NEW", EVIDENCE);
    }

    /** 조치완료·재검증대기(rowVersion 1) 문제. FIXED_AT에 바뀌었다. */
    private long fixedIssue() {
        long issueId = newIssue();
        fixtures.markFixedAt(issueId, accountId, FIXED_AT);
        return issueId;
    }

    private long reverify(String status, Instant startedAt) {
        return fixtures.insertRun("REVERIFY", status, startedAt, detectedRunId, accountId);
    }

    private ResultActions change(long issueId, String json) throws Exception {
        return mockMvc.perform(
                patch(PATH, issueId)
                        .with(loggedIn("ADMIN"))
                        .with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    private static String body(String status, String reason, Long runId, long rowVersion) {
        String run = runId == null ? "" : ",\"resolvedCheckRunId\":" + runId;
        return "{\"status\":\""
                + status
                + "\",\"reason\":\""
                + reason
                + "\""
                + run
                + ",\"rowVersion\":"
                + rowVersion
                + "}";
    }

    private Map<String, Object> lastHistory(long issueId) {
        return jdbcClient
                .sql(
                        """
                        select from_status, to_status, note, check_run_id, changed_by
                        from issue_history where issue_id = :id
                        order by id desc limit 1
                        """)
                .param("id", issueId)
                .query()
                .singleRow();
    }

    private long historyCount(long issueId) {
        return jdbcClient
                .sql("select count(*) from issue_history where issue_id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }

    /**
     * 화면처럼 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 같은 값으로 보낸다. spring-security-test의 csrf()는 공유 컨텍스트의
     * CsrfFilter 저장소를 바꿔 같은 컨텍스트를 쓰는 다른 테스트의 실제 로그인 요청을 깨뜨리므로 쓰지 않는다.
     */
    private static RequestPostProcessor csrfToken() {
        String token = UUID.randomUUID().toString();
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, "issue-test", roleCode, false);
        return authentication(account.toAuthentication());
    }
}

package com.axcore.platform.issue;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** API 065·066을 실제 Service·Repository·PostgreSQL까지 연결해 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueApiIntegrationTests {

    private static final String LIST = "/api/v1/issues";
    private static final String DETAIL = "/api/v1/issues/{id}";
    private static final String CONFIRMED = "{\"source\": \"MES\", \"causeConfirmed\": true}";
    private static final String UNCONFIRMED = "{\"source\": \"MES\", \"checkedRows\": 45}";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long runId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        accountId = fixtures.insertAdmin();
        runId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
    }

    @Test
    void readsIssueWithEvidence() throws Exception {
        long issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);

        mockMvc.perform(get(DETAIL, issueId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(issueId))
                .andExpect(jsonPath("$.data.issueKind").value("UNMATCHED"))
                .andExpect(jsonPath("$.data.status").value("NEW"))
                .andExpect(jsonPath("$.data.dedupeKey").isNotEmpty())
                .andExpect(jsonPath("$.data.failureReason").value("변환 값 E023과 같은 직원이 없습니다."))
                .andExpect(jsonPath("$.data.rawValue").value("W23"))
                .andExpect(jsonPath("$.data.transformedValue").value("E023"))
                .andExpect(jsonPath("$.data.evidence.source").value("MES"))
                .andExpect(jsonPath("$.data.evidence.causeConfirmed").value(true))
                .andExpect(jsonPath("$.data.linkMappingId").value(mappingId))
                .andExpect(jsonPath("$.data.detectedCheckRunId").value(runId))
                .andExpect(jsonPath("$.data.objectInstanceId").value(nullValue()))
                .andExpect(jsonPath("$.data.proposalItemId").value(nullValue()))
                .andExpect(jsonPath("$.data.heldItemId").value(nullValue()))
                .andExpect(jsonPath("$.data.resolvedCheckRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.forwardedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.forwardedBy").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.rowVersion").value(0));
    }

    @Test
    void marksCauseUnconfirmedWhenEvidenceDoesNotSay() throws Exception {
        long issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", UNCONFIRMED);

        mockMvc.perform(get(DETAIL, issueId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.checkedRows").value(45))
                .andExpect(jsonPath("$.data.evidence.causeConfirmed").value(false));

        mockMvc.perform(get(LIST).with(admin()).param("linkMappingId", String.valueOf(mappingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.causeConfirmed").value(false));
    }

    @Test
    void showsResolvedRunAndForwardRecord() throws Exception {
        long issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        long reverifyId = fixtures.insertReverifyRun(runId, accountId);
        fixtures.resolve(issueId, reverifyId);
        jdbcClient
                .sql(
                        "update issue set forwarded_at = now(), forwarded_by = :accountId where id = :id")
                .param("accountId", accountId)
                .param("id", issueId)
                .update();

        mockMvc.perform(get(DETAIL, issueId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedCheckRunId").value(reverifyId))
                .andExpect(jsonPath("$.data.forwardedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.forwardedBy").value(accountId));
    }

    @Test
    void listsNewestFirstWithPages() throws Exception {
        long first = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        long second = fixtures.insertIssue(runId, mappingId, "DUPLICATE", "NEW", CONFIRMED);
        long third = fixtures.insertIssue(runId, mappingId, "VIOLATION", "NEW", CONFIRMED);
        fixtures.setCreatedAt(first, Instant.parse("2026-10-27T00:00:00Z"));
        fixtures.setCreatedAt(second, Instant.parse("2026-10-28T00:00:00Z"));
        fixtures.setCreatedAt(third, Instant.parse("2026-10-28T00:00:00Z"));

        mockMvc.perform(
                        get(LIST)
                                .with(admin())
                                .param("linkMappingId", String.valueOf(mappingId))
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(third))
                .andExpect(jsonPath("$.data[1].id").value(second))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mockMvc.perform(
                        get(LIST)
                                .with(admin())
                                .param("linkMappingId", String.valueOf(mappingId))
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void filtersByStatusKindAndRule() throws Exception {
        long expected = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "CHECKING", CONFIRMED);
        fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        fixtures.insertIssue(runId, mappingId, "DUPLICATE", "CHECKING", CONFIRMED);
        long otherMappingId = fixtures.insertLinkMapping();
        fixtures.insertIssue(runId, otherMappingId, "UNMATCHED", "CHECKING", CONFIRMED);

        mockMvc.perform(
                        get(LIST)
                                .with(admin())
                                .param("status", "CHECKING")
                                .param("issueKind", "UNMATCHED")
                                .param("linkMappingId", String.valueOf(mappingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(expected))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void filtersByCreatedPeriodIncludingBothEnds() throws Exception {
        long before = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        long atFrom = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        long atTo = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        long after = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);
        fixtures.setCreatedAt(before, Instant.parse("2026-10-26T23:59:59Z"));
        fixtures.setCreatedAt(atFrom, Instant.parse("2026-10-27T00:00:00Z"));
        fixtures.setCreatedAt(atTo, Instant.parse("2026-10-28T00:00:00Z"));
        fixtures.setCreatedAt(after, Instant.parse("2026-10-28T00:00:01Z"));

        // 시간대를 붙인 입력은 그 시각으로 해석한다. 09:00+09:00은 00:00Z와 같다.
        mockMvc.perform(
                        get(LIST)
                                .with(admin())
                                .param("linkMappingId", String.valueOf(mappingId))
                                .param("from", "2026-10-27T09:00:00+09:00")
                                .param("to", "2026-10-28T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(atTo))
                .andExpect(jsonPath("$.data[1].id").value(atFrom));
    }

    @Test
    void returnsEmptyPageWhenNothingMatches() throws Exception {
        fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);

        mockMvc.perform(
                        get(LIST)
                                .with(admin())
                                .param("linkMappingId", String.valueOf(mappingId))
                                .param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "status=OPEN",
                "issueKind=MISSING",
                "linkMappingId=0",
                "page=-1",
                "size=0",
                "from=2026-10-27T00:00:00",
                "from=not-a-time",
                "from=2026-10-28T00:00:00Z&to=2026-10-27T00:00:00Z"
            })
    void rejectsInvalidQuery(String query) throws Exception {
        mockMvc.perform(get(LIST + "?" + query).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForUnknownIssue() throws Exception {
        mockMvc.perform(get(DETAIL, Long.MAX_VALUE).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("issue"));
    }

    @Test
    void rejectsNonPositiveId() throws Exception {
        mockMvc.perform(get(DETAIL, 0).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        long issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);

        mockMvc.perform(get(LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(DETAIL, issueId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        long issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "NEW", CONFIRMED);

        mockMvc.perform(get(LIST).with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get(DETAIL, issueId).with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private RequestPostProcessor admin() {
        return loggedIn("ADMIN");
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, "issue-test", roleCode, false);
        return authentication(account.toAuthentication());
    }
}

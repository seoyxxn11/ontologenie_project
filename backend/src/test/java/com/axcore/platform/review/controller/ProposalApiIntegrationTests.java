package com.axcore.platform.review.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProposalApiIntegrationTests {

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    private long accountId;
    private long analysisRunId;
    private String loginId;

    @BeforeEach
    void prepareAccountAndAnalysis() {
        loginId = "proposal-api-" + UUID.randomUUID();

        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (
                                    login_id, display_name, password_hash,
                                    role_id, must_change_password
                                )
                                values (
                                    :loginId, '테스트 관리자', 'test-hash',
                                    (select id from role where code = 'ADMIN'),
                                    false
                                )
                                returning id
                                """)
                        .param("loginId", loginId)
                        .query(Long.class)
                        .single();

        analysisRunId =
                jdbcClient
                        .sql(
                                """
                                insert into analysis_run (
                                    run_kind, work_key, status,
                                    requested_by, started_at, finished_at
                                )
                                values (
                                    'INITIAL', :workKey, 'SUCCEEDED',
                                    :accountId, now(), now()
                                )
                                returning id
                                """)
                        .param("workKey", "api-analysis-" + UUID.randomUUID())
                        .param("accountId", accountId)
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsRealDetailWithSucceededCheck() throws Exception {
        long proposalId = insertProposal("AI", "PENDING");

        long checkRunId =
                insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-10-30T01:00:00Z");

        insertResult(checkRunId, proposalId, 1, 85, 100);

        mockMvc.perform(get("/api/v1/proposals/{id}", proposalId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(proposalId))
                .andExpect(jsonPath("$.data.analysisRunId").value(analysisRunId))
                .andExpect(jsonPath("$.data.origin").value("AI"))
                .andExpect(jsonPath("$.data.itemKind").value("LINK_RULE"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.payload.linkTypeId").value(5))
                .andExpect(jsonPath("$.data.evidence.reason").value("통합 테스트 근거"))
                .andExpect(jsonPath("$.data.aiConfidence").value(0.86))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.applyStatus").value("NOT_APPLIED"))
                .andExpect(jsonPath("$.data.latestCheck.checkRunId").value(checkRunId))
                .andExpect(jsonPath("$.data.latestCheck.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.latestCheck.matchedCount").value(85))
                .andExpect(jsonPath("$.data.latestCheck.totalCount").value(100))
                .andExpect(jsonPath("$.data.latestCheck.matchRate").value(0.85))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void filtersAndPagesRealProposals() throws Exception {
        long firstId = insertProposal("MANUAL", "PENDING");
        long secondId = insertProposal("MANUAL", "PENDING");
        long thirdId = insertProposal("MANUAL", "PENDING");

        insertProposal("MANUAL", "HELD");
        insertProposal("AI", "PENDING");

        long secondCheckId =
                insertRun(secondId, 1, "CANDIDATE", "SUCCEEDED", "2026-10-30T01:00:00Z");

        insertResult(secondCheckId, secondId, 1, 85, 100);

        long thirdCheckId = insertRun(thirdId, 1, "CANDIDATE", "RUNNING", "2026-10-30T01:00:00Z");

        mockMvc.perform(
                        get("/api/v1/proposals")
                                .with(admin())
                                .param("origin", "MANUAL")
                                .param("itemKind", "LINK_RULE")
                                .param("reviewStatus", "PENDING")
                                .param("applyStatus", "NOT_APPLIED")
                                .param("page", "0")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(thirdId))
                .andExpect(jsonPath("$.data[0].latestCheck.checkRunId").value(thirdCheckId))
                .andExpect(jsonPath("$.data[0].latestCheck.status").value("RUNNING"))
                .andExpect(jsonPath("$.data[0].latestCheck.matchRate").value(nullValue()))
                .andExpect(jsonPath("$.data[1].id").value(secondId))
                .andExpect(jsonPath("$.data[1].latestCheck.checkRunId").value(secondCheckId))
                .andExpect(jsonPath("$.data[1].latestCheck.matchRate").value(0.85))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mockMvc.perform(
                        get("/api/v1/proposals")
                                .with(admin())
                                .param("origin", "MANUAL")
                                .param("itemKind", "LINK_RULE")
                                .param("reviewStatus", "PENDING")
                                .param("applyStatus", "NOT_APPLIED")
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[0].latestCheck").hasJsonPath())
                .andExpect(jsonPath("$.data[0].latestCheck").value(nullValue()))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void excludesOldRevisionAndManualCheck() throws Exception {
        long proposalId = insertProposal("MANUAL", "PENDING");

        long oldCheckId =
                insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-10-30T01:00:00Z");

        insertResult(oldCheckId, proposalId, 1, 100, 100);

        addRevision(proposalId, 2);

        long manualCheckId =
                insertRun(proposalId, 2, "MANUAL", "SUCCEEDED", "2026-10-30T02:00:00Z");

        insertResult(manualCheckId, proposalId, 2, 100, 100);

        mockMvc.perform(get("/api/v1/proposals/{id}", proposalId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.latestCheck").hasJsonPath())
                .andExpect(jsonPath("$.data.latestCheck").value(nullValue()));
    }

    @Test
    void returnsNewestFailedCheckInsteadOfOlderSuccess() throws Exception {
        long proposalId = insertProposal("MANUAL", "PENDING");

        long succeededId =
                insertRun(proposalId, 1, "CANDIDATE", "SUCCEEDED", "2026-10-30T01:00:00Z");

        insertResult(succeededId, proposalId, 1, 100, 100);

        long failedId = insertRun(proposalId, 1, "CANDIDATE", "FAILED", "2026-10-30T02:00:00Z");

        mockMvc.perform(get("/api/v1/proposals/{id}", proposalId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestCheck.checkRunId").value(failedId))
                .andExpect(jsonPath("$.data.latestCheck.status").value("FAILED"))
                .andExpect(jsonPath("$.data.latestCheck.matchedCount").value(nullValue()))
                .andExpect(jsonPath("$.data.latestCheck.totalCount").value(nullValue()))
                .andExpect(jsonPath("$.data.latestCheck.matchRate").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void findsCheckWithoutResult(String checkStatus) throws Exception {
        long proposalId = insertProposal("MANUAL", "PENDING");

        long checkRunId =
                insertRun(proposalId, 1, "CANDIDATE", checkStatus, "2026-10-30T01:00:00Z");

        mockMvc.perform(get("/api/v1/proposals/{id}", proposalId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestCheck.checkRunId").value(checkRunId))
                .andExpect(jsonPath("$.data.latestCheck.status").value(checkStatus))
                .andExpect(jsonPath("$.data.latestCheck.matchedCount").value(nullValue()))
                .andExpect(jsonPath("$.data.latestCheck.totalCount").value(nullValue()))
                .andExpect(jsonPath("$.data.latestCheck.matchRate").value(nullValue()));
    }

    @Test
    void returnsNotFoundFromRealService() throws Exception {
        mockMvc.perform(get("/api/v1/proposals/{id}", Long.MAX_VALUE).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("proposal_item"))
                .andExpect(jsonPath("$.error.details.id").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    private RequestPostProcessor admin() {
        LoginAccount account = new LoginAccount(accountId, loginId, "ADMIN", false);

        return authentication(account.toAuthentication());
    }

    private long insertProposal(String origin, String reviewStatus) {
        return jdbcClient
                .sql(
                        """
                        with inserted as (
                            insert into proposal_item (
                                analysis_run_id, origin, item_kind,
                                payload, evidence, ai_confidence,
                                review_status, created_by
                            )
                            values (
                                case
                                    when :origin = 'AI'
                                    then :analysisRunId
                                end,
                                :origin,
                                'LINK_RULE',
                                '{"linkTypeId":5}'::jsonb,
                                '{"reason":"통합 테스트 근거"}'::jsonb,
                                case
                                    when :origin = 'AI'
                                    then 0.8600
                                end,
                                :reviewStatus,
                                case
                                    when :origin = 'MANUAL'
                                    then :accountId
                                end
                            )
                            returning *
                        )
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, ai_confidence, created_by
                        )
                        select
                            id, revision,
                            payload, evidence, ai_confidence, created_by
                        from inserted
                        returning proposal_item_id
                        """)
                .param("origin", origin)
                .param("analysisRunId", analysisRunId)
                .param("reviewStatus", reviewStatus)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private void addRevision(long proposalId, int revision) {
        jdbcClient
                .sql(
                        """
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, ai_confidence, created_by
                        )
                        select
                            id, :revision,
                            payload, evidence, ai_confidence, created_by
                        from proposal_item
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .update();

        jdbcClient
                .sql(
                        """
                        update proposal_item
                        set revision = :revision
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .update();
    }

    private long insertRun(
            long proposalId,
            int revision,
            String triggerKind,
            String checkStatus,
            String requestedAt) {

        String targetScope =
                """
                {
                    "proposalItems": [
                        {"proposalItemId": %d, "revision": %d}
                    ]
                }
                """
                        .formatted(proposalId, revision);

        return jdbcClient
                .sql(
                        """
                        insert into check_run (
                            trigger_kind, status, scope, work_key,
                            requested_by, requested_at,
                            started_at, finished_at, error_message
                        )
                        values (
                            :triggerKind,
                            :status,
                            cast(:scope as jsonb),
                            :workKey,
                            :accountId,
                            cast(:requestedAt as timestamptz),
                            case
                                when :status <> 'QUEUED'
                                then cast(:requestedAt as timestamptz)
                            end,
                            case
                                when :status in ('SUCCEEDED', 'FAILED')
                                then cast(:requestedAt as timestamptz)
                            end,
                            case
                                when :status = 'FAILED'
                                then '통합 테스트 검사 실패'
                            end
                        )
                        returning id
                        """)
                .param("triggerKind", triggerKind)
                .param("status", checkStatus)
                .param("scope", targetScope)
                .param("workKey", "api-check-" + UUID.randomUUID())
                .param("accountId", accountId)
                .param("requestedAt", requestedAt)
                .query(Long.class)
                .single();
    }

    private void insertResult(
            long checkRunId, long proposalId, int revision, int matched, int total) {

        jdbcClient
                .sql(
                        """
                        insert into check_result (
                            check_run_id,
                            proposal_item_id,
                            proposal_item_revision,
                            matched_count,
                            total_count,
                            unmatched_count
                        )
                        values (
                            :checkRunId, :proposalId, :revision,
                            :matched, :total, :unmatched
                        )
                        """)
                .param("checkRunId", checkRunId)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .param("matched", matched)
                .param("total", total)
                .param("unmatched", total - matched)
                .update();
    }
}

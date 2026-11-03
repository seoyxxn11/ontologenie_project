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
import org.junit.jupiter.params.provider.CsvSource;
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
class ProposalRevisionApiTests {

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbc;

    private long accountId;
    private long proposalId;
    private String loginId;

    @BeforeEach
    void prepareData() {
        loginId = "revision-api-" + UUID.randomUUID();

        accountId =
                jdbc.sql(
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

        long analysisRunId =
                jdbc.sql(
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
                        .param("workKey", "revision-analysis-" + UUID.randomUUID())
                        .param("accountId", accountId)
                        .query(Long.class)
                        .single();

        proposalId =
                jdbc.sql(
                                """
                                with inserted as (
                                    insert into proposal_item (
                                        analysis_run_id, origin, item_kind,
                                        payload, evidence, ai_confidence
                                    )
                                    values (
                                        :analysisRunId, 'AI', 'LINK_RULE',
                                        '{"linkTypeId":5,"name":"original"}'::jsonb,
                                        '{"reason":"AI 최초 제안"}'::jsonb,
                                        0.86
                                    )
                                    returning *
                                )
                                insert into proposal_revision (
                                    proposal_item_id, revision,
                                    payload, evidence, ai_confidence,
                                    created_by, created_at
                                )
                                select
                                    id, revision,
                                    payload, evidence, ai_confidence,
                                    null, '2026-11-02T01:00:00Z'::timestamptz
                                from inserted
                                returning proposal_item_id
                                """)
                        .param("analysisRunId", analysisRunId)
                        .query(Long.class)
                        .single();

        jdbc.sql(
                        """
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, ai_confidence,
                            created_by, created_at
                        )
                        values (
                            :proposalId, 2,
                            '{"linkTypeId":5,"name":"edited"}'::jsonb,
                            '{"reason":"관리자 수정"}'::jsonb,
                            null, :accountId,
                            '2026-11-02T02:00:00Z'::timestamptz
                        )
                        """)
                .param("proposalId", proposalId)
                .param("accountId", accountId)
                .update();

        jdbc.sql(
                        """
                        update proposal_item
                        set revision = 2,
                            payload = '{"linkTypeId":5,"name":"edited"}'::jsonb,
                            evidence = '{"reason":"관리자 수정"}'::jsonb,
                            ai_confidence = null,
                            updated_by = :accountId
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .param("accountId", accountId)
                .update();
    }

    @Test
    void returnsOriginalAndEditedRevisionWithTheirOwnMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/proposals/{id}/revisions", proposalId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].revision").value(1))
                .andExpect(jsonPath("$.data[0].payload.name").value("original"))
                .andExpect(jsonPath("$.data[0].evidence.reason").value("AI 최초 제안"))
                .andExpect(jsonPath("$.data[0].aiConfidence").value(0.86))
                .andExpect(jsonPath("$.data[0].createdBy").hasJsonPath())
                .andExpect(jsonPath("$.data[0].createdBy").value(nullValue()))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-02T01:00:00Z"))
                .andExpect(jsonPath("$.data[1].revision").value(2))
                .andExpect(jsonPath("$.data[1].payload.name").value("edited"))
                .andExpect(jsonPath("$.data[1].evidence.reason").value("관리자 수정"))
                .andExpect(jsonPath("$.data[1].aiConfidence").hasJsonPath())
                .andExpect(jsonPath("$.data[1].aiConfidence").value(nullValue()))
                .andExpect(jsonPath("$.data[1].createdBy").value(accountId))
                .andExpect(jsonPath("$.data[1].createdAt").value("2026-11-02T02:00:00Z"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @ParameterizedTest
    @CsvSource({"0,1,true", "1,2,false"})
    void pagesRevisionsInAscendingOrder(int page, int revision, boolean hasNext) throws Exception {
        mockMvc.perform(
                        get("/api/v1/proposals/{id}/revisions", proposalId)
                                .with(loggedIn("ADMIN"))
                                .param("page", String.valueOf(page))
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].revision").value(revision))
                .andExpect(jsonPath("$.meta.page").value(page))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(hasNext));
    }

    @Test
    void returnsEmptyPageWithMetadataWhenPageIsBeyondLastPage() throws Exception {
        mockMvc.perform(
                        get("/api/v1/proposals/{id}/revisions", proposalId)
                                .with(loggedIn("ADMIN"))
                                .param("page", "2")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void excludesRevisionsBelongingToAnotherProposal() throws Exception {
        jdbc.sql(
                        """
                        with inserted as (
                            insert into proposal_item (
                                origin, item_kind, payload, created_by
                            )
                            values (
                                'MANUAL', 'LINK_RULE',
                                '{"name":"another-proposal"}'::jsonb,
                                :accountId
                            )
                            returning *
                        )
                        insert into proposal_revision (
                            proposal_item_id, revision, payload, created_by
                        )
                        select id, revision, payload, created_by
                        from inserted
                        """)
                .param("accountId", accountId)
                .update();

        mockMvc.perform(get("/api/v1/proposals/{id}/revisions", proposalId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].payload.name").value("original"))
                .andExpect(jsonPath("$.data[1].payload.name").value("edited"))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    @Test
    void returnsNotFoundWhenProposalDoesNotExist() throws Exception {
        mockMvc.perform(
                        get("/api/v1/proposals/{id}/revisions", Long.MAX_VALUE)
                                .with(loggedIn("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("proposal_item"));
    }

    @ParameterizedTest
    @CsvSource({
        "page,-1",
        "page,abc",
        "page,2147483648",
        "size,0",
        "size,-1",
        "size,abc",
        "size,2147483648"
    })
    void rejectsInvalidPageParameters(String parameter, String value) throws Exception {
        mockMvc.perform(
                        get("/api/v1/proposals/{id}/revisions", proposalId)
                                .with(loggedIn("ADMIN"))
                                .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidProposalId(String id) throws Exception {
        mockMvc.perform(get("/api/v1/proposals/" + id + "/revisions").with(loggedIn("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/proposals/{id}/revisions", proposalId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRoles(String roleCode) throws Exception {
        mockMvc.perform(
                        get("/api/v1/proposals/{id}/revisions", proposalId)
                                .with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, loginId, roleCode, false);
        return authentication(account.toAuthentication());
    }
}

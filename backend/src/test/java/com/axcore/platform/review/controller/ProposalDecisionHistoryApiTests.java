package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.DecisionHistoryFixtures;
import java.util.Map;
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
class ProposalDecisionHistoryApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        account = data.account();
        proposal = data.proposal(account);
    }

    @Test
    void returnsTheSpecifiedFieldsIncludingNullOptionAndPastRevision() throws Exception {
        long id =
                data.decision(
                        proposal,
                        1,
                        "HOLD",
                        null,
                        "추가 확인 필요",
                        account,
                        "2026-11-04T10:30:00+09:00");
        data.revise(proposal, 2);
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal).with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].proposalItemId").value(proposal))
                .andExpect(jsonPath("$.data[0].itemRevision").value(1))
                .andExpect(jsonPath("$.data[0].decision").value("HOLD"))
                .andExpect(jsonPath("$.data[0].selectedOptionId").hasJsonPath())
                .andExpect(jsonPath("$.data[0].selectedOptionId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].reason").value("추가 확인 필요"))
                .andExpect(jsonPath("$.data[0].decidedBy").value(account))
                .andExpect(jsonPath("$.data[0].decidedAt").value("2026-11-04T01:30:00Z"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void returnsAllDecisionsForAllRevisionsWithTheirOriginalOptions() throws Exception {
        long oldOption = data.option(proposal, 1);
        long held =
                data.decision(
                        proposal, 1, "HOLD", oldOption, "보류 사유", account, "2026-11-04T01:00:00Z");
        data.revise(proposal, 2);
        long option = data.option(proposal, 2);
        long approved =
                data.decision(
                        proposal, 2, "APPROVE", option, "승인 사유", account, "2026-11-04T02:00:00Z");
        data.decision(proposal, 2, "REJECT", null, "거절 사유", account, "2026-11-04T03:00:00Z");
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal).with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].decision").value("REJECT"))
                .andExpect(jsonPath("$.data[1].id").value(approved))
                .andExpect(jsonPath("$.data[1].selectedOptionId").value(option))
                .andExpect(jsonPath("$.data[1].itemRevision").value(2))
                .andExpect(jsonPath("$.data[2].id").value(held))
                .andExpect(jsonPath("$.data[2].selectedOptionId").value(oldOption))
                .andExpect(jsonPath("$.data[2].itemRevision").value(1));
    }

    @Test
    void pagesInStableTimeThenIdOrderAndExcludesAnotherCandidate() throws Exception {
        String at = "2026-11-04T01:30:00Z";
        long first = data.decision(proposal, 1, "HOLD", null, "첫 판단", account, at);
        long second = data.decision(proposal, 1, "HOLD", null, "두 번째 판단", account, at);
        long third = data.decision(proposal, 1, "REJECT", null, "최종 판단", account, at);
        long other = data.proposal(account);
        data.decision(other, 1, "HOLD", null, "다른 후보", account, "2026-11-05T00:00:00Z");
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .param("size", "2")
                                .with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(third))
                .andExpect(jsonPath("$.data[1].id").value(second))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .param("page", "1")
                                .param("size", "2")
                                .with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .param("page", "2")
                                .param("size", "2")
                                .with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void existingCandidateWithoutDecisionsReturnsAnEmptyPage() throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal).with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void nonexistentCandidateReturns404() throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/decisions", Long.MAX_VALUE).with(login("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("proposal_item"));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,-1", "page,text", "size,text", "page,2147483648"})
    void rejectsInvalidPageParameters(String name, String value) throws Exception {
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .param(name, value)
                                .with(login("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "text"})
    void rejectsInvalidCandidateIds(String id) throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/decisions", id).with(login("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "PLANNER", "MAINTENANCE"})
    void requiresAdmin(String role) throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal).with(login(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
    }

    @Test
    void retainsTheTemporaryPasswordRestriction() throws Exception {
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .with(
                                        authentication(
                                                new LoginAccount(account, "admin", "ADMIN", true)
                                                        .toAuthentication())))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "HELD", "APPROVED", "REJECTED"})
    void readsHistoryForEveryReviewStatusWithoutChangingAnything(String reviewStatus)
            throws Exception {
        data.decision(proposal, 1, "HOLD", null, "기존 판단", account, "2026-11-04T01:00:00Z");
        jdbc.sql("update proposal_item set review_status=:status where id=:id")
                .param("status", reviewStatus)
                .param("id", proposal)
                .update();
        Map<String, Object> before = candidateState();
        mvc.perform(get("/api/v1/proposals/{id}/decisions", proposal).with(login("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        assertThat(candidateState()).isEqualTo(before);
        assertThat(data.count(proposal)).isEqualTo(1);
    }

    private Map<String, Object> candidateState() {
        return jdbc.sql("select * from proposal_item where id=:id")
                .param("id", proposal)
                .query()
                .singleRow();
    }

    private RequestPostProcessor login(String role) {
        return authentication(new LoginAccount(account, "admin", role, false).toAuthentication());
    }
}

package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.DecisionHistoryFixtures;
import java.util.LinkedHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "test.context=csrf-api")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ProposalDecisionApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        account = data.account();
        proposal = data.proposal(account);
    }

    @ParameterizedTest
    @CsvSource({"REJECT,REJECTED", "HOLD,HELD"})
    void commitsNonApprovalDecisionAndItsAuditWithoutApplying(String decision, String status)
            throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/proposals/{id}/decisions", proposal)
                                        .with(
                                                authentication(
                                                        new LoginAccount(
                                                                        account,
                                                                        "reviewer",
                                                                        "ADMIN",
                                                                        false)
                                                                .toAuthentication()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "revision",
                                                                1,
                                                                "decision",
                                                                decision,
                                                                "reason",
                                                                "추가 검토 결과"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.decision").value(decision))
                        .andExpect(jsonPath("$.data.reviewStatus").value(status))
                        .andExpect(jsonPath("$.data.applyStatus").value("NOT_APPLIED"))
                        .andExpect(jsonPath("$.data.appliedEntityIds").isEmpty())
                        .andExpect(jsonPath("$.data.heldItemIds").doesNotExist())
                        .andReturn();

        long auditId =
                json.readTree(response.getResponse().getContentAsString())
                        .at("/data/actionExecutionId")
                        .asLong();
        assertThat(auditId).isPositive();
        assertThat(data.count(proposal)).isEqualTo(1);
        assertThat(
                        jdbc.sql("select review_status from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(String.class)
                                .single())
                .isEqualTo(status);
        assertThat(
                        jdbc.sql("select status from action_execution where id=:id")
                                .param("id", auditId)
                                .query(String.class)
                                .single())
                .isEqualTo("SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsupportedApprovalRollsBackAndReturnsTheSpecifiedPublicError(boolean held)
            throws Exception {
        if (held) {
            jdbc.sql("update proposal_item set review_status='HELD' where id=:id")
                    .param("id", proposal)
                    .update();
        }
        data.successfulCheck(proposal, 1, account);
        var response =
                decide(proposal, Map.of("revision", 1, "decision", "APPROVE", "reason", "검사 확인"))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.error.code").value("APPLY_FAILED"))
                        .andExpect(jsonPath("$.error.details.reason").isNotEmpty())
                        .andReturn();
        long audit =
                json.readTree(response.getResponse().getContentAsString())
                        .at("/error/details/actionExecutionId")
                        .asLong();
        assertThat(audit).isPositive();
        assertThat(data.count(proposal)).isZero();
        assertThat(
                        jdbc.sql("select review_status from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(String.class)
                                .single())
                .isEqualTo(held ? "HELD" : "PENDING");
        assertThat(
                        jdbc.sql("select status from action_execution where id=:id")
                                .param("id", audit)
                                .query(String.class)
                                .single())
                .isEqualTo("FAILED");
    }

    @Test
    void repeatedHoldAndAFinalRejectAreVisibleInDecisionHistory() throws Exception {
        for (String decision : new String[] {"HOLD", "HOLD", "REJECT"}) {
            decide(proposal, Map.of("revision", 1, "decision", decision, "reason", "판단"))
                    .andExpect(status().isOk());
        }
        assertThat(data.count(proposal)).isEqualTo(3);
        mvc.perform(
                        get("/api/v1/proposals/{id}/decisions", proposal)
                                .with(
                                        authentication(
                                                new LoginAccount(
                                                                account, "reviewer", "ADMIN", false)
                                                        .toAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].decision").value("REJECT"));
        var blocked =
                decide(proposal, Map.of("revision", 1, "decision", "HOLD", "reason", "재결정"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("PROPOSAL_ALREADY_DECIDED"))
                        .andReturn();
        assertThat(data.count(proposal)).isEqualTo(3);
        assertBlockedAudit(blocked, proposal, "PROPOSAL_ALREADY_DECIDED");
    }

    @ParameterizedTest
    @CsvSource({
        "MISSING,404,RESOURCE_NOT_FOUND",
        "REVISION,409,REVISION_CONFLICT",
        "OPTION,400,VALIDATION_FAILED",
        "CHECK,422,REVIEW_VALIDATION_FAILED"
    })
    void preservesTheExistingValidationErrorsAndAuditsBlockedDecisions(
            String input, int statusCode, String errorCode) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", input.equals("REVISION") ? 2 : 1);
        body.put("decision", input.equals("CHECK") ? "APPROVE" : "HOLD");
        body.put("reason", "확인");
        if (input.equals("OPTION")) body.put("selectedOptionId", Long.MAX_VALUE);
        long targetId = input.equals("MISSING") ? Long.MAX_VALUE : proposal;
        var blocked =
                decide(targetId, body)
                        .andExpect(status().is(statusCode))
                        .andExpect(jsonPath("$.error.code").value(errorCode))
                        .andReturn();
        assertThat(data.count(proposal)).isZero();
        assertBlockedAudit(blocked, targetId, errorCode);
    }

    @Test
    void enforcesAuthenticationCsrfRoleAndBodyValidation() throws Exception {
        String body = "{\"revision\":1,\"decision\":\"HOLD\",\"reason\":\"확인\"}";
        mvc.perform(
                        post("/api/v1/proposals/{id}/decisions", proposal)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        var admin =
                authentication(
                        new LoginAccount(account, "reviewer", "ADMIN", false).toAuthentication());
        mvc.perform(
                        post("/api/v1/proposals/{id}/decisions", proposal)
                                .with(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        var blocked =
                mvc.perform(
                                post("/api/v1/proposals/{id}/decisions", proposal)
                                        .with(
                                                authentication(
                                                        new LoginAccount(
                                                                        account, "viewer", "VIEWER",
                                                                        false)
                                                                .toAuthentication()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                        .andReturn();
        assertBlockedAudit(blocked, proposal, "FORBIDDEN");
        decide(proposal, Map.of("revision", 1, "decision", "HOLD", "reason", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void repeatedHoldRecordsTheNewDecisionWithoutInventingACandidateUpdate() throws Exception {
        var body = Map.<String, Object>of("revision", 1, "decision", "HOLD", "reason", "재검토 필요");
        decide(proposal, body).andExpect(status().isOk());
        var repeated = decide(proposal, body).andExpect(status().isOk()).andReturn();
        long audit =
                json.readTree(repeated.getResponse().getContentAsString())
                        .at("/data/actionExecutionId")
                        .asLong();
        assertThat(data.count(proposal)).isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "select table_name from entity_change where action_execution_id=:id")
                                .param("id", audit)
                                .query(String.class)
                                .list())
                .containsExactly("review_decision");
    }

    private void assertBlockedAudit(MvcResult response, long targetId, String errorCode)
            throws Exception {
        long auditId =
                json.readTree(response.getResponse().getContentAsString())
                        .at("/error/details/actionExecutionId")
                        .asLong();
        assertThat(auditId).isPositive();
        var audit =
                jdbc.sql(
                                """
                        select status, blocked_reason, target_type, target_id,
                            result->>'errorCode' as error_code,
                            jsonb_typeof(result->'basisCheckRunId') as basis_type
                        from action_execution where id=:id
                        """)
                        .param("id", auditId)
                        .query()
                        .singleRow();
        assertThat(audit)
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_type", "proposal_item")
                .containsEntry("target_id", targetId)
                .containsEntry("error_code", errorCode)
                .containsEntry("basis_type", "null");
        assertThat((String) audit.get("blocked_reason")).isNotBlank();
        assertThat(
                        jdbc.sql("select count(*) from entity_change where action_execution_id=:id")
                                .param("id", auditId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private ResultActions decide(long id, Map<String, Object> body) throws Exception {
        return mvc.perform(
                post("/api/v1/proposals/{id}/decisions", id)
                        .with(
                                authentication(
                                        new LoginAccount(account, "reviewer", "ADMIN", false)
                                                .toAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }
}

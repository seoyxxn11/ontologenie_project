package com.axcore.platform.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.LatestCheckResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.service.ProposalService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ProposalControllerTests {

    @Autowired MockMvc mockMvc;

    @MockitoBean ProposalService proposalService;

    @Test
    void returnsFilteredPageForAdmin() throws Exception {
        ProposalSearchRequest request =
                new ProposalSearchRequest("AI", "LINK_RULE", "PENDING", "NOT_APPLIED", 1, 2);

        PageRequest pageable = PageRequest.of(1, 2, Sort.by("id").descending());

        Page<ProposalResponse> result = new PageImpl<>(List.of(sampleResponse()), pageable, 3);

        given(proposalService.search(any(), eq(request), eq(pageable))).willReturn(result);

        mockMvc.perform(
                        get("/api/v1/proposals")
                                .with(loggedIn("ADMIN"))
                                .param("origin", "AI")
                                .param("itemKind", "LINK_RULE")
                                .param("reviewStatus", "PENDING")
                                .param("applyStatus", "NOT_APPLIED")
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].latestCheck.checkRunId").value(402))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void usesDefaultPageAndSizeWhenOmitted() throws Exception {
        ProposalSearchRequest request =
                new ProposalSearchRequest(null, null, null, null, null, null);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").descending());

        given(proposalService.search(any(), eq(request), eq(pageable)))
                .willReturn(Page.empty(pageable));

        mockMvc.perform(get("/api/v1/proposals").with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void returnsDetailWithLatestCheckForAdmin() throws Exception {
        given(proposalService.getProposal(any(), eq(10L))).willReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/proposals/10").with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.payload.linkTypeId").value(5))
                .andExpect(jsonPath("$.data.evidence.reason").value("테스트용 근거"))
                .andExpect(jsonPath("$.data.aiConfidence").value(0.86))
                .andExpect(jsonPath("$.data.latestCheck.checkRunId").value(402))
                .andExpect(jsonPath("$.data.latestCheck.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.latestCheck.matchRate").value(0.6667))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void returnsNotFoundForUnknownProposal() throws Exception {
        given(proposalService.getProposal(any(), eq(999L)))
                .willThrow(
                        new BusinessException(
                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                Map.of("resource", "proposal_item", "id", 999L)));

        mockMvc.perform(get("/api/v1/proposals/999").with(loggedIn("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("proposal_item"))
                .andExpect(jsonPath("$.error.details.id").value(999))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "origin, UNKNOWN",
        "itemKind, UNKNOWN",
        "reviewStatus, UNKNOWN",
        "applyStatus, UNKNOWN",
        "page, -1",
        "size, 0",
        "page, abc"
    })
    void rejectsInvalidQueryParameter(String field, String value) throws Exception {

        mockMvc.perform(get("/api/v1/proposals").with(loggedIn("ADMIN")).param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(proposalService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidProposalId(String id) throws Exception {
        mockMvc.perform(get("/api/v1/proposals/" + id).with(loggedIn("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(proposalService);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/proposals", "/api/v1/proposals/10", "/api/v1/proposals/10/options"})
    void requiresAuthentication(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        verifyNoInteractions(proposalService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        for (String path :
                List.of(
                        "/api/v1/proposals",
                        "/api/v1/proposals/10",
                        "/api/v1/proposals/10/options")) {

            mockMvc.perform(get(path).with(loggedIn(roleCode)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }

        verifyNoInteractions(proposalService);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/proposals", "/api/v1/proposals/10", "/api/v1/proposals/10/options"})
    void blocksAdminWhoMustChangePassword(String path) throws Exception {
        LoginAccount account = new LoginAccount(1L, "test-admin", "ADMIN", true);

        mockMvc.perform(get(path).with(authentication(account.toAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(proposalService);
    }

    private static RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(1L, "test-user", roleCode, false);

        return authentication(account.toAuthentication());
    }

    private ProposalResponse sampleResponse() {
        LatestCheckResponse latestCheck =
                new LatestCheckResponse(402L, "SUCCEEDED", 2, 3, new BigDecimal("0.6667"), false);

        return new ProposalResponse(
                10L,
                12L,
                "AI",
                "LINK_RULE",
                3,
                Map.of("linkTypeId", 5),
                Map.of("reason", "테스트용 근거"),
                new BigDecimal("0.8600"),
                "PENDING",
                "NOT_APPLIED",
                null,
                null,
                true,
                latestCheck);
    }
}

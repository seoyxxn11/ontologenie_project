package com.axcore.platform.review.dto;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.global.response.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = ProposalResponseTests.TestController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
        })
@Import(ProposalResponseTests.TestController.class)
class ProposalResponseTests {

    @Autowired MockMvc mockMvc;

    @Test
    void returnsProposalAsJson() throws Exception {
        mockMvc.perform(get("/test/proposal-dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.origin").value("AI"))
                .andExpect(jsonPath("$.data.evidence").isMap())
                .andExpect(jsonPath("$.data.evidence").isEmpty())
                .andExpect(jsonPath("$.data.latestCheck").hasJsonPath())
                .andExpect(jsonPath("$.data.latestCheck").value(nullValue()));
    }

    @Test
    void returnsAppliedAtAsUtc() throws Exception {
        mockMvc.perform(get("/test/proposal-dto/applied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedAt").value("2026-10-28T03:00:00Z"));
    }

    @Test
    void returnsLatestCheckAsJson() throws Exception {
        mockMvc.perform(get("/test/proposal-dto/checked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestCheck").isMap())
                .andExpect(jsonPath("$.data.latestCheck.checkRunId").value(10))
                .andExpect(jsonPath("$.data.latestCheck.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.latestCheck.matchedCount").value(85))
                .andExpect(jsonPath("$.data.latestCheck.totalCount").value(100))
                .andExpect(jsonPath("$.data.latestCheck.matchRate").value(0.85));
    }

    @Test
    void keepsNullFieldsForManualProposal() throws Exception {
        mockMvc.perform(get("/test/proposal-dto/manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                .andExpect(jsonPath("$.data.analysisRunId").hasJsonPath())
                .andExpect(jsonPath("$.data.analysisRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.aiConfidence").hasJsonPath())
                .andExpect(jsonPath("$.data.aiConfidence").value(nullValue()));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/proposal-dto")
        ApiResponse<ProposalResponse> proposal() {
            ProposalResponse response =
                    new ProposalResponse(
                            1L,
                            12L,
                            "AI",
                            "LINK_RULE",
                            2,
                            Map.of("linkTypeId", 5),
                            null,
                            new BigDecimal("0.8600"),
                            "PENDING",
                            "NOT_APPLIED",
                            null,
                            null,
                            null);

            return ApiResponse.of(response);
        }

        @GetMapping("/test/proposal-dto/applied")
        ApiResponse<ProposalResponse> appliedProposal() {
            ProposalResponse response =
                    new ProposalResponse(
                            2L,
                            12L,
                            "AI",
                            "LINK_RULE",
                            2,
                            Map.of("linkTypeId", 5),
                            Map.of(),
                            new BigDecimal("0.8600"),
                            "APPROVED",
                            "APPLIED",
                            null,
                            Instant.parse("2026-10-28T03:00:00Z"),
                            null);

            return ApiResponse.of(response);
        }

        @GetMapping("/test/proposal-dto/checked")
        ApiResponse<ProposalResponse> checkedProposal() {
            LatestCheckResponse latestCheck =
                    new LatestCheckResponse(10L, "SUCCEEDED", 85, 100, new BigDecimal("0.85"));

            ProposalResponse response =
                    new ProposalResponse(
                            3L,
                            12L,
                            "AI",
                            "LINK_RULE",
                            2,
                            Map.of("linkTypeId", 5),
                            Map.of(),
                            new BigDecimal("0.8600"),
                            "PENDING",
                            "NOT_APPLIED",
                            null,
                            null,
                            latestCheck);

            return ApiResponse.of(response);
        }

        @GetMapping("/test/proposal-dto/manual")
        ApiResponse<ProposalResponse> manualProposal() {
            ProposalResponse response =
                    new ProposalResponse(
                            4L,
                            null,
                            "MANUAL",
                            "LINK_RULE",
                            2,
                            Map.of("linkTypeId", 5),
                            Map.of(),
                            null,
                            "PENDING",
                            "NOT_APPLIED",
                            null,
                            null,
                            null);

            return ApiResponse.of(response);
        }
    }
}

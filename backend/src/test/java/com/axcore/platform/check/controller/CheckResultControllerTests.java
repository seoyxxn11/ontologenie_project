package com.axcore.platform.check.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.dto.CheckResultResponse;
import com.axcore.platform.check.dto.CheckResultSearchRequest;
import com.axcore.platform.check.service.CheckResultService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
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

/** 실제 보안 설정으로 API 062의 요청·응답·권한을 검증한다. Service 결과는 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class CheckResultControllerTests {

    private static final String PATH = "/api/v1/checks/runs/10/results";

    @Autowired MockMvc mockMvc;

    @MockitoBean CheckResultService checkResultService;

    @Test
    void returnsFilteredPageForAdmin() throws Exception {
        CheckResultSearchRequest request = new CheckResultSearchRequest(null, 30L, 1, 2);

        PageRequest pageable = PageRequest.of(1, 2, Sort.by("id").ascending());

        Page<CheckResultResponse> result = new PageImpl<>(List.of(sampleResponse()), pageable, 3);

        given(checkResultService.search(eq(10L), eq(request), eq(pageable))).willReturn(result);

        mockMvc.perform(
                        get(PATH)
                                .with(loggedIn("ADMIN"))
                                .param("proposalItemId", "30")
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(20))
                .andExpect(jsonPath("$.data[0].checkRunId").value(10))
                .andExpect(jsonPath("$.data[0].proposalItemId").value(30))
                .andExpect(jsonPath("$.data[0].proposalItemRevision").value(2))
                .andExpect(jsonPath("$.data[0].previousResultId").value(19))
                .andExpect(jsonPath("$.data[0].linkMappingId").hasJsonPath())
                .andExpect(jsonPath("$.data[0].linkMappingId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].totalCount").value(10))
                .andExpect(jsonPath("$.data[0].matchedCount").value(7))
                .andExpect(jsonPath("$.data[0].unmatchedCount").value(1))
                .andExpect(jsonPath("$.data[0].duplicateCount").value(1))
                .andExpect(jsonPath("$.data[0].violationCount").value(1))
                .andExpect(jsonPath("$.data[0].excludedTargetCount").value(2))
                .andExpect(jsonPath("$.data[0].matchRate").value(0.7))
                .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value("W23"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].transformedValue").value("E023"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].reason").value("대상 객체 없음"))
                .andExpect(jsonPath("$.data[0].aiConfidence").doesNotExist())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void bindsMappingFilter() throws Exception {
        CheckResultSearchRequest request = new CheckResultSearchRequest(600L, null, null, null);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        given(checkResultService.search(eq(10L), eq(request), eq(pageable)))
                .willReturn(Page.empty(pageable));

        mockMvc.perform(get(PATH).with(loggedIn("ADMIN")).param("linkMappingId", "600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void usesDefaultPageAndSizeWhenOmitted() throws Exception {
        CheckResultSearchRequest request = new CheckResultSearchRequest(null, null, null, null);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        given(checkResultService.search(eq(10L), eq(request), eq(pageable)))
                .willReturn(Page.empty(pageable));

        mockMvc.perform(get(PATH).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void keepsNullMatchRateWhenTotalCountIsZero() throws Exception {
        CheckResultSearchRequest request = new CheckResultSearchRequest(null, null, null, null);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        CheckResultResponse response =
                new CheckResultResponse(
                        21L, 10L, null, 30L, 2, null, 0, 0, 0, 0, 0, 0, null, List.of());

        given(checkResultService.search(eq(10L), eq(request), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(response), pageable, 1));

        mockMvc.perform(get(PATH).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].totalCount").value(0))
                .andExpect(jsonPath("$.data[0].matchRate").hasJsonPath())
                .andExpect(jsonPath("$.data[0].matchRate").value(nullValue()))
                .andExpect(jsonPath("$.data[0].sampleRows").isArray())
                .andExpect(jsonPath("$.data[0].sampleRows").isEmpty());
    }

    @Test
    void returnsNotFoundForUnknownRun() throws Exception {
        CheckResultSearchRequest request = new CheckResultSearchRequest(null, null, null, null);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        given(checkResultService.search(eq(999L), eq(request), eq(pageable)))
                .willThrow(
                        new BusinessException(
                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                Map.of("resource", "check_run", "id", 999L)));

        mockMvc.perform(get("/api/v1/checks/runs/999/results").with(loggedIn("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("check_run"))
                .andExpect(jsonPath("$.error.details.id").value(999))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "linkMappingId, 0",
        "linkMappingId, abc",
        "proposalItemId, 0",
        "proposalItemId, abc",
        "page, -1",
        "page, abc",
        "size, 0",
        "size, abc"
    })
    void rejectsInvalidQueryParameter(String field, String value) throws Exception {
        mockMvc.perform(get(PATH).with(loggedIn("ADMIN")).param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(checkResultService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidRunId(String id) throws Exception {
        mockMvc.perform(get("/api/v1/checks/runs/" + id + "/results").with(loggedIn("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(checkResultService);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        verifyNoInteractions(checkResultService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        mockMvc.perform(get(PATH).with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(checkResultService);
    }

    @Test
    void blocksAdminWhoMustChangePassword() throws Exception {
        LoginAccount account = new LoginAccount(1L, "test-admin", "ADMIN", true);

        mockMvc.perform(get(PATH).with(authentication(account.toAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(checkResultService);
    }

    private static RequestPostProcessor loggedIn(String roleCode) {
        // 실제 애플리케이션과 같은 로그인 사용자 객체를 사용한다.
        LoginAccount account = new LoginAccount(1L, "test-user", roleCode, false);

        return authentication(account.toAuthentication());
    }

    private CheckResultResponse sampleResponse() {
        return new CheckResultResponse(
                20L,
                10L,
                null,
                30L,
                2,
                19L,
                10,
                7,
                1,
                1,
                1,
                2,
                new BigDecimal("0.7000"),
                List.of(
                        Map.of(
                                "rawValue", "W23",
                                "transformedValue", "E023",
                                "classification", "UNMATCHED",
                                "reason", "대상 객체 없음")));
    }
}

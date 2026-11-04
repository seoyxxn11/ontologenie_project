package com.axcore.platform.review.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.HeldItemSearchRequest;
import com.axcore.platform.review.service.HeldItemService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class HeldItemControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean HeldItemService heldItemService;

    @Test
    void usesDefaultPageWithoutImplicitStatusFilter() throws Exception {
        HeldItemSearchRequest request = new HeldItemSearchRequest(null, null, null, null);
        PageRequest pageable = PageRequest.of(0, 20);
        given(heldItemService.search(eq(request), eq(pageable))).willReturn(Page.empty(pageable));
        mvc.perform(get("/api/v1/held-items").with(account("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void forwardsFiltersAndRequestedPage() throws Exception {
        HeldItemSearchRequest request = new HeldItemSearchRequest("AUTO_RESOLVED", 5L, 2, 3);
        PageRequest pageable = PageRequest.of(2, 3);
        given(heldItemService.search(eq(request), eq(pageable))).willReturn(Page.empty(pageable));
        mvc.perform(
                        get("/api/v1/held-items")
                                .with(account("ADMIN", false))
                                .param("status", "AUTO_RESOLVED")
                                .param("linkTypeId", "5")
                                .param("page", "2")
                                .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.size").value(3));
    }

    @ParameterizedTest
    @CsvSource({
        "status, UNKNOWN",
        "status, held",
        "status, APPROVE",
        "linkTypeId, 0",
        "linkTypeId, -1",
        "linkTypeId, abc",
        "linkTypeId, 9223372036854775808",
        "page, -1",
        "page, abc",
        "page, 2147483648",
        "size, 0",
        "size, -1",
        "size, abc"
    })
    void rejectsInvalidFiltersOrPagingBeforeQuery(String name, String value) throws Exception {
        mvc.perform(get("/api/v1/held-items").with(account("ADMIN", false)).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        verifyNoInteractions(heldItemService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidDetailIdBeforeQuery(String id) throws Exception {
        mvc.perform(get("/api/v1/held-items/" + id).with(account("ADMIN", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(heldItemService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/held-items", "/api/v1/held-items/1"})
    void requiresAuthentication(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        verifyNoInteractions(heldItemService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRolesBeforeQuery(String role) throws Exception {
        for (String path : List.of("/api/v1/held-items", "/api/v1/held-items/1")) {
            mvc.perform(get(path).with(account(role, false)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        }
        verifyNoInteractions(heldItemService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/held-items", "/api/v1/held-items/1"})
    void blocksAdminWhoMustChangePassword(String path) throws Exception {
        mvc.perform(get(path).with(account("ADMIN", true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        verifyNoInteractions(heldItemService);
    }

    private RequestPostProcessor account(String role, boolean mustChangePassword) {
        return authentication(
                new LoginAccount(1L, "held-reader", role, mustChangePassword).toAuthentication());
    }
}

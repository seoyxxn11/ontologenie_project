package com.axcore.platform.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 역할 및 권한 목록(API 008)을 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AdminRoleApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void listsFourRolesInOrderWithPermissionCodes() throws Exception {
        Cookie session = login("ADMIN");

        mockMvc.perform(get(ROLES).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].code").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].name").value("온톨로지 관리자"))
                .andExpect(jsonPath("$.data[0].permissions[0]").value("ACCOUNT_MANAGE"))
                .andExpect(jsonPath("$.data[1].code").value("PLANNER"))
                .andExpect(jsonPath("$.data[2].code").value("MAINTENANCE"))
                .andExpect(jsonPath("$.data[3].code").value("VIEWER"))
                .andExpect(jsonPath("$.data[3].permissions").isArray())
                .andExpect(jsonPath("$.data[3].permissions.length()").value(0))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void forbidsNonAdmin() throws Exception {
        Cookie session = login("PLANNER");

        mockMvc.perform(get(ROLES).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get(ROLES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    private Cookie login(String roleCode) throws Exception {
        String loginId =
                roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcClient
                .sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id,
                                             must_change_password)
                        values (:loginId, '테스트 사용자', :hash,
                                (select id from role where code = :roleCode), false)
                        """)
                .param("loginId", loginId)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("roleCode", roleCode)
                .update();
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                .formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
    }
}

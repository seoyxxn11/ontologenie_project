package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 비밀번호 변경(API 003)을 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class PasswordChangeApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String NEW_PASSWORD = "N3w-Passw0rd";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void changesPasswordAndEndsTemporaryState() throws Exception {
        TestAccount account = createAccount(true);
        LoginSession session = login(account.loginId(), PASSWORD);

        changePassword(session, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
                                select must_change_password, password_changed_at is not null as changed,
                                       updated_by, row_version
                                from account where id = :id
                                """)
                        .param("id", account.id())
                        .query()
                        .singleRow();
        assertThat(row.get("must_change_password")).isEqualTo(false);
        assertThat(row.get("changed")).isEqualTo(true);
        assertThat(row.get("updated_by")).as("공통 Entity가 로그인 계정을 수정자로 남긴다").isEqualTo(account.id());
        assertThat(row.get("row_version")).isEqualTo(1);
        loginRequest(account.loginId(), PASSWORD).andExpect(status().isUnauthorized());
        loginRequest(account.loginId(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void unlocksBusinessApiInSameSession() throws Exception {
        TestAccount account = createAccount(true);
        LoginSession session = login(account.loginId(), PASSWORD);
        mockMvc.perform(get("/api/v1/not-implemented").cookie(session.session()))
                .andExpect(status().isForbidden());

        changePassword(session, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        // 인가를 통과해 실제 경로 처리(없는 경로라 404)까지 간다.
        mockMvc.perform(get("/api/v1/not-implemented").cookie(session.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/auth/me").cookie(session.session()))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    void expiresOtherSessionsButKeepsCurrent() throws Exception {
        TestAccount account = createAccount(false);
        LoginSession current = login(account.loginId(), PASSWORD);
        LoginSession other = login(account.loginId(), PASSWORD);

        changePassword(current, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").cookie(current.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(other.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongCurrentPasswordWithoutChange() throws Exception {
        TestAccount account = createAccount(false);
        LoginSession session = login(account.loginId(), PASSWORD);

        changePassword(session, "wrong-password", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("currentPassword"))
                .andExpect(jsonPath("$.error.details.reason").value("현재 비밀번호가 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details.errors[0].field").value("currentPassword"));

        loginRequest(account.loginId(), PASSWORD).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(session.session()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Ab1!xyz | 8자 이상이어야 합니다.",
                "Abcdefgh1 | 영문·숫자·특수문자를 모두 포함해야 합니다.",
                "Abcdefgh! | 영문·숫자·특수문자를 모두 포함해야 합니다.",
                "12345678! | 영문·숫자·특수문자를 모두 포함해야 합니다.",
                "Passw0rd! | 현재 비밀번호와 같을 수 없습니다."
            })
    void rejectsNewPasswordAgainstPolicy(String newPassword, String reason) throws Exception {
        TestAccount account = createAccount(true);
        LoginSession session = login(account.loginId(), PASSWORD);

        changePassword(session, PASSWORD, newPassword)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("newPassword"))
                .andExpect(jsonPath("$.error.details.reason").value(reason));

        assertThat(mustChangePassword(account.id())).isTrue();
    }

    @Test
    void rejectsPasswordSameAsLoginId() throws Exception {
        String loginId = "user1!" + UUID.randomUUID().toString().substring(0, 8);
        TestAccount account = createAccount(loginId, false);
        LoginSession session = login(account.loginId(), PASSWORD);

        changePassword(session, PASSWORD, loginId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.reason").value("로그인 ID와 같을 수 없습니다."));
    }

    @Test
    void rejectsPasswordLongerThanBcryptLimit() throws Exception {
        TestAccount account = createAccount(false);
        LoginSession session = login(account.loginId(), PASSWORD);

        changePassword(session, PASSWORD, "A1!" + "a".repeat(70))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.reason").value("72바이트(영문 72자)를 넘을 수 없습니다."));
    }

    @Test
    void rejectsMissingNewPassword() throws Exception {
        TestAccount account = createAccount(false);
        LoginSession session = login(account.loginId(), PASSWORD);

        mockMvc.perform(
                        patch("/api/v1/auth/password")
                                .cookie(session.session(), session.csrf())
                                .header("X-XSRF-TOKEN", session.csrf().getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("newPassword"));
    }

    @Test
    void requiresLoginAndCsrfToken() throws Exception {
        Cookie csrf = new Cookie("XSRF-TOKEN", "test-token");
        mockMvc.perform(
                        patch("/api/v1/auth/password")
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());

        TestAccount account = createAccount(false);
        LoginSession session = login(account.loginId(), PASSWORD);
        mockMvc.perform(
                        patch("/api/v1/auth/password")
                                .cookie(session.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private ResultActions changePassword(
            LoginSession session, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(
                patch("/api/v1/auth/password")
                        .cookie(session.session(), session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(currentPassword, newPassword)));
    }

    private static String body(String currentPassword, String newPassword) {
        return "{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}"
                .formatted(currentPassword, newPassword);
    }

    private ResultActions loginRequest(String loginId, String password) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                        .formatted(loginId, password)));
    }

    private LoginSession login(String loginId, String password) throws Exception {
        MvcResult result = loginRequest(loginId, password).andExpect(status().isOk()).andReturn();
        return new LoginSession(
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private TestAccount createAccount(boolean mustChangePassword) {
        return createAccount("user-" + UUID.randomUUID(), mustChangePassword);
    }

    private TestAccount createAccount(String loginId, boolean mustChangePassword) {
        Long id =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '테스트 사용자', :hash,
                                        (select id from role where code = 'VIEWER'),
                                        :mustChangePassword)
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("hash", passwordEncoder.encode(PASSWORD))
                        .param("mustChangePassword", mustChangePassword)
                        .query(Long.class)
                        .single();
        return new TestAccount(id, loginId);
    }

    private boolean mustChangePassword(Long accountId) {
        return jdbcClient
                .sql("select must_change_password from account where id = :id")
                .param("id", accountId)
                .query(Boolean.class)
                .single();
    }

    private record TestAccount(Long id, String loginId) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

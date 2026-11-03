package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.AccountSessionService;
import com.axcore.platform.global.config.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 로그인·내 계정·로그아웃(API 001·002·099)과 세션·CSRF 규칙을 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AuthApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String INVALID_CREDENTIALS = "로그인 ID 또는 비밀번호가 올바르지 않습니다.";
    private static final String INACTIVE_ACCOUNT = "잠기거나 비활성화된 계정입니다. 관리자에게 문의해 주세요.";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired AccountSessionService accountSessionService;

    @Test
    void loginIssuesSessionAndCsrfCookies() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);

        MvcResult result =
                login(account.loginId(), PASSWORD)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(account.id()))
                        .andExpect(jsonPath("$.data.loginId").value(account.loginId()))
                        .andExpect(jsonPath("$.data.roleCode").value("VIEWER"))
                        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                        .andExpect(jsonPath("$.data.rowVersion").value(0))
                        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                        .andReturn();

        MockCookie session = (MockCookie) result.getResponse().getCookie("SESSION");
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getSecure()).isTrue();
        assertThat(session.getSameSite()).isEqualTo("Lax");
        // 화면이 값을 읽어 헤더에 넣어야 해서 HttpOnly를 붙이지 않는다.
        Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select principal_name || ':' || max_inactive_interval
                                        from spring_session where session_id = :id
                                        """)
                                .param("id", sessionId(sessionCookie(result)))
                                .query(String.class)
                                .single())
                .as("세션은 DB에 저장되고 30분 동안 요청이 없으면 만료된다")
                .isEqualTo(account.id() + ":1800");
        assertThat(
                        jdbcClient
                                .sql("select last_login_at is not null from account where id = :id")
                                .param("id", account.id())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void rejectsWrongPasswordWithSameMessageAsUnknownLoginId() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);

        MvcResult result =
                login(account.loginId(), "wrong-password")
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                        .andExpect(jsonPath("$.error.message").value(INVALID_CREDENTIALS))
                        .andReturn();
        login("no-such-" + UUID.randomUUID(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(INVALID_CREDENTIALS));

        assertThat(result.getResponse().getCookie("SESSION")).isNull();
        assertThat(failedLoginCount(account.id())).isEqualTo(1);
    }

    @Test
    void locksAccountAfterFiveFailuresAndResetsCountOnSuccess() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        TestAccount other = createAccount("VIEWER", "ACTIVE", false);

        for (int i = 0; i < 5; i++) {
            login(account.loginId(), "wrong-password").andExpect(status().isUnauthorized());
        }
        login(account.loginId(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(INACTIVE_ACCOUNT));
        assertThat(accountStatus(account.id())).isEqualTo("LOCKED");

        login(other.loginId(), "wrong-password").andExpect(status().isUnauthorized());
        login(other.loginId(), PASSWORD).andExpect(status().isOk());
        assertThat(failedLoginCount(other.id())).isZero();
    }

    @Test
    void rejectsDisabledAccountEvenWithCorrectPassword() throws Exception {
        TestAccount account = createAccount("VIEWER", "DISABLED", false);

        login(account.loginId(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(INACTIVE_ACCOUNT));
    }

    @Test
    void rejectsMissingLoginId() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("loginId"));
    }

    @Test
    void meRequiresSessionInCommonErrorFormat() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/auth/me"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                        .andReturn();

        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains(requestId);
    }

    @Test
    void meReturnsLoggedInAccount() throws Exception {
        TestAccount account = createAccount("PLANNER", "ACTIVE", false);
        Cookie session = sessionCookie(login(account.loginId(), PASSWORD).andReturn());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(account.id()))
                .andExpect(jsonPath("$.data.roleCode").value("PLANNER"));
    }

    @Test
    void loginReplacesExistingSessionId() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        Cookie first = sessionCookie(login(account.loginId(), PASSWORD).andReturn());

        Cookie second =
                sessionCookie(
                        mockMvc.perform(loginRequest(account.loginId(), PASSWORD).cookie(first))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mockMvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void logoutRequiresCsrfToken() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        Cookie session = sessionCookie(login(account.loginId(), PASSWORD).andReturn());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("CSRF 토큰이 없거나 올바르지 않습니다."));
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void logoutDeletesOnlyThisSession() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        MvcResult loggedIn = login(account.loginId(), PASSWORD).andReturn();
        Cookie session = sessionCookie(loggedIn);
        Cookie otherSession = sessionCookie(login(account.loginId(), PASSWORD).andReturn());
        Cookie csrf = loggedIn.getResponse().getCookie("XSRF-TOKEN");

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/logout")
                                        .cookie(session, csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue()))
                        .andExpect(status().isNoContent())
                        .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(
                        cookie -> assertThat(cookie).startsWith("SESSION=").contains("Max-Age=0"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(otherSession)).andExpect(status().isOk());
        assertThat(sessionCount(account.id())).isEqualTo(1);
    }

    @Test
    void logoutWithoutSessionIsUnauthenticated() throws Exception {
        Cookie csrf = new Cookie("XSRF-TOKEN", "test-token");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void blocksBusinessApiUntilPasswordChanged() throws Exception {
        TestAccount temporary = createAccount("VIEWER", "ACTIVE", true);
        TestAccount changed = createAccount("VIEWER", "ACTIVE", false);
        Cookie temporarySession =
                sessionCookie(
                        login(temporary.loginId(), PASSWORD)
                                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                                .andReturn());
        Cookie changedSession = sessionCookie(login(changed.loginId(), PASSWORD).andReturn());

        mockMvc.perform(get("/api/v1/not-implemented").cookie(temporarySession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("비밀번호를 변경한 뒤 이용할 수 있습니다."));
        mockMvc.perform(get("/api/v1/auth/me").cookie(temporarySession)).andExpect(status().isOk());
        // 비밀번호를 바꾼 계정은 인가를 통과해 실제 경로 처리(없는 경로라 404)까지 간다.
        mockMvc.perform(get("/api/v1/not-implemented").cookie(changedSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiresAllSessionsOfAccount() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        Cookie first = sessionCookie(login(account.loginId(), PASSWORD).andReturn());
        Cookie second = sessionCookie(login(account.loginId(), PASSWORD).andReturn());

        accountSessionService.expireAll(account.id());

        assertThat(sessionCount(account.id())).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiresOtherSessionsButKeepsCurrent() throws Exception {
        TestAccount account = createAccount("VIEWER", "ACTIVE", false);
        Cookie current = sessionCookie(login(account.loginId(), PASSWORD).andReturn());
        Cookie other = sessionCookie(login(account.loginId(), PASSWORD).andReturn());

        accountSessionService.expireOthers(account.id(), sessionId(current));

        mockMvc.perform(get("/api/v1/auth/me").cookie(current)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(other)).andExpect(status().isUnauthorized());
    }

    private ResultActions login(String loginId, String password) throws Exception {
        return mockMvc.perform(loginRequest(loginId, password));
    }

    private static MockHttpServletRequestBuilder loginRequest(String loginId, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"loginId\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password));
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).as("SESSION 쿠키").isNotNull();
        return cookie;
    }

    /** 세션 쿠키 값은 세션 ID를 Base64로 바꾼 값이다. */
    private static String sessionId(Cookie sessionCookie) {
        return new String(
                Base64.getDecoder().decode(sessionCookie.getValue()), StandardCharsets.UTF_8);
    }

    private TestAccount createAccount(String roleCode, String status, boolean mustChangePassword) {
        String loginId = "user-" + UUID.randomUUID();
        Long id =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     status, must_change_password)
                                values (:loginId, '테스트 사용자', :hash,
                                        (select id from role where code = :roleCode),
                                        :status, :mustChangePassword)
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("hash", passwordEncoder.encode(PASSWORD))
                        .param("roleCode", roleCode)
                        .param("status", status)
                        .param("mustChangePassword", mustChangePassword)
                        .query(Long.class)
                        .single();
        return new TestAccount(id, loginId);
    }

    private int failedLoginCount(Long accountId) {
        return jdbcClient
                .sql("select failed_login_count from account where id = :id")
                .param("id", accountId)
                .query(Integer.class)
                .single();
    }

    private String accountStatus(Long accountId) {
        return jdbcClient
                .sql("select status from account where id = :id")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private int sessionCount(Long accountId) {
        return jdbcClient
                .sql("select count(*) from spring_session where principal_name = :name")
                .param("name", String.valueOf(accountId))
                .query(Integer.class)
                .single();
    }

    private record TestAccount(Long id, String loginId) {}
}

package com.axcore.platform.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내부 API(/api/v1/internal/**)의 서비스 토큰 인증(API 105·043)을 실제 보안 설정으로 확인한다. */
// 테스트 클래스 안의 컨트롤러는 컴포넌트 스캔에서 빠지므로 직접 등록한다.
@SpringBootTest(properties = "axcore.internal-api.token=" + InternalApiAuthTests.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, InternalApiAuthTests.TestInternalController.class})
class InternalApiAuthTests {

    static final String TOKEN = "test-internal-token";
    private static final String PATH = "/api/v1/internal/test-auth";
    private static final String PASSWORD = "Passw0rd!";
    private static final String UNAUTHENTICATED_MESSAGE = "서비스 자격 증명이 없거나 유효하지 않습니다.";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void acceptsValidTokenAsInternalServiceWithoutCsrfOrSession() throws Exception {
        MvcResult result =
                mockMvc.perform(post(PATH).header(InternalApiTokenFilter.HEADER, TOKEN))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").value(InternalApiTokenFilter.PRINCIPAL))
                        .andReturn();
        mockMvc.perform(get(PATH).header(InternalApiTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk());

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .as("내부 호출은 세션·CSRF 쿠키를 만들지 않는다")
                .isEmpty();
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        mockMvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").value(UNAUTHENTICATED_MESSAGE))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        mockMvc.perform(post(PATH).header(InternalApiTokenFilter.HEADER, TOKEN + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(UNAUTHENTICATED_MESSAGE));
        mockMvc.perform(get(PATH).header(InternalApiTokenFilter.HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUserSessionWithoutToken() throws Exception {
        MvcResult login = login();
        Cookie session = login.getResponse().getCookie("SESSION");
        Cookie csrf = login.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post(PATH).cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("내부 API는 사용자 세션으로 호출할 수 없습니다."));
        // 세션은 그대로 살아 있고 일반 API는 계속 쓸 수 있다.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void tokenDoesNotOpenOtherApis() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header(InternalApiTokenFilter.HEADER, TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("인증 정보가 없거나 유효하지 않습니다."));
    }

    private MvcResult login() throws Exception {
        String loginId = "user-" + UUID.randomUUID();
        jdbcClient
                .sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id,
                                             status, must_change_password)
                        values (:loginId, '테스트 사용자', :hash,
                                (select id from role where code = 'VIEWER'), 'ACTIVE', false)
                        """)
                .param("loginId", loginId)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .update();
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                .formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @RestController
    static class TestInternalController {

        @GetMapping(PATH)
        ApiResponse<String> read(Authentication authentication) {
            return ApiResponse.of(authentication.getName());
        }

        @PostMapping(PATH)
        ApiResponse<String> write(Authentication authentication) {
            return ApiResponse.of(authentication.getName());
        }
    }
}

package com.axcore.platform.source;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 원천 API 테스트가 함께 쓰는 로그인·요청·원천 등록 도우미. */
abstract class SourceApiTestSupport {

    static final String BASE_PATH = "/api/v1/sources";

    /** 원천 접속 비밀번호 암호화 키(테스트 전용, 32바이트 base64). 원천 API 테스트 클래스가 모두 같은 값을 써 Spring 컨텍스트를 함께 쓴다. */
    static final String CREDENTIAL_KEY =
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE=";

    static final String SYNC_PASSWORD = "sync-Pa55!";
    static final String AI_PASSWORD = "ai-Pa55!";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    ResultActions send(LoginSession session, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.cookie(session.session(), session.csrf())
                .header("X-XSRF-TOKEN", session.csrf().getValue());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    /** API 010으로 POSTGRES 원천을 등록하고 ID를 돌려준다. */
    long createPostgresSource(LoginSession admin, String code) throws Exception {
        MvcResult result =
                send(admin, post(BASE_PATH), postgresBody(code))
                        .andExpect(status().isCreated())
                        .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
                .longValue();
    }

    static String postgresBody(String code) {
        return """
                {"code": "%s", "name": "생산 ERP", "sourceKind": "POSTGRES",
                 "connectionConfig": {"host": "10.0.0.5", "port": 15432, "database": "erp"},
                 "credentials": [
                   {"purpose": "AI", "username": "platform_ai", "password": "%s"},
                   {"purpose": "SYNC", "username": "platform_ro", "password": "%s"}]}
                """
                .formatted(code, AI_PASSWORD, SYNC_PASSWORD);
    }

    static String uniqueCode() {
        return "SRC_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    LoginSession loginAs(String roleCode) throws Exception {
        String loginId = "user-" + UUID.randomUUID();
        Long accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '테스트 사용자', :hash,
                                        (select id from role where code = :roleCode), false)
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("hash", passwordEncoder.encode(PASSWORD))
                        .param("roleCode", roleCode)
                        .query(Long.class)
                        .single();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                        .formatted(loginId, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        return new LoginSession(
                accountId,
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    record LoginSession(long accountId, Cookie session, Cookie csrf) {}
}

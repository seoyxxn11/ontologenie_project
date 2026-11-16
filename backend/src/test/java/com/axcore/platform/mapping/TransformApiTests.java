package com.axcore.platform.mapping;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.MvcResult;

/** 허용 변환 함수 목록(API 041)을 실제 보안 설정으로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class TransformApiTests {

    private static final String PATH = "/api/v1/ontology/transforms";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void listsTransformFunctions() throws Exception {
        Cookie admin = loginAs("ADMIN");

        mockMvc.perform(get(PATH).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(11)))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.data[0].op").value("STRIP_PREFIX"))
                .andExpect(jsonPath("$.data[0].label").value("접두어 빼기"))
                .andExpect(jsonPath("$.data[0].argument.name").value("prefix"))
                .andExpect(jsonPath("$.data[0].argument.type").value("string"))
                .andExpect(jsonPath("$.data[0].inputTypes[0]").value("STRING"))
                .andExpect(jsonPath("$.data[0].outputType").value("STRING"))
                .andExpect(jsonPath("$.data[0].example.step.op").value("STRIP_PREFIX"))
                .andExpect(jsonPath("$.data[0].example.step.arg").value("W"))
                .andExpect(jsonPath("$.data[0].example.input").value("W23"))
                .andExpect(jsonPath("$.data[0].example.output").value("23"))
                // 인자가 없는 함수도 같은 모양으로 null을 내보낸다.
                .andExpect(jsonPath("$.data[3].op").value("UPPER"))
                .andExpect(jsonPath("$.data[3].argument").value(nullValue()))
                .andExpect(jsonPath("$.data[3].example.step.arg").value(nullValue()))
                .andExpect(jsonPath("$.data[9].op").value("MAP_VALUE"))
                .andExpect(jsonPath("$.data[9].argument.type").value("object"))
                .andExpect(jsonPath("$.data[9].example.step.arg.RUN").value("가동"))
                .andExpect(jsonPath("$.data[10].op").value("MAP_KNOWN"))
                .andExpect(jsonPath("$.data[10].argument.type").value("object"));
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        Cookie viewer = loginAs("VIEWER");

        mockMvc.perform(get(PATH).cookie(viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    private Cookie loginAs(String roleCode) throws Exception {
        String loginId = "user-" + UUID.randomUUID();
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
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                        .formatted(loginId, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        return result.getResponse().getCookie("SESSION");
    }
}

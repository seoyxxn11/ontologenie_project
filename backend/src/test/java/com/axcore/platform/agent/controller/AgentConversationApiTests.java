package com.axcore.platform.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "test.context=agent-conversation")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentConversationApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    long owner;
    long other;

    @BeforeEach
    void accounts() {
        owner = account();
        other = account();
    }

    @Test
    void createsEmptyConversationOwnedByLoginUser() throws Exception {
        String body =
                mvc.perform(post("/api/v1/agent/conversations").with(loggedIn(owner)).with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.title").value(nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode data = json.readTree(body).path("data");
        assertThat(data.path("lastMessageAt")).isEqualTo(data.path("startedAt"));
        long id = data.path("id").asLong();
        assertThat(
                        jdbc.sql("select account_id from agent_conversation where id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(owner);
    }

    @Test
    void listsOnlyOwnConversationsByLatestMessage() throws Exception {
        long oldest = conversation(owner, "2026-10-25T00:00:00Z");
        long latest = conversation(owner, "2026-10-27T00:00:00Z");
        long middle = conversation(owner, "2026-10-26T00:00:00Z");
        conversation(other, "2026-10-28T00:00:00Z");

        mvc.perform(get("/api/v1/agent/conversations").param("size", "2").with(loggedIn(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(latest))
                .andExpect(jsonPath("$.data[1].id").value(middle))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        mvc.perform(
                        get("/api/v1/agent/conversations")
                                .param("page", "1")
                                .param("size", "2")
                                .with(loggedIn(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(oldest));
    }

    @Test
    void returnsOwnConversationDetail() throws Exception {
        long id = conversation(owner, "2026-10-27T00:05:00Z");

        mvc.perform(get("/api/v1/agent/conversations/{id}", id).with(loggedIn(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.title").value("설비 PR-03의 최근 비가동 이력을 보여줘"))
                .andExpect(jsonPath("$.data.lastMessageAt").value("2026-10-27T00:05:00Z"));
    }

    @Test
    void hidesOtherUsersConversationAsNotFound() throws Exception {
        long othersConversation = conversation(other, "2026-10-27T00:00:00Z");

        mvc.perform(
                        get("/api/v1/agent/conversations/{id}", othersConversation)
                                .with(loggedIn(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/agent/conversations/{id}", Long.MAX_VALUE).with(loggedIn(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void requiresLoginAndCsrf() throws Exception {
        mvc.perform(get("/api/v1/agent/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/agent/conversations").with(loggedIn(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsOutOfRangePagingAndId() throws Exception {
        mvc.perform(get("/api/v1/agent/conversations").param("page", "-1").with(loggedIn(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agent/conversations").param("size", "0").with(loggedIn(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agent/conversations/{id}", 0).with(loggedIn(owner)))
                .andExpect(status().isBadRequest());
    }

    private long account() {
        return jdbc.sql(
                        """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '대화 사용자', 'test-hash', (select id from role where code='VIEWER'), false) returning id
                """)
                .param("login", "agent-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private long conversation(long accountId, String lastMessageAt) {
        OffsetDateTime at = OffsetDateTime.parse(lastMessageAt);
        return jdbc.sql(
                        """
                insert into agent_conversation(account_id, title, started_at, last_message_at)
                values (:account, '설비 PR-03의 최근 비가동 이력을 보여줘', :at, :at) returning id
                """)
                .param("account", accountId)
                .param("at", at)
                .query(Long.class)
                .single();
    }

    private static RequestPostProcessor loggedIn(long accountId) {
        return authentication(
                new LoginAccount(accountId, "agent-user", "VIEWER", false).toAuthentication());
    }
}

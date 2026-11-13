package com.axcore.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 민감 값 열람 기록(S15P31C106-394)을 실제 보안 설정과 DB로 확인한다. 민감 값을 볼 수 있는 역할(PLANNER)이 가리지 않은 값을 받으면 접속 기록이
 * 남고, 가려졌거나 값이 비었으면 남지 않는다. 기록에는 값 원문이 없고, ADMIN이 096에서 골라 볼 때만 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class RestrictedViewLogApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String SECRET = "T-KIM-394";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbc;

    @Autowired PasswordEncoder passwordEncoder;

    private long typeId;
    private long shown;
    private long empty;
    private long planner;
    private long viewer;
    private long admin;

    @BeforeEach
    void setUp() {
        String group = "VIEWLOG_" + UUID.randomUUID().toString().substring(0, 8);
        allow("PLANNER", group, true);
        allow("VIEWER", group, false);
        allow("ADMIN", group, false);
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name, access_group) values (:code, '정비 지시', :group) returning id")
                        .param("code", "MO_" + UUID.randomUUID().toString().substring(0, 8))
                        .param("group", group)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, 'order_no', '지시 번호', 'STRING', 'NORMAL'),
                               (:type, 'technician', '정비 담당', 'STRING', 'RESTRICTED')
                        """)
                .param("type", typeId)
                .update();
        shown = object("MO-1", SECRET);
        empty = object("MO-2", null);
        planner = account("PLANNER");
        viewer = account("VIEWER");
        admin = account("ADMIN");
    }

    @Test
    void recordsShownRestrictedFieldsWithoutValues() throws Exception {
        Cookie session = login(planner);
        mockMvc.perform(get("/api/v1/objects/" + shown).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties.technician").value(SECRET));

        List<Map<String, Object>> logs = logsOf(planner);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.getFirst();
        assertThat(log)
                .containsEntry("target_type", "object_instance")
                .containsEntry("target_id", shown)
                .containsEntry("entry_point", "WEB")
                .containsEntry("actor_role_code", "PLANNER");
        assertThat((String) log.get("result"))
                .contains("OBJECT_DETAIL", "technician", "policyVersion")
                .doesNotContain(SECRET);
        assertThat((String) log.get("input")).doesNotContain(SECRET);
    }

    @Test
    void recordsOnlyObjectsWhoseRestrictedValueWasShownInAList() throws Exception {
        Cookie session = login(planner);
        mockMvc.perform(get("/api/v1/objects").param("objectTypeId", "" + typeId).cookie(session))
                .andExpect(status().isOk());

        List<Map<String, Object>> logs = logsOf(planner);
        assertThat(logs).hasSize(1);
        // 값을 보여 준 객체가 하나뿐이라 그 객체가 대상이 된다. 값이 빈 객체는 남기지 않는다.
        assertThat(logs.getFirst()).containsEntry("target_id", shown);
        assertThat((String) logs.getFirst().get("result"))
                .contains("OBJECT_SEARCH", "\"targetId\": " + shown)
                .doesNotContain("\"targetId\": " + empty)
                .doesNotContain(SECRET);
    }

    @Test
    void recordsNothingWhenValuesAreMaskedOrEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/objects/" + shown).cookie(login(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties.technician").doesNotExist());
        mockMvc.perform(get("/api/v1/objects/" + empty).cookie(login(planner)))
                .andExpect(status().isOk());

        assertThat(logsOf(viewer)).isEmpty();
        assertThat(logsOf(planner)).isEmpty();
    }

    /** 열람 기록은 ADMIN이 096에서 eventKind로 골라 볼 때만 보인다. 본인 기록이어도 다른 역할에는 열지 않는다. */
    @Test
    void onlyAdminSeesTheLogWhenAskingForIt() throws Exception {
        Cookie plannerSession = login(planner);
        mockMvc.perform(get("/api/v1/objects/" + shown).cookie(plannerSession))
                .andExpect(status().isOk());
        long logId = ((Number) logsOf(planner).getFirst().get("id")).longValue();
        int id = Math.toIntExact(logId);

        mockMvc.perform(
                        get("/api/v1/audit/actions")
                                .param("eventKind", "RESTRICTED_VIEW")
                                .cookie(plannerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit/actions").param("size", "100").cookie(plannerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        mockMvc.perform(get("/api/v1/audit/actions/" + logId).cookie(plannerSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/action-executions/" + logId).cookie(plannerSession))
                .andExpect(status().isNotFound());

        Cookie adminSession = login(admin);
        mockMvc.perform(
                        get("/api/v1/audit/actions")
                                .param("actorId", "" + planner)
                                .param("size", "100")
                                .cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        mockMvc.perform(
                        get("/api/v1/objects/" + shown + "/executions")
                                .param("size", "100")
                                .cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        mockMvc.perform(
                        get("/api/v1/audit/actions")
                                .param("eventKind", "RESTRICTED_VIEW")
                                .param("actorId", "" + planner)
                                .cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(id)));
        mockMvc.perform(get("/api/v1/audit/actions/" + logId).cookie(adminSession))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> logsOf(long accountId) {
        return jdbc.sql(
                        """
                        select id, target_type, target_id, entry_point, actor_role_code,
                               input::text as input, result::text as result
                        from action_execution
                        where actor_id = :actor and event_kind = 'RESTRICTED_VIEW'
                        order by id
                        """)
                .param("actor", accountId)
                .query()
                .listOfRows();
    }

    private void allow(String role, String group, boolean restricted) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        values ((select id from role where code = :role), :group, :restricted)
                        """)
                .param("role", role)
                .param("group", group)
                .param("restricted", restricted)
                .update();
    }

    private long object(String orderNo, String technician) {
        String properties =
                technician == null
                        ? "{\"order_no\": \"%s\", \"technician\": null}".formatted(orderNo)
                        : "{\"order_no\": \"%s\", \"technician\": \"%s\"}"
                                .formatted(orderNo, technician);
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", typeId)
                .param("name", orderNo)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }

    private long account(String role) {
        String loginId = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                        values (:loginId, '열람 기록', :hash, (select id from role where code = :role), false)
                        returning id
                        """)
                .param("loginId", loginId)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private Cookie login(long accountId) throws Exception {
        String loginId =
                jdbc.sql("select login_id from account where id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .single();
        MockHttpServletRequestBuilder request =
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                        .formatted(loginId, PASSWORD));
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
    }
}

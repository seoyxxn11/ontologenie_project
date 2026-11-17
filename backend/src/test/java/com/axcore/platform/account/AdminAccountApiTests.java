package com.axcore.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** 관리자 계정 조회·생성·변경(API 004~007)을 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AdminAccountApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String TEMPORARY_PASSWORD = "Temp-Pass1";
    private static final String ACCOUNTS = "/api/v1/admin/accounts";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------- 005 생성

    @Test
    void createsAccountWithTemporaryPasswordAndRecordsAction() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);
        String loginId = uniqueLoginId("planner");

        MvcResult result =
                create(session, loginId, "생산기획 담당", "PLANNER", TEMPORARY_PASSWORD)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.loginId").value(loginId))
                        .andExpect(jsonPath("$.data.displayName").value("생산기획 담당"))
                        .andExpect(jsonPath("$.data.roleCode").value("PLANNER"))
                        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                        .andExpect(jsonPath("$.data.rowVersion").value(0))
                        .andExpect(jsonPath("$.data.temporaryPassword").doesNotExist())
                        .andReturn();
        long id = idOf(result);

        Map<String, Object> row =
                jdbcClient
                        .sql(
                                "select password_hash, created_by, updated_by from account where id = :id")
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(passwordEncoder.matches(TEMPORARY_PASSWORD, (String) row.get("password_hash")))
                .as("임시 비밀번호는 해시로만 저장한다")
                .isTrue();
        assertThat(row.get("created_by")).isEqualTo(admin.id());
        assertThat(row.get("updated_by")).isEqualTo(admin.id());

        Map<String, Object> audit = lastAudit("CREATE_ACCOUNT", admin.id());
        assertThat(audit)
                .containsEntry("event_kind", "ACTION")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_role_code", "ADMIN")
                .containsEntry("entry_point", "WEB")
                .containsEntry("checked_permission_code", "ACCOUNT_MANAGE")
                .containsEntry("target_type", "account")
                .containsEntry("target_id", id);
        assertThat((String) audit.get("input"))
                .contains(loginId)
                .doesNotContain(TEMPORARY_PASSWORD)
                .doesNotContainIgnoringCase("password");
        List<Map<String, Object>> changes = changesOf((Long) audit.get("id"));
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst())
                .containsEntry("table_name", "account")
                .containsEntry("change_kind", "INSERT");
        assertThat((String) changes.getFirst().get("after_data"))
                .contains(loginId)
                .doesNotContain("password_hash");

        // 새 사용자는 임시 비밀번호로 로그인하고, 비밀번호를 바꿔야 하는 상태다.
        loginRequest(loginId, TEMPORARY_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    }

    @Test
    void rejectsDuplicateLoginIdAndRecordsBlocked() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount existing = createAccount("VIEWER");
        LoginSession session = login(admin);

        create(session, existing.loginId(), "중복", "VIEWER", TEMPORARY_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("같은 식별자로 등록된 항목이 있습니다."))
                .andExpect(jsonPath("$.error.details.field").value("loginId"));

        Map<String, Object> audit = lastAudit("CREATE_ACCOUNT", admin.id());
        assertThat(audit).containsEntry("status", "BLOCKED").containsEntry("target_id", null);
        assertThat((String) audit.get("blocked_reason")).contains("같은 식별자");
        assertThat(changesOf((Long) audit.get("id"))).isEmpty();
    }

    @Test
    void rejectsTemporaryPasswordAgainstPolicy() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);
        String loginId = uniqueLoginId("weak");

        create(session, loginId, "약한 비밀번호", "VIEWER", "abcdefgh")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("temporaryPassword"))
                .andExpect(jsonPath("$.error.details.reason").value("영문·숫자·특수문자를 모두 포함해야 합니다."));

        assertThat(countAccounts(loginId)).isZero();
        Map<String, Object> audit = lastAudit("CREATE_ACCOUNT", admin.id());
        assertThat(audit).containsEntry("status", "BLOCKED");
        assertThat((String) audit.get("blocked_reason")).contains("영문·숫자·특수문자");
        assertThat((String) audit.get("input")).doesNotContain("abcdefgh");
    }

    @Test
    void rejectsMissingOrInvalidFields() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);

        write(post(ACCOUNTS), session, "{\"loginId\": \"only-id\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        create(session, uniqueLoginId("role"), "역할 오류", "OWNER", TEMPORARY_PASSWORD)
                .andExpect(status().isBadRequest());
        create(session, "a".repeat(101), "긴 ID", "VIEWER", TEMPORARY_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("loginId"));
    }

    @Test
    void forbidsNonAdminAndRecordsBlocked() throws Exception {
        TestAccount planner = createAccount("PLANNER");
        LoginSession session = login(planner);

        create(session, uniqueLoginId("x"), "권한 없음", "VIEWER", TEMPORARY_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ACCOUNT_MANAGE"));
        mockMvc.perform(get(ACCOUNTS).cookie(session.session())).andExpect(status().isForbidden());
        mockMvc.perform(get(ACCOUNTS + "/" + planner.id()).cookie(session.session()))
                .andExpect(status().isForbidden());
        update(session, planner.id(), "{\"roleCode\": \"ADMIN\", \"rowVersion\": 0}")
                .andExpect(status().isForbidden());

        assertThat(lastAudit("CREATE_ACCOUNT", planner.id()))
                .containsEntry("status", "BLOCKED")
                .containsEntry("actor_role_code", "PLANNER");
        assertThat(lastAudit("UPDATE_ACCOUNT", planner.id()))
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_id", planner.id());
        assertThat(roleOf(planner.id())).isEqualTo("PLANNER");
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get(ACCOUNTS)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 004·006 조회

    @Test
    void searchesByKeywordStatusAndRoleWithPaging() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);
        String tag = "srch" + UUID.randomUUID().toString().substring(0, 8);
        TestAccount first = createAccount(tag + "-a", "MAINTENANCE", "ACTIVE");
        TestAccount second = createAccount(tag + "-b", "MAINTENANCE", "DISABLED");
        createAccount(tag + "-c", "VIEWER", "ACTIVE");

        mockMvc.perform(
                        get(ACCOUNTS)
                                .cookie(session.session())
                                .param("q", tag.toUpperCase())
                                .param("roleCode", "MAINTENANCE")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first.id()))
                .andExpect(jsonPath("$.data[0].roleCode").value("MAINTENANCE"))
                .andExpect(jsonPath("$.data[0].rowVersion").value(0))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mockMvc.perform(
                        get(ACCOUNTS)
                                .cookie(session.session())
                                .param("q", tag)
                                .param("status", "DISABLED"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second.id()));
    }

    @Test
    void treatsLikeWildcardsAsPlainText() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);

        mockMvc.perform(get(ACCOUNTS).cookie(session.session()).param("q", "%_no-such-account_%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rejectsInvalidSearchConditions() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        LoginSession session = login(admin);

        mockMvc.perform(get(ACCOUNTS).cookie(session.session()).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ACCOUNTS).cookie(session.session()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ACCOUNTS).cookie(session.session()).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsDetailOrNotFound() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession session = login(admin);

        mockMvc.perform(get(ACCOUNTS + "/" + viewer.id()).cookie(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(viewer.id()))
                .andExpect(jsonPath("$.data.loginId").value(viewer.loginId()))
                .andExpect(jsonPath("$.data.roleCode").value("VIEWER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mockMvc.perform(get(ACCOUNTS + "/999999999").cookie(session.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- 007 변경

    @Test
    void renamesWithoutExpiringSessionsAndRecordsChangedColumns() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession adminSession = login(admin);
        LoginSession viewerSession = login(viewer);

        update(adminSession, viewer.id(), "{\"displayName\": \"새 이름\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("새 이름"))
                .andExpect(jsonPath("$.data.roleCode").value("VIEWER"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        mockMvc.perform(get("/api/v1/auth/me").cookie(viewerSession.session()))
                .andExpect(status().isOk());
        assertThat(updatedBy(viewer.id())).isEqualTo(admin.id());
        Map<String, Object> audit = lastAudit("UPDATE_ACCOUNT", admin.id());
        assertThat(audit)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("target_id", viewer.id());
        Map<String, Object> change = changesOf((Long) audit.get("id")).getFirst();
        assertThat(change).containsEntry("change_kind", "UPDATE");
        assertThat((String) change.get("before_data"))
                .contains("display_name", "테스트 사용자")
                .doesNotContain("role_id");
        assertThat((String) change.get("after_data")).contains("새 이름");
    }

    @Test
    void changesRoleAndExpiresAllSessionsOfTarget() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession adminSession = login(admin);
        LoginSession first = login(viewer);
        LoginSession second = login(viewer);

        update(adminSession, viewer.id(), "{\"roleCode\": \"PLANNER\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("PLANNER"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(first.session()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(second.session()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(adminSession.session()))
                .andExpect(status().isOk());
        loginRequest(viewer.loginId(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("PLANNER"));
    }

    @Test
    void locksThenUnlocksAndResetsFailureCount() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession adminSession = login(admin);
        LoginSession viewerSession = login(viewer);

        update(adminSession, viewer.id(), "{\"status\": \"LOCKED\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(viewerSession.session()))
                .andExpect(status().isUnauthorized());
        loginRequest(viewer.loginId(), PASSWORD).andExpect(status().isUnauthorized());

        jdbcClient
                .sql("update account set failed_login_count = 5 where id = :id")
                .param("id", viewer.id())
                .update();
        update(adminSession, viewer.id(), "{\"status\": \"ACTIVE\", \"rowVersion\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.rowVersion").value(2));

        assertThat(failedLoginCount(viewer.id())).isZero();
        loginRequest(viewer.loginId(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void rejectsStaleRowVersionAndRecordsBlocked() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession session = login(admin);
        update(session, viewer.id(), "{\"displayName\": \"첫 변경\", \"rowVersion\": 0}")
                .andExpect(status().isOk());

        update(session, viewer.id(), "{\"displayName\": \"늦은 변경\", \"rowVersion\": 0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.field").value("rowVersion"))
                .andExpect(jsonPath("$.error.details.expected").value(0))
                .andExpect(jsonPath("$.error.details.actual").value(1));

        assertThat(displayName(viewer.id())).isEqualTo("첫 변경");
        assertThat(lastAudit("UPDATE_ACCOUNT", admin.id())).containsEntry("status", "BLOCKED");
    }

    @Test
    void rejectsUpdateWithoutChangeOrVersion() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        TestAccount viewer = createAccount("VIEWER");
        LoginSession session = login(admin);

        update(session, viewer.id(), "{\"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("request"));
        update(session, viewer.id(), "{\"displayName\": \"버전 없음\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("rowVersion"));
        update(session, viewer.id(), "{\"displayName\": \"   \", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("displayName"));
        update(session, 999999999L, "{\"displayName\": \"없음\", \"rowVersion\": 0}")
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsLastActiveAdmin() throws Exception {
        TestAccount admin = createAccount("ADMIN");
        // 다른 테스트가 만든 관리자를 비활성으로 돌려 이 관리자가 마지막 활성 ADMIN이 되게 한다.
        jdbcClient
                .sql(
                        """
                        update account set status = 'DISABLED'
                        where id <> :id and status = 'ACTIVE'
                          and role_id = (select id from role where code = 'ADMIN')
                        """)
                .param("id", admin.id())
                .update();
        LoginSession session = login(admin);

        update(session, admin.id(), "{\"roleCode\": \"VIEWER\", \"rowVersion\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("마지막 활성 관리자 계정의 역할이나 상태는 바꿀 수 없습니다."))
                .andExpect(jsonPath("$.error.details.field").value("roleCode"));
        update(session, admin.id(), "{\"status\": \"DISABLED\", \"rowVersion\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("status"));
        assertThat(lastAudit("UPDATE_ACCOUNT", admin.id())).containsEntry("status", "BLOCKED");
        assertThat(roleOf(admin.id())).isEqualTo("ADMIN");

        // 다른 활성 관리자가 생기면 바꿀 수 있다.
        createAccount("ADMIN");
        update(session, admin.id(), "{\"roleCode\": \"VIEWER\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("VIEWER"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(session.session()))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 도우미

    private ResultActions create(
            LoginSession session,
            String loginId,
            String displayName,
            String roleCode,
            String temporaryPassword)
            throws Exception {
        return write(
                post(ACCOUNTS),
                session,
                """
                {"loginId": "%s", "displayName": "%s", "roleCode": "%s", "temporaryPassword": "%s"}
                """
                        .formatted(loginId, displayName, roleCode, temporaryPassword));
    }

    private ResultActions update(LoginSession session, Long id, String body) throws Exception {
        return write(patch(ACCOUNTS + "/" + id), session, body);
    }

    private ResultActions write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            LoginSession session,
            String body)
            throws Exception {
        return mockMvc.perform(
                request.cookie(session.session(), session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions loginRequest(String loginId, String password) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                        .formatted(loginId, password)));
    }

    private LoginSession login(TestAccount account) throws Exception {
        MvcResult result =
                loginRequest(account.loginId(), PASSWORD).andExpect(status().isOk()).andReturn();
        return new LoginSession(
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private TestAccount createAccount(String roleCode) {
        return createAccount(uniqueLoginId(roleCode.toLowerCase()), roleCode, "ACTIVE");
    }

    private TestAccount createAccount(String loginId, String roleCode, String status) {
        Long id =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     status, must_change_password)
                                values (:loginId, '테스트 사용자', :hash,
                                        (select id from role where code = :roleCode),
                                        :status, false)
                                returning id
                                """)
                        .param("loginId", loginId)
                        .param("hash", passwordEncoder.encode(PASSWORD))
                        .param("roleCode", roleCode)
                        .param("status", status)
                        .query(Long.class)
                        .single();
        return new TestAccount(id, loginId);
    }

    private static String uniqueLoginId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).path("data").path("id").longValue();
    }

    private Map<String, Object> lastAudit(String actionCode, Long actorId) {
        return jdbcClient
                .sql(
                        """
                        select e.id, e.event_kind, e.status, e.actor_type, e.actor_role_code,
                               e.entry_point, e.checked_permission_code, e.target_type, e.target_id,
                               e.input::text as input, e.blocked_reason
                        from action_execution e
                        join action_definition d on d.id = e.action_definition_id
                        where d.code = :code and e.actor_id = :actorId
                        order by e.id desc
                        limit 1
                        """)
                .param("code", actionCode)
                .param("actorId", actorId)
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> changesOf(Long executionId) {
        return jdbcClient
                .sql(
                        """
                        select table_name, change_kind, before_data::text as before_data,
                               after_data::text as after_data
                        from entity_change where action_execution_id = :id order by id
                        """)
                .param("id", executionId)
                .query()
                .listOfRows();
    }

    private long countAccounts(String loginId) {
        return jdbcClient
                .sql("select count(*) from account where login_id = :loginId")
                .param("loginId", loginId)
                .query(Long.class)
                .single();
    }

    private String roleOf(Long accountId) {
        return jdbcClient
                .sql(
                        "select r.code from account a join role r on r.id = a.role_id where a.id = :id")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private String displayName(Long accountId) {
        return jdbcClient
                .sql("select display_name from account where id = :id")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private Long updatedBy(Long accountId) {
        return jdbcClient
                .sql("select updated_by from account where id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private int failedLoginCount(Long accountId) {
        return jdbcClient
                .sql("select failed_login_count from account where id = :id")
                .param("id", accountId)
                .query(Integer.class)
                .single();
    }

    private record TestAccount(Long id, String loginId) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

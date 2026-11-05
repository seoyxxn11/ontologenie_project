package com.axcore.platform.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.action.handler.ActionContext;
import com.axcore.platform.action.handler.ActionHandler;
import com.axcore.platform.action.handler.ActionPreparation;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Action 정의 관리(API 087~090)와 V9·V10 초기 데이터를 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, ActionDefinitionApiTests.HandlerConfig.class})
class ActionDefinitionApiTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String ACTIONS = "/api/v1/actions";
    private static final String TEST_HANDLER = "TEST_HANDLER";
    private static final String UNDEPLOYED_HANDLER = "UNDEPLOYED_HANDLER";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        ActionHandler testHandler() {
            return new ActionHandler() {
                @Override
                public String key() {
                    return TEST_HANDLER;
                }

                @Override
                public ActionPreparation prepare(ActionContext context) {
                    return ActionPreparation.blocked("테스트 Handler는 실행하지 않습니다.");
                }
            };
        }
    }

    // ---------------------------------------------------------------- 초기 데이터

    @Test
    void seedsBusinessAndManagementActions() {
        List<Map<String, Object>> rows =
                jdbcClient
                        .sql(
                                """
                                select code, action_mode, handler_key, target_kind, target_object_type_id,
                                       required_permission, requires_confirmation
                                from action_definition
                                where code in ('CREATE_REASSIGNMENT_PLAN', 'CREATE_ACCOUNT', 'DECIDE_PROPOSAL')
                                order by code
                                """)
                        .query()
                        .listOfRows();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0))
                .containsEntry("code", "CREATE_ACCOUNT")
                .containsEntry("action_mode", "RECORD")
                .containsEntry("handler_key", null);
        assertThat(rows.get(1))
                .containsEntry("code", "CREATE_REASSIGNMENT_PLAN")
                .containsEntry("action_mode", "EXECUTE")
                .containsEntry("handler_key", "CREATE_REASSIGNMENT_PLAN")
                .containsEntry("target_kind", "OBJECT")
                .containsEntry("target_object_type_id", null)
                .containsEntry("required_permission", "PLAN_CREATE")
                .containsEntry("requires_confirmation", false);
        assertThat(rows.get(2))
                .containsEntry("action_mode", "RECORD")
                .containsEntry("required_permission", "PROPOSAL_DECIDE");
        assertThat(permissionsOf("ADMIN"))
                .contains(
                        "ACCOUNT_MANAGE",
                        "ACTION_DEFINITION_MANAGE",
                        "PLAN_CREATE",
                        "PROPOSAL_DECIDE",
                        "SOURCE_MANAGE",
                        "SYNC_SCHEDULE_MANAGE");
        assertThat(permissionsOf("PLANNER")).containsExactly("PLAN_CREATE");
        assertThat(permissionsOf("VIEWER")).isEmpty();
    }

    @Test
    void rejectsRecordDefinitionWithHandlerKey() {
        String code = uniqueCode();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into action_definition (code, name, description, target_kind,
                                                    action_mode, handler_key, action_kind, required_permission)
                                                values (:code, '잘못된 정의', '검사', 'SYSTEM', 'RECORD', 'X', 'WRITE',
                                                        'PLAN_CREATE')
                                                """)
                                        .param("code", code)
                                        .update())
                .hasMessageContaining("ck_action_definition_handler");
    }

    // ---------------------------------------------------------------- 087 목록

    @Test
    void listsDefinitionsForAdminWithFilters() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        mockMvc.perform(get(ACTIONS).param("size", "100").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[?(@.code == 'CREATE_ACCOUNT')].actionMode")
                                .value("RECORD"))
                .andExpect(
                        jsonPath("$.data[?(@.code == 'CREATE_REASSIGNMENT_PLAN')].handlerKey")
                                .value("CREATE_REASSIGNMENT_PLAN"))
                .andExpect(
                        jsonPath("$.data[?(@.code == 'CREATE_REASSIGNMENT_PLAN')].handlerReady")
                                .value(true))
                .andExpect(
                        jsonPath("$.data[?(@.code == 'CREATE_ACCOUNT')].handlerReady").value(false))
                .andExpect(jsonPath("$.meta.page").value(0));
        // 다른 테스트·초기 데이터에 기대지 않도록 조건에 맞는 정의를 직접 만들고, 결과가 모두 조건을 지키는지 본다.
        String inactiveRead = uniqueCode();
        write(
                        post(ACTIONS),
                        admin,
                        createBody(inactiveRead, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null)
                                .replace(
                                        "\"actionKind\": \"WRITE\"",
                                        "\"actionKind\": \"READ\", \"isActive\": false"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        get(ACTIONS)
                                .param("isActive", "false")
                                .param("actionKind", "READ")
                                .param("size", "100")
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '%s')]".formatted(inactiveRead)).exists())
                .andExpect(jsonPath("$.data[?(@.isActive == true)]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.actionKind == 'WRITE')]").isEmpty());
    }

    @Test
    void forbidsListForNonAdmin() throws Exception {
        LoginSession planner = login(createAccount("PLANNER"));

        mockMvc.perform(get(ACTIONS).cookie(planner.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---------------------------------------------------------------- 088 등록

    @Test
    void createsExecutableDefinitionAndRecordsAction() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = uniqueCode();

        MvcResult result =
                write(
                                post(ACTIONS),
                                admin,
                                createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.code").value(code))
                        .andExpect(jsonPath("$.data.actionMode").value("EXECUTE"))
                        .andExpect(jsonPath("$.data.handlerKey").value(TEST_HANDLER))
                        .andExpect(jsonPath("$.data.handlerReady").value(true))
                        .andExpect(jsonPath("$.data.isActive").value(true))
                        .andExpect(jsonPath("$.data.rowVersion").value(0))
                        .andExpect(jsonPath("$.data.inputSchema.type").value("object"))
                        .andReturn();
        long id = idOf(result);

        Map<String, Object> audit = lastAudit("CREATE_ACTION_DEFINITION", adminAccount.id());
        assertThat(audit)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("event_kind", "ACTION")
                .containsEntry("checked_permission_code", "ACTION_DEFINITION_MANAGE")
                .containsEntry("target_type", "action_definition")
                .containsEntry("target_id", id);
        List<Map<String, Object>> changes = changesOf((Long) audit.get("id"));
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0)).containsEntry("change_kind", "INSERT");
        assertThat(changes.get(0).get("after_data").toString()).contains(code);
        assertThat(createdBy(id)).isEqualTo(adminAccount.id());
    }

    @Test
    void createsDefinitionWhoseHandlerIsNotDeployedYet() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = uniqueCode();

        long id =
                idOf(
                        write(
                                        post(ACTIONS),
                                        admin,
                                        createBody(
                                                code,
                                                UNDEPLOYED_HANDLER,
                                                "PLAN_CREATE",
                                                "SYSTEM",
                                                null))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.handlerKey").value(UNDEPLOYED_HANDLER))
                                .andExpect(jsonPath("$.data.handlerReady").value(false))
                                .andReturn());

        assertThat(countDefinitions(code)).isOne();
        assertThat(lastAudit("CREATE_ACTION_DEFINITION", adminAccount.id()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("target_id", id);
        // 준비 여부는 저장하지 않으므로 087·089도 같은 계산 결과를 돌려준다.
        mockMvc.perform(get(ACTIONS + "/" + code).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlerReady").value(false));
        mockMvc.perform(get(ACTIONS).param("size", "100").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[?(@.code == '%s')].handlerReady".formatted(code))
                                .value(false));
    }

    @Test
    void rejectsUnknownPermissionAndSystemTargetWithType() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        write(
                        post(ACTIONS),
                        admin,
                        createBody(
                                uniqueCode(), TEST_HANDLER, "NO_SUCH_PERMISSION", "SYSTEM", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("requiredPermission"));
        write(
                        post(ACTIONS),
                        admin,
                        createBody(uniqueCode(), TEST_HANDLER, "PLAN_CREATE", "SYSTEM", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetObjectTypeId"));
        write(
                        post(ACTIONS),
                        admin,
                        createBody(uniqueCode(), TEST_HANDLER, "PLAN_CREATE", "OBJECT", 999_999L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetObjectTypeId"));
    }

    @Test
    void rejectsRecordModeRegistration() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        String body =
                createBody(uniqueCode(), TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null)
                        .replace("\"actionKind\"", "\"actionMode\": \"RECORD\", \"actionKind\"");

        write(post(ACTIONS), admin, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("actionMode"));
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        write(
                        post(ACTIONS),
                        admin,
                        createBody(
                                "CREATE_REASSIGNMENT_PLAN",
                                TEST_HANDLER,
                                "PLAN_CREATE",
                                "SYSTEM",
                                null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.details.field").value("code"));
    }

    @Test
    void forbidsCreateForNonAdminAndRecordsBlocked() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        String code = uniqueCode();

        write(post(ACTIONS), planner, createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.error.details.requiredPermission")
                                .value("ACTION_DEFINITION_MANAGE"));

        assertThat(countDefinitions(code)).isZero();
        assertThat(lastAudit("CREATE_ACTION_DEFINITION", plannerAccount.id()))
                .containsEntry("status", "BLOCKED")
                .containsEntry("actor_role_code", "PLANNER");
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        write(post(ACTIONS), admin, "{\"code\": \"%s\", \"name\": \"이름\"}".formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsCodeThatCannotBeUsedInPath() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        for (String code : List.of("A/B", "A;B", " TEST_X", "lower_code", "1_START")) {
            write(
                            post(ACTIONS),
                            admin,
                            createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.field").value("code"));
            assertThat(countDefinitions(code)).isZero();
        }
    }

    // ---------------------------------------------------------------- 089 조회

    @Test
    void getsDefinitionForAdminAndRoleWithPermission() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        LoginSession planner = login(createAccount("PLANNER"));
        LoginSession viewer = login(createAccount("VIEWER"));

        mockMvc.perform(get(ACTIONS + "/CREATE_ACCOUNT").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionMode").value("RECORD"))
                .andExpect(jsonPath("$.data.handlerKey").doesNotExist())
                .andExpect(jsonPath("$.data.handlerReady").value(false));
        mockMvc.perform(get(ACTIONS + "/CREATE_REASSIGNMENT_PLAN").cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlerReady").value(true))
                .andExpect(jsonPath("$.data.requiresConfirmation").value(false))
                .andExpect(jsonPath("$.data.inputSchema.required[0]").value("fromEquipmentId"));
        mockMvc.perform(get(ACTIONS + "/CREATE_REASSIGNMENT_PLAN").cookie(viewer.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("PLAN_CREATE"));
        mockMvc.perform(get(ACTIONS + "/NO_SUCH_ACTION").cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 090 수정

    @Test
    void updatesSentFieldsAndRecordsChangedColumns() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = uniqueCode();
        long id =
                idOf(
                        write(
                                        post(ACTIONS),
                                        admin,
                                        createBody(
                                                code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                                .andReturn());

        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        "{\"isActive\": false, \"requiresConfirmation\": true, \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.data.name").value("테스트 Action"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        Map<String, Object> audit = lastAudit("UPDATE_ACTION_DEFINITION", adminAccount.id());
        assertThat(audit).containsEntry("status", "SUCCEEDED").containsEntry("target_id", id);
        Map<String, Object> change = changesOf((Long) audit.get("id")).get(0);
        assertThat(change).containsEntry("change_kind", "UPDATE");
        assertThat(change.get("before_data").toString())
                .contains("is_active")
                .doesNotContain("\"name\"");
        assertThat(change.get("after_data").toString()).contains("requires_confirmation");
    }

    @Test
    void rejectsStaleRowVersion() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        String code = uniqueCode();
        write(post(ACTIONS), admin, createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                .andExpect(status().isCreated());
        write(patch(ACTIONS + "/" + code), admin, "{\"name\": \"새 이름\", \"rowVersion\": 0}")
                .andExpect(status().isOk());

        write(patch(ACTIONS + "/" + code), admin, "{\"name\": \"다른 이름\", \"rowVersion\": 0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.actual").value(1));
    }

    @Test
    void rejectsChangingRecordDefinition() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        int version = rowVersion("CREATE_ACCOUNT");

        write(
                        patch(ACTIONS + "/CREATE_ACCOUNT"),
                        admin,
                        "{\"isActive\": false, \"rowVersion\": %d}".formatted(version))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(rowVersion("CREATE_ACCOUNT")).isEqualTo(version);
    }

    @Test
    void rejectsImmutableFieldsAndEmptyChange() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));

        write(
                        patch(ACTIONS + "/CREATE_REASSIGNMENT_PLAN"),
                        admin,
                        "{\"code\": \"OTHER\", \"name\": \"이름\", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("code"));
        write(
                        patch(ACTIONS + "/CREATE_REASSIGNMENT_PLAN"),
                        admin,
                        "{\"actionMode\": \"RECORD\", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("actionMode"));
        write(patch(ACTIONS + "/CREATE_REASSIGNMENT_PLAN"), admin, "{\"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("request"));
    }

    @Test
    void rejectsSystemTargetKeepingObjectType() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        long typeId = createObjectType();
        String code = uniqueCode();
        write(post(ACTIONS), admin, createBody(code, TEST_HANDLER, "PLAN_CREATE", "OBJECT", typeId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetObjectTypeId").value(typeId));

        write(patch(ACTIONS + "/" + code), admin, "{\"targetKind\": \"SYSTEM\", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetObjectTypeId"));
        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        "{\"targetKind\": \"SYSTEM\", \"targetObjectTypeId\": null, \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetKind").value("SYSTEM"))
                .andExpect(jsonPath("$.data.targetObjectTypeId").doesNotExist());
    }

    @Test
    void forbidsUpdateForNonAdminAndRecordsBlocked() throws Exception {
        TestAccount plannerAccount = createAccount("PLANNER");
        LoginSession planner = login(plannerAccount);
        int version = rowVersion("CREATE_REASSIGNMENT_PLAN");

        write(
                        patch(ACTIONS + "/CREATE_REASSIGNMENT_PLAN"),
                        planner,
                        "{\"isActive\": false, \"rowVersion\": %d}".formatted(version))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.error.details.requiredPermission")
                                .value("ACTION_DEFINITION_MANAGE"));

        assertThat(rowVersion("CREATE_REASSIGNMENT_PLAN")).isEqualTo(version);
        assertThat(lastAudit("UPDATE_ACTION_DEFINITION", plannerAccount.id()))
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_type", "action_definition")
                .containsEntry("target_id", definitionId("CREATE_REASSIGNMENT_PLAN"));
    }

    @Test
    void updateOfUnknownCodeReturnsNotFoundAndRecordsBlocked() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);

        write(patch(ACTIONS + "/NO_SUCH_ACTION"), admin, "{\"name\": \"이름\", \"rowVersion\": 0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(lastAudit("UPDATE_ACTION_DEFINITION", adminAccount.id()))
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_type", null)
                .containsEntry("target_id", null);
    }

    @Test
    void changesHandlerKeyToUndeployedHandlerAndBack() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = uniqueCode();
        write(post(ACTIONS), admin, createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                .andExpect(status().isCreated());

        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        "{\"handlerKey\": \"%s\", \"rowVersion\": 0}".formatted(UNDEPLOYED_HANDLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlerKey").value(UNDEPLOYED_HANDLER))
                .andExpect(jsonPath("$.data.handlerReady").value(false))
                .andExpect(jsonPath("$.data.rowVersion").value(1));
        Map<String, Object> change =
                changesOf((Long) lastAudit("UPDATE_ACTION_DEFINITION", adminAccount.id()).get("id"))
                        .get(0);
        assertThat(change.get("after_data").toString()).contains(UNDEPLOYED_HANDLER);

        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        "{\"handlerKey\": \"%s\", \"rowVersion\": 1}".formatted(TEST_HANDLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlerReady").value(true));
    }

    @Test
    void rejectsUnknownPermissionOnUpdate() throws Exception {
        LoginSession admin = login(createAccount("ADMIN"));
        String code = uniqueCode();
        write(post(ACTIONS), admin, createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                .andExpect(status().isCreated());

        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        "{\"requiredPermission\": \"NO_SUCH_PERMISSION\", \"rowVersion\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("requiredPermission"));
        assertThat(rowVersion(code)).isZero();
    }

    @Test
    void recordsBlockedChangeOfRecordDefinition() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);

        write(
                        patch(ACTIONS + "/DECIDE_PROPOSAL"),
                        admin,
                        "{\"isActive\": false, \"rowVersion\": %d}"
                                .formatted(rowVersion("DECIDE_PROPOSAL")))
                .andExpect(status().isUnprocessableContent());

        Map<String, Object> audit = lastAudit("UPDATE_ACTION_DEFINITION", adminAccount.id());
        assertThat(audit)
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_id", definitionId("DECIDE_PROPOSAL"));
        assertThat(audit.get("blocked_reason").toString()).contains("관리 Action");
    }

    @Test
    void recordsDescriptionAndInputSchemaChanges() throws Exception {
        TestAccount adminAccount = createAccount("ADMIN");
        LoginSession admin = login(adminAccount);
        String code = uniqueCode();
        write(post(ACTIONS), admin, createBody(code, TEST_HANDLER, "PLAN_CREATE", "SYSTEM", null))
                .andExpect(status().isCreated());

        write(
                        patch(ACTIONS + "/" + code),
                        admin,
                        """
                        {"description": "바뀐 설명",
                         "inputSchema": {"type": "object", "required": ["targetId"]},
                         "rowVersion": 0}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("바뀐 설명"))
                .andExpect(jsonPath("$.data.inputSchema.required[0]").value("targetId"));

        Map<String, Object> change =
                changesOf((Long) lastAudit("UPDATE_ACTION_DEFINITION", adminAccount.id()).get("id"))
                        .get(0);
        assertThat(change.get("before_data").toString())
                .contains("테스트용 업무 Action")
                .contains("input_schema");
        assertThat(change.get("after_data").toString()).contains("바뀐 설명").contains("targetId");
    }

    // ---------------------------------------------------------------- 도우미

    private long definitionId(String code) {
        return jdbcClient
                .sql("select id from action_definition where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private static String createBody(
            String code, String handlerKey, String permission, String targetKind, Long typeId) {
        return """
                {"code": "%s", "name": "테스트 Action", "description": "테스트용 업무 Action",
                 "targetKind": "%s", "targetObjectTypeId": %s, "handlerKey": "%s",
                 "inputSchema": {"type": "object"}, "actionKind": "WRITE",
                 "requiresConfirmation": false, "requiredPermission": "%s"}
                """
                .formatted(
                        code, targetKind, typeId == null ? "null" : typeId, handlerKey, permission);
    }

    private ResultActions write(
            MockHttpServletRequestBuilder request, LoginSession session, String body)
            throws Exception {
        return mockMvc.perform(
                request.cookie(session.session(), session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private LoginSession login(TestAccount account) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                        .formatted(account.loginId(), PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        return new LoginSession(
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private TestAccount createAccount(String roleCode) {
        String loginId =
                roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long id =
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
        return new TestAccount(id, loginId);
    }

    private long createObjectType() {
        return jdbcClient
                .sql(
                        """
                        insert into object_type (code, name)
                        values (:code, '테스트 설비') returning id
                        """)
                .param("code", "EQ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .query(Long.class)
                .single();
    }

    private static String uniqueCode() {
        return "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static long idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private List<String> permissionsOf(String roleCode) {
        return jdbcClient
                .sql(
                        """
                        select p.code from role_permission rp
                        join role r on r.id = rp.role_id join permission p on p.id = rp.permission_id
                        where r.code = :roleCode order by p.code
                        """)
                .param("roleCode", roleCode)
                .query(String.class)
                .list();
    }

    private Map<String, Object> lastAudit(String actionCode, Long actorId) {
        return jdbcClient
                .sql(
                        """
                        select e.id, e.event_kind, e.status, e.actor_role_code, e.checked_permission_code,
                               e.target_type, e.target_id, e.blocked_reason
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
                        select change_kind, before_data::text as before_data, after_data::text as after_data
                        from entity_change where action_execution_id = :id order by id
                        """)
                .param("id", executionId)
                .query()
                .listOfRows();
    }

    private long countDefinitions(String code) {
        return jdbcClient
                .sql("select count(*) from action_definition where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private Long createdBy(long id) {
        return jdbcClient
                .sql("select created_by from action_definition where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private int rowVersion(String code) {
        return jdbcClient
                .sql("select row_version from action_definition where code = :code")
                .param("code", code)
                .query(Integer.class)
                .single();
    }

    private record TestAccount(Long id, String loginId) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

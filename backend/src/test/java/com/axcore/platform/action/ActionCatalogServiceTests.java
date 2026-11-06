package com.axcore.platform.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.action.handler.ActionContext;
import com.axcore.platform.action.handler.ActionHandler;
import com.axcore.platform.action.handler.ActionPreparation;
import com.axcore.platform.action.service.ActionCatalogService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Agent용 실행 가능한 Action 목록(Jira 303, Action 정의서 7절)을 V9·V10 초기 데이터와 실제 DB로 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, ActionCatalogServiceTests.HandlerConfig.class})
class ActionCatalogServiceTests {

    private static final String DEPLOYED = "CATALOG_TEST_HANDLER";
    private static final String UNDEPLOYED = "CATALOG_UNDEPLOYED_HANDLER";

    @Autowired ActionCatalogService catalog;

    @Autowired JdbcClient jdbcClient;

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        ActionHandler catalogTestHandler() {
            return new ActionHandler() {
                @Override
                public String key() {
                    return DEPLOYED;
                }

                @Override
                public ActionPreparation prepare(ActionContext context) {
                    return ActionPreparation.blocked("테스트 Handler는 실행하지 않습니다.");
                }
            };
        }
    }

    @Test
    void listsOnlyActiveDeployedBusinessActionsTheRoleMayRun() {
        String ready = createDefinition(DEPLOYED, "PLAN_CREATE", true, true);
        String inactive = createDefinition(DEPLOYED, "PLAN_CREATE", false, false);
        String undeployed = createDefinition(UNDEPLOYED, "PLAN_CREATE", true, false);
        String adminOnly = createDefinition(DEPLOYED, "SOURCE_MANAGE", true, false);

        List<String> planner = codes(catalog.executableFor(account("PLANNER")));

        assertThat(planner)
                .contains("CREATE_REASSIGNMENT_PLAN", ready)
                .doesNotContain(inactive, undeployed, adminOnly);
        // 다른 테스트가 만든 정의가 섞여 있어도, 결과는 모두 조건을 지켜야 한다.
        planner.forEach(this::assertExecutableByPlanner);
    }

    @Test
    void returnsValuesNeededToBuildTool() {
        String ready = createDefinition(DEPLOYED, "PLAN_CREATE", true, true);

        ExecutableAction action =
                catalog.executableFor(account("PLANNER")).stream()
                        .filter(candidate -> candidate.code().equals(ready))
                        .findFirst()
                        .orElseThrow();

        assertThat(action.name()).isEqualTo("테스트 Action");
        assertThat(action.description()).isEqualTo("Agent용 목록 테스트");
        assertThat(action.inputSchema()).containsEntry("type", "object");
        assertThat(action.requiresConfirmation()).isTrue();
    }

    @Test
    void excludesManagementActions() {
        List<String> admin = codes(catalog.executableFor(account("ADMIN")));

        assertThat(admin).doesNotContain("CREATE_ACCOUNT", "DECIDE_PROPOSAL");
        admin.forEach(code -> assertThat(modeOf(code)).isEqualTo("EXECUTE"));
    }

    @Test
    void filtersByRolePermission() {
        String adminOnly = createDefinition(DEPLOYED, "SOURCE_MANAGE", true, false);

        assertThat(codes(catalog.executableFor(account("ADMIN")))).contains(adminOnly);
        assertThat(codes(catalog.executableFor(account("PLANNER")))).doesNotContain(adminOnly);
    }

    @Test
    void roleWithoutPermissionGetsNoActions() {
        assertThat(catalog.executableFor(account("VIEWER"))).isEmpty();
    }

    /** 090으로 handlerKey를 이미 배포된 키로 바꾼 경우. 정의를 고치지 않는 배포 전후는 ActionCatalogServiceDeployTests가 본다. */
    @Test
    void listsDefinitionOnceItPointsToDeployedHandlerKey() {
        String code = createDefinition(UNDEPLOYED, "PLAN_CREATE", true, false);
        assertThat(codes(catalog.executableFor(account("PLANNER")))).doesNotContain(code);

        jdbcClient
                .sql("update action_definition set handler_key = :key where code = :code")
                .param("key", DEPLOYED)
                .param("code", code)
                .update();

        assertThat(codes(catalog.executableFor(account("PLANNER")))).contains(code);
    }

    // ---------------------------------------------------------------- 도우미

    private void assertExecutableByPlanner(String code) {
        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
                                select d.action_mode, d.is_active, d.handler_key,
                                       exists (select 1 from role_permission rp
                                               join role r on r.id = rp.role_id
                                               join permission p on p.id = rp.permission_id
                                               where r.code = 'PLANNER'
                                                 and p.code = d.required_permission) as permitted
                                from action_definition d where d.code = :code
                                """)
                        .param("code", code)
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("action_mode", "EXECUTE")
                .containsEntry("is_active", true)
                .containsEntry("permitted", true);
        assertThat(row.get("handler_key")).isIn("CREATE_REASSIGNMENT_PLAN", DEPLOYED);
    }

    private String modeOf(String code) {
        return jdbcClient
                .sql("select action_mode from action_definition where code = :code")
                .param("code", code)
                .query(String.class)
                .single();
    }

    /** SYSTEM 대상 업무 Action 정의. API(088)의 다른 검사와 섞이지 않게 DB에 직접 넣는다. */
    private String createDefinition(
            String handlerKey, String permission, boolean active, boolean confirm) {
        String code = "CATALOG_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcClient
                .sql(
                        """
                        insert into action_definition (code, name, description, target_kind, action_mode,
                            handler_key, input_schema, action_kind, requires_confirmation,
                            required_permission, is_active)
                        values (:code, '테스트 Action', 'Agent용 목록 테스트', 'SYSTEM', 'EXECUTE',
                                :handlerKey, '{"type": "object"}'::jsonb, 'WRITE', :confirm,
                                :permission, :active)
                        """)
                .param("code", code)
                .param("handlerKey", handlerKey)
                .param("confirm", confirm)
                .param("permission", permission)
                .param("active", active)
                .update();
        return code;
    }

    private static LoginAccount account(String roleCode) {
        return new LoginAccount(1L, roleCode.toLowerCase(), roleCode, false);
    }

    private static List<String> codes(List<ExecutableAction> actions) {
        return actions.stream().map(ExecutableAction::code).toList();
    }
}

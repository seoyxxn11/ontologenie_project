package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 정의 직접 편집의 감사 기록(V18 관리 Action)과 044 정의 변경 이력 조회(Jira 187)를 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest(properties = "test.context=csrf-api")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DefinitionChangeApiTests {

    private static final String ONTOLOGY = "/api/v1/ontology";
    private static final String CHANGES = ONTOLOGY + "/changes";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    private long admin;
    private long resource;
    private Map<String, Long> columns;

    @BeforeEach
    void prepare() {
        admin = account("ADMIN");
        String source = code("SRC");
        long dataSource =
                jdbc.sql(
                                "insert into data_source (code, name, source_kind) values (:code, 'ERP', 'POSTGRES') returning id")
                        .param("code", source)
                        .query(Long.class)
                        .single();
        resource =
                jdbc.sql(
                                """
                                insert into source_resource (data_source_id, resource_key, resource_kind, is_readable, sync_strategy, loaded_lsn)
                                values (:source, 'erp.staff', 'TABLE', true, 'CDC', 100) returning id
                                """)
                        .param("source", dataSource)
                        .query(Long.class)
                        .single();
        columns = new java.util.HashMap<>();
        int position = 1;
        for (String column : List.of("emp_no", "emp_name")) {
            columns.put(
                    column,
                    jdbc.sql(
                                    """
                                    insert into source_column (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position, is_present)
                                    values (:resource, :name, 'varchar', false, :position, true) returning id
                                    """)
                            .param("resource", resource)
                            .param("name", column)
                            .param("position", position++)
                            .query(Long.class)
                            .single());
        }
    }

    @Test
    void recordsEveryDirectEditWithItsActionAndChangedRow() throws Exception {
        long employee =
                id(
                        send(
                                post(ONTOLOGY + "/object-types"),
                                Map.of("code", code("EMP"), "name", "직원")));
        send(patch(ONTOLOGY + "/object-types/" + employee), Map.of("name", "사원", "rowVersion", 0));
        long empNo =
                id(
                        send(
                                post(ONTOLOGY + "/object-types/" + employee + "/properties"),
                                Map.of(
                                        "code",
                                        "emp_no",
                                        "name",
                                        "사번",
                                        "dataType",
                                        "STRING",
                                        "isRequired",
                                        false)));
        long empName =
                id(
                        send(
                                post(ONTOLOGY + "/object-types/" + employee + "/properties"),
                                Map.of(
                                        "code",
                                        "emp_name",
                                        "name",
                                        "이름",
                                        "dataType",
                                        "STRING",
                                        "isRequired",
                                        false)));
        send(
                patch(ONTOLOGY + "/properties/" + empName),
                Map.of("name", "성명", "dataType", "STRING", "isRequired", false, "rowVersion", 0));
        long department =
                id(
                        send(
                                post(ONTOLOGY + "/object-types"),
                                Map.of("code", code("DEPT"), "name", "부서")));
        Map<String, Object> link =
                new java.util.LinkedHashMap<>(
                        Map.of(
                                "code",
                                code("BELONGS"),
                                "name",
                                "소속",
                                "sourceTypeId",
                                employee,
                                "targetTypeId",
                                department,
                                "cardinality",
                                "MANY_TO_ONE",
                                "description",
                                "직원이 속한 부서"));
        long linkType = id(send(post(ONTOLOGY + "/link-types"), link));
        link.put("name", "소속 부서");
        link.put("rowVersion", 0);
        send(patch(ONTOLOGY + "/link-types/" + linkType), link);
        sendNoContent(delete(ONTOLOGY + "/link-types/" + linkType));
        Map<String, Object> mapping =
                new java.util.LinkedHashMap<>(
                        Map.of(
                                "sourceResourceId",
                                resource,
                                "objectTypeId",
                                employee,
                                "keyColumns",
                                List.of("emp_no"),
                                "rowFilter",
                                Map.of(),
                                "isActive",
                                false));
        long objectMapping = id(send(post(ONTOLOGY + "/object-mappings"), mapping));
        mapping.put("isActive", true);
        mapping.put("rowVersion", 0);
        send(patch(ONTOLOGY + "/object-mappings/" + objectMapping), mapping);
        Map<String, Object> propertyMapping =
                new java.util.LinkedHashMap<>(
                        Map.of(
                                "sourceMappingId",
                                objectMapping,
                                "sourceResourceId",
                                resource,
                                "propertyDefinitionId",
                                empName,
                                "sourceColumnId",
                                columns.get("emp_name")));
        long mappingId = id(send(post(ONTOLOGY + "/property-mappings"), propertyMapping));
        propertyMapping.put("transformSteps", List.of(Map.of("op", "TRIM")));
        propertyMapping.put("rowVersion", 0);
        send(patch(ONTOLOGY + "/property-mappings/" + mappingId), propertyMapping);
        send(delete(ONTOLOGY + "/property-mappings/" + mappingId), null);
        sendNoContent(delete(ONTOLOGY + "/properties/" + empNo));
        sendNoContent(delete(ONTOLOGY + "/object-types/" + department));

        List<Map<String, Object>> executions =
                jdbc.sql(
                                """
                                select d.code as action, e.status, e.target_type, e.target_id,
                                       e.checked_permission_code as permission,
                                       c.table_name, c.change_kind, c.row_key ->> 'id' as row_id,
                                       c.before_data is not null as has_before, c.after_data is not null as has_after
                                from action_execution e
                                join action_definition d on d.id = e.action_definition_id
                                left join entity_change c on c.action_execution_id = e.id
                                where e.actor_id = :admin
                                order by e.id
                                """)
                        .param("admin", admin)
                        .query()
                        .listOfRows();
        assertThat(executions)
                .extracting(row -> row.get("action"))
                .containsExactly(
                        "CREATE_OBJECT_TYPE",
                        "UPDATE_OBJECT_TYPE",
                        "CREATE_PROPERTY",
                        "CREATE_PROPERTY",
                        "UPDATE_PROPERTY",
                        "CREATE_OBJECT_TYPE",
                        "CREATE_LINK_TYPE",
                        "UPDATE_LINK_TYPE",
                        "DELETE_LINK_TYPE",
                        "CREATE_OBJECT_MAPPING",
                        "UPDATE_OBJECT_MAPPING",
                        "CREATE_PROPERTY_MAPPING",
                        "UPDATE_PROPERTY_MAPPING",
                        "DELETE_PROPERTY_MAPPING",
                        "DELETE_PROPERTY",
                        "DELETE_OBJECT_TYPE");
        assertThat(executions)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
                            assertThat(row.get("permission")).isEqualTo("ONTOLOGY_MANAGE");
                            assertThat(row.get("table_name")).isEqualTo(row.get("target_type"));
                            assertThat(row.get("row_id"))
                                    .isEqualTo(String.valueOf(row.get("target_id")));
                            String action = (String) row.get("action");
                            String kind = (String) row.get("change_kind");
                            assertThat(kind)
                                    .isEqualTo(
                                            action.startsWith("CREATE_")
                                                    ? "INSERT"
                                                    : action.startsWith("UPDATE_")
                                                            ? "UPDATE"
                                                            : "DELETE");
                            assertThat(row.get("has_before")).isEqualTo(!kind.equals("INSERT"));
                            assertThat(row.get("has_after")).isEqualTo(!kind.equals("DELETE"));
                        });

        // 수정의 전후는 DB 컬럼 그대로(snake_case) 남는다.
        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(employee))
                                .with(user(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].actionCode",
                                contains("UPDATE_OBJECT_TYPE", "CREATE_OBJECT_TYPE")))
                .andExpect(jsonPath("$.data[0].changeKind").value("UPDATE"))
                .andExpect(jsonPath("$.data[0].tableName").value("object_type"))
                .andExpect(jsonPath("$.data[0].rowKey.id").value(employee))
                .andExpect(jsonPath("$.data[0].beforeData.name").value("직원"))
                .andExpect(jsonPath("$.data[0].afterData.name").value("사원"))
                .andExpect(jsonPath("$.data[0].afterData.row_version").value(1))
                .andExpect(jsonPath("$.data[0].actorId").value(admin))
                .andExpect(jsonPath("$.data[0].actorType").value("USER"))
                .andExpect(jsonPath("$.data[0].requestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].changeKind").value("INSERT"))
                .andExpect(jsonPath("$.data[1].beforeData").value(nullValue()));
        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(department))
                                .with(user(admin, "ADMIN")))
                .andExpect(jsonPath("$.data[*].changeKind", contains("DELETE", "INSERT")))
                .andExpect(jsonPath("$.data[0].afterData").value(nullValue()))
                .andExpect(jsonPath("$.data[0].beforeData.name").value("부서"));
    }

    @Test
    void sameValuesLeaveNoChangedRowAndRejectedEditsAreBlockedAndRolledBack() throws Exception {
        long type =
                id(
                        send(
                                post(ONTOLOGY + "/object-types"),
                                Map.of("code", code("SAME"), "name", "같음")));

        // 값이 같은 수정: 실행은 남지만 바뀐 행은 없다.
        send(patch(ONTOLOGY + "/object-types/" + type), Map.of("name", "같음", "rowVersion", 0));
        // 오래된 rowVersion: 409, 편집 롤백, BLOCKED 기록
        perform(patch(ONTOLOGY + "/object-types/" + type), Map.of("name", "바뀜", "rowVersion", 7))
                .andExpect(status().isConflict());
        // 쓰이는 유형 삭제: 409 IN_USE, BLOCKED 기록
        id(
                send(
                        post(ONTOLOGY + "/object-types/" + type + "/properties"),
                        Map.of(
                                "code",
                                "code",
                                "name",
                                "코드",
                                "dataType",
                                "STRING",
                                "isRequired",
                                false)));
        perform(delete(ONTOLOGY + "/object-types/" + type), null).andExpect(status().isConflict());

        assertThat(
                        jdbc.sql("select name from object_type where id = :id")
                                .param("id", type)
                                .query(String.class)
                                .single())
                .isEqualTo("같음");
        List<Map<String, Object>> rows =
                jdbc.sql(
                                """
                                select d.code as action, e.status, e.blocked_reason, e.result ->> 'errorCode' as error_code,
                                       e.result ->> 'changedRows' as changed_rows,
                                       (select count(*) from entity_change c where c.action_execution_id = e.id) as changes
                                from action_execution e join action_definition d on d.id = e.action_definition_id
                                where e.actor_id = :admin order by e.id
                                """)
                        .param("admin", admin)
                        .query()
                        .listOfRows();
        assertThat(rows)
                .extracting(
                        row -> row.get("action"),
                        row -> row.get("status"),
                        row -> row.get("error_code"),
                        row -> row.get("changes"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "CREATE_OBJECT_TYPE", "SUCCEEDED", null, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                "UPDATE_OBJECT_TYPE", "SUCCEEDED", null, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                "UPDATE_OBJECT_TYPE", "BLOCKED", "VERSION_CONFLICT", 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                "CREATE_PROPERTY", "SUCCEEDED", null, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                "DELETE_OBJECT_TYPE", "BLOCKED", "IN_USE", 0L));
        assertThat(rows.get(1).get("changed_rows")).isEqualTo("0");
        assertThat(rows.get(2).get("blocked_reason")).isNotNull();
    }

    @Test
    void listsApprovalChangesTooButOnlyDefinitionTablesNewestFirstWithPaging() throws Exception {
        long type =
                id(
                        send(
                                post(ONTOLOGY + "/object-types"),
                                Map.of("code", code("PAGE"), "name", "0")));
        for (int i = 1; i <= 3; i++) {
            send(
                    patch(ONTOLOGY + "/object-types/" + type),
                    Map.of("name", String.valueOf(i), "rowVersion", i - 1));
        }
        // 후보 승인이 남긴 정의 변경(DECIDE_PROPOSAL)은 함께 나오고, 정의가 아닌 표(proposal_item)는 나오지 않는다.
        long approval =
                jdbc.sql(
                                """
                                insert into action_execution (event_kind, actor_type, actor_id, entry_point, status,
                                    action_definition_id, checked_permission_code, target_type, target_id, input, result,
                                    requested_at, finished_at)
                                values ('ACTION', 'USER', :admin, 'WEB', 'SUCCEEDED',
                                    (select id from action_definition where code = 'DECIDE_PROPOSAL'), 'PROPOSAL_DECIDE',
                                    'proposal_item', 1, '{}', '{}', now() + interval '1 minute', now() + interval '1 minute')
                                returning id
                                """)
                        .param("admin", admin)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into entity_change (action_execution_id, table_name, row_key, change_kind, before_data, after_data)
                        values (:e, 'object_type', jsonb_build_object('id', :type), 'UPDATE', '{"name":"3"}', '{"name":"승인"}'),
                               (:e, 'proposal_item', '{"id":1}', 'UPDATE', '{"review_status":"PENDING"}', '{"review_status":"APPROVED"}')
                        """)
                .param("e", approval)
                .param("type", type)
                .update();

        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(type))
                                .param("size", "2")
                                .with(user(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(5))
                .andExpect(jsonPath("$.meta.hasNext").value(true))
                .andExpect(
                        jsonPath(
                                "$.data[*].actionCode",
                                contains("DECIDE_PROPOSAL", "UPDATE_OBJECT_TYPE")))
                .andExpect(jsonPath("$.data[1].afterData.name").value("3"));
        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(type))
                                .param("page", "2")
                                .param("size", "2")
                                .with(user(admin, "ADMIN")))
                .andExpect(jsonPath("$.data[*].actionCode", contains("CREATE_OBJECT_TYPE")))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        // 기간은 requestedAt 기준, from 포함·to 미포함
        Instant future = Instant.now().plusSeconds(30);
        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(type))
                                .param("from", future.toString())
                                .with(user(admin, "ADMIN")))
                .andExpect(jsonPath("$.data[*].actionCode", contains("DECIDE_PROPOSAL")));
        mvc.perform(
                        get(CHANGES)
                                .param("tableName", "object_type")
                                .param("rowId", String.valueOf(type))
                                .param("to", future.toString())
                                .with(user(admin, "ADMIN")))
                .andExpect(jsonPath("$.meta.totalElements").value(4));
        mvc.perform(get(CHANGES).param("tableName", "proposal_item").with(user(admin, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("tableName"));
    }

    @Test
    void validatesConditionsAndAllowsOnlyAdmins() throws Exception {
        mvc.perform(get(CHANGES).param("rowId", "1").with(user(admin, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("rowId"));
        mvc.perform(
                        get(CHANGES)
                                .param("from", "2026-11-12T00:00:00Z")
                                .param("to", "2026-11-12T00:00:00Z")
                                .with(user(admin, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("from"));
        mvc.perform(get(CHANGES).param("size", "101").with(user(admin, "ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(CHANGES).with(user(account("VIEWER"), "VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(CHANGES)).andExpect(status().isUnauthorized());
        // 정의 편집도 관리자만: 거절은 감사 대상 전에 끝나 기록이 생기지 않는다.
        long viewer = account("VIEWER");
        mvc.perform(
                        post(ONTOLOGY + "/object-types")
                                .with(user(viewer, "VIEWER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"" + code("NO") + "\",\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(
                        jdbc.sql("select count(*) from action_execution where actor_id = :id")
                                .param("id", viewer)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = perform(request, body).andReturn().getResponse();
        String content = response.getContentAsString();
        assertThat(response.getStatus()).as(content).isBetween(200, 299);
        return json.readTree(content);
    }

    private void sendNoContent(MockHttpServletRequestBuilder request) throws Exception {
        perform(request, null).andExpect(status().isNoContent());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        request.with(user(admin, "ADMIN")).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private static long id(JsonNode body) {
        return body.path("data").path("id").asLong();
    }

    private static RequestPostProcessor user(long id, String role) {
        return authentication(
                new LoginAccount(id, "definition-" + id, role, false).toAuthentication());
    }

    private long account(String role) {
        return jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                        values (:login, '정의 이력 테스트', 'test-hash', (select id from role where code = :role), false)
                        returning id
                        """)
                .param("login", "definition-" + UUID.randomUUID())
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private static String code(String prefix) {
        return prefix
                + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}

package com.axcore.platform.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Types;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 객체 원천 매핑(API 034~036)을 실제 보안 설정과 DB로 확인한다. 원천 자원·컬럼은 연결 검사(배치)가 채우므로 직접 넣는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectMappingApiTests {

    private static final String BASE_PATH = "/api/v1/ontology/object-mappings";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsMappingAndRequestsFullReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(4096L);
        long employee = insertObjectType();

        send(admin, post(BASE_PATH), body(resource, employee, "\"emp_no\"", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceResourceId").value(resource))
                .andExpect(jsonPath("$.data.objectTypeId").value(employee))
                .andExpect(jsonPath("$.data.keyColumns", contains("emp_no")))
                .andExpect(jsonPath("$.data.rowFilter").isEmpty())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                .andExpect(jsonPath("$.data.proposalItemId").value(nullValue()))
                .andExpect(jsonPath("$.data.rowVersion").value(0));

        assertThat(loadedLsn(resource)).isNull();
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("sourceResourceId", String.valueOf(resource))
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].objectTypeId", contains((int) employee)))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("objectTypeId", String.valueOf(insertObjectType()))
                                .cookie(admin.session()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void inactiveMappingDoesNotReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(4096L);

        send(
                        admin,
                        post(BASE_PATH),
                        body(resource, insertObjectType(), "\"emp_no\"", ", \"isActive\": false"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isActive").value(false));

        assertThat(loadedLsn(resource)).isEqualTo(4096L);
    }

    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        long type = insertObjectType();

        send(
                        admin,
                        post(BASE_PATH),
                        body(resource, type, "\"emp_no\"", ", \"rowFilter\": {\"use_yn\": \"Y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("rowFilter"));
        send(admin, post(BASE_PATH), body(resource, type, "\"emp_no\", \"emp_no\"", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("keyColumns"));
        send(admin, post(BASE_PATH), body(resource, type, "", ""))
                .andExpect(status().isBadRequest());
        send(admin, post(BASE_PATH), body(resource, type, "\"old_code\"", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.missingColumns", contains("old_code")));
        send(admin, post(BASE_PATH), body(999999999L, type, "\"emp_no\"", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("sourceResourceId"));
        send(admin, post(BASE_PATH), body(resource, 999999999L, "\"emp_no\"", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("objectTypeId"));
    }

    @Test
    void allowsOneActiveMappingPerResourceAndType() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        long employee = insertObjectType();
        long worker = insertObjectType();
        long first = createMapping(admin, resource, employee, "\"emp_no\"");

        send(admin, post(BASE_PATH), body(resource, employee, "\"emp_name\"", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        // 같은 표를 다른 유형으로 매핑하는 것은 된다.
        send(admin, post(BASE_PATH), body(resource, worker, "\"emp_no\"", ""))
                .andExpect(status().isCreated());
        // 멈춘 매핑은 같은 자원·유형이 있어도 만들 수 있고, 다시 켜려면 기존 활성 매핑을 먼저 멈춰야 한다.
        long paused =
                createMapping(admin, resource, employee, "\"emp_name\"", ", \"isActive\": false");
        send(
                        admin,
                        patch(BASE_PATH + "/" + paused),
                        updateBody(resource, employee, "\"emp_name\"", true, 0))
                .andExpect(status().isConflict());
        send(
                        admin,
                        patch(BASE_PATH + "/" + first),
                        updateBody(resource, employee, "\"emp_no\"", false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reloadRequired").value(false));
        send(
                        admin,
                        patch(BASE_PATH + "/" + paused),
                        updateBody(resource, employee, "\"emp_name\"", true, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.reloadReason").value("REACTIVATED"));
    }

    @Test
    void changesIdentityOnlyWhileNoObjects() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(4096L);
        long type = insertObjectType();
        long mapping = createMapping(admin, resource, type, "\"emp_no\"");
        setLoadedLsn(resource, 8192L);

        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\", \"emp_name\"", null, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyColumns", contains("emp_no", "emp_name")))
                .andExpect(jsonPath("$.data.rowVersion").value(1))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.reloadReason").value("IDENTITY_CHANGED"));
        assertThat(loadedLsn(resource)).isNull();

        setLoadedLsn(resource, 8192L);
        insertObject(mapping, type);
        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\"", null, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MAPPING_IDENTITY_LOCKED"));
        // 원천 식별은 그대로 두고 수집만 멈추는 것은 된다. 다시 읽을 필요는 없다.
        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\", \"emp_name\"", false, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.reloadRequired").value(false))
                .andExpect(jsonPath("$.data.reloadReason").value(nullValue()));
        assertThat(loadedLsn(resource)).isEqualTo(8192L);
    }

    /** 387: 매핑을 끄면 그 객체는 연결 대상에서 빠지므로, 같은 요청에서 그 객체에 걸린 연결을 끈다. */
    @Test
    void turningMappingOffInactivatesLinksOfItsObjects() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        long employee = insertObjectType();
        long worker = insertObjectType();
        long mapping = createMapping(admin, resource, employee, "\"emp_no\"");
        long collected = insertObject(mapping, employee);
        long platform =
                jdbcClient
                        .sql(
                                "insert into object_instance (object_type_id, display_name) values (:type, '작업자') returning id")
                        .param("type", worker)
                        .query(Long.class)
                        .single();
        long link = insertManualLink(worker, employee, platform, collected);

        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, employee, "\"emp_no\"", false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));

        assertThat(
                        jdbcClient
                                .sql("select status from object_link where id = :id")
                                .param("id", link)
                                .query(String.class)
                                .single())
                .isEqualTo("INACTIVE");
    }

    @Test
    void rejectsInvalidUpdates() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        long type = insertObjectType();
        long mapping = createMapping(admin, resource, type, "\"emp_no\"");

        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\"", null, 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"nope\"", null, 0))
                .andExpect(status().isUnprocessableContent());
        send(
                        admin,
                        patch(BASE_PATH + "/999999999"),
                        updateBody(resource, type, "\"emp_no\"", null, 0))
                .andExpect(status().isNotFound());
        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        """
                        {"sourceResourceId": %d, "objectTypeId": %d, "keyColumns": ["emp_no"],
                         "rowFilter": {"op": "EQ"}, "rowVersion": 0}
                        """
                                .formatted(resource, type))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        mockMvc.perform(get(BASE_PATH).cookie(viewer.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        send(viewer, post(BASE_PATH), body(1, 1, "\"emp_no\"", ""))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
    }

    /** 원천 데이터 정책상 들이지 않는 컬럼은 키로 쓸 수 없다(391). */
    @Test
    void rejectsForbiddenKeyColumn() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        jdbcClient
                .sql("update source_resource set resource_key = 'erp.employee' where id = :id")
                .param("id", resource)
                .update();
        long type = insertObjectType();

        send(admin, post(BASE_PATH), body(resource, type, "\"emp_no\", \"emp_name\"", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.field").value("keyColumns"))
                .andExpect(jsonPath("$.error.details.forbiddenColumns", contains("emp_name")));
        createMapping(admin, resource, type, "\"emp_no\"");
    }

    /** 391 이전에 만든, 정책이 막는 컬럼을 키로 쓰는 매핑도 끌 수 있다. 다시 켜는 것은 막는다(398). */
    @Test
    void turnsOffMappingOnForbiddenKeyButDoesNotTurnItBackOn() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        jdbcClient
                .sql("update source_resource set resource_key = 'erp.employee' where id = :id")
                .param("id", resource)
                .update();
        long type = insertObjectType();
        long mapping = createMapping(admin, resource, type, "\"emp_no\"");
        jdbcClient
                .sql(
                        "update object_source_mapping set key_columns = '[\"emp_no\", \"emp_name\"]'::jsonb where id = :id")
                .param("id", mapping)
                .update();

        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\", \"emp_name\"", false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(resource, type, "\"emp_no\", \"emp_name\"", true, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.forbiddenColumns", contains("emp_name")));
    }

    /** 민감 컬럼도 키로 쓸 수 없다. 키 값은 원천 키·표시 이름 대체값으로 가리지 않고 드러난다(391). */
    @Test
    void rejectsRestrictedKeyColumn() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long resource = insertResource(null);
        jdbcClient
                .sql(
                        "update source_resource set resource_key = 'mes.maintenance_order' where id = :id")
                .param("id", resource)
                .update();
        jdbcClient
                .sql(
                        "update source_column set column_name = 'technician' where source_resource_id = :id and column_name = 'emp_name'")
                .param("id", resource)
                .update();
        long type = insertObjectType();

        send(admin, post(BASE_PATH), body(resource, type, "\"technician\"", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_KEY"))
                .andExpect(jsonPath("$.error.details.restrictedColumns", contains("technician")));
        createMapping(admin, resource, type, "\"emp_no\"");
    }

    private long createMapping(LoginSession admin, long resource, long type, String keys)
            throws Exception {
        return createMapping(admin, resource, type, keys, "");
    }

    private long createMapping(
            LoginSession admin, long resource, long type, String keys, String extra)
            throws Exception {
        MvcResult result =
                send(admin, post(BASE_PATH), body(resource, type, keys, extra))
                        .andExpect(status().isCreated())
                        .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
                .longValue();
    }

    private static String body(long resource, long type, String keys, String extra) {
        return """
                {"sourceResourceId": %d, "objectTypeId": %d, "keyColumns": [%s]%s}
                """
                .formatted(resource, type, keys, extra);
    }

    private static String updateBody(
            long resource, long type, String keys, Boolean active, long rowVersion) {
        return """
                {"sourceResourceId": %d, "objectTypeId": %d, "keyColumns": [%s], "rowFilter": {}%s,
                 "rowVersion": %d}
                """
                .formatted(
                        resource,
                        type,
                        keys,
                        active == null ? "" : ", \"isActive\": " + active,
                        rowVersion);
    }

    /** 원천 하나와 CDC 테이블 하나(emp_no·emp_name, 사라진 old_code 컬럼)를 넣는다. */
    private long insertResource(Long loadedLsn) {
        long source =
                jdbcClient
                        .sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, 'ERP', 'POSTGRES') returning id
                                """)
                        .param("code", uniqueCode())
                        .query(Long.class)
                        .single();
        long resource =
                jdbcClient
                        .sql(
                                """
                                insert into source_resource
                                    (data_source_id, resource_key, resource_kind, is_readable, sync_strategy, loaded_lsn)
                                values (:source, 'erp.staff', 'TABLE', true, 'CDC', :lsn)
                                returning id
                                """)
                        .param("source", source)
                        .param("lsn", loadedLsn, Types.BIGINT)
                        .query(Long.class)
                        .single();
        int position = 1;
        for (String column : new String[] {"emp_no", "emp_name", "old_code"}) {
            jdbcClient
                    .sql(
                            """
                            insert into source_column
                                (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position, is_present)
                            values (:resource, :name, 'varchar', false, :position, :present)
                            """)
                    .param("resource", resource)
                    .param("name", column)
                    .param("position", position++)
                    .param("present", !column.equals("old_code"))
                    .update();
        }
        return resource;
    }

    private long insertObjectType() {
        return jdbcClient
                .sql("insert into object_type (code, name) values (:code, '유형') returning id")
                .param("code", uniqueCode())
                .query(Long.class)
                .single();
    }

    private long insertObject(long mapping, long type) {
        return jdbcClient
                .sql(
                        """
                        insert into object_instance (object_type_id, source_mapping_id, source_key, display_name)
                        values (:type, :mapping, '{"emp_no": "E011"}', '김원천') returning id
                        """)
                .param("type", type)
                .param("mapping", mapping)
                .query(Long.class)
                .single();
    }

    /** 관계 유형 하나와 그 수동 연결 하나. */
    private long insertManualLink(long sourceType, long targetType, long source, long target) {
        long linkType =
                jdbcClient
                        .sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '소속', :source, :target, 'MANY_TO_ONE', '시험') returning id
                                """)
                        .param("code", uniqueCode())
                        .param("source", sourceType)
                        .param("target", targetType)
                        .query(Long.class)
                        .single();
        long account =
                jdbcClient
                        .sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '연결', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "link-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        return jdbcClient
                .sql(
                        """
                        insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                        values (:type, :source, :target, 'MANUAL', :account) returning id
                        """)
                .param("type", linkType)
                .param("source", source)
                .param("target", target)
                .param("account", account)
                .query(Long.class)
                .single();
    }

    private Long loadedLsn(long resource) {
        return jdbcClient
                .sql("select loaded_lsn from source_resource where id = :id")
                .param("id", resource)
                .query((rs, row) -> rs.getObject(1, Long.class))
                .list()
                .getFirst();
    }

    private void setLoadedLsn(long resource, long lsn) {
        jdbcClient
                .sql("update source_resource set loaded_lsn = :lsn where id = :id")
                .param("lsn", lsn)
                .param("id", resource)
                .update();
    }

    private ResultActions send(
            LoginSession session, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.cookie(session.session(), session.csrf())
                .header("X-XSRF-TOKEN", session.csrf().getValue());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private static String uniqueCode() {
        return "T_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private LoginSession loginAs(String roleCode) throws Exception {
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
        return new LoginSession(
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private record LoginSession(Cookie session, Cookie csrf) {}
}

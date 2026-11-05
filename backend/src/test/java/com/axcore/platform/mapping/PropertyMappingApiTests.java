package com.axcore.platform.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Types;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 속성 원천 매핑(API 037~039·101)을 실제 보안 설정과 DB로 확인한다. 원천 자원·컬럼은 연결 검사(배치)가 채우므로 직접 넣는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class PropertyMappingApiTests {

    private static final String BASE_PATH = "/api/v1/ontology/property-mappings";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsMappingAndRequestsFullReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);

        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                f.properties.get("emp_no"),
                                f.columns.get("emp_no"),
                                """
                                , "transformSteps": [{"op": "TRIM"}, {"op": "STRIP_PREFIX", "arg": "W"}]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceMappingId").value(f.mapping))
                .andExpect(jsonPath("$.data.sourceResourceId").value(f.resource))
                .andExpect(jsonPath("$.data.sourceColumnId").value(f.columns.get("emp_no")))
                .andExpect(jsonPath("$.data.sourcePath").value(nullValue()))
                .andExpect(
                        jsonPath("$.data.transformSteps[*].op", contains("TRIM", "STRIP_PREFIX")))
                .andExpect(jsonPath("$.data.transformSteps[0].arg").value(nullValue()))
                .andExpect(jsonPath("$.data.transformSteps[1].arg").value("W"))
                .andExpect(jsonPath("$.data.rowVersion").value(0))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.reloadReason").value("PROPERTY_MAPPING_ADDED"));
        assertThat(loadedLsn(f.resource)).isNull();

        // 변환 단계를 빼면 빈 목록으로 저장한다.
        send(
                        admin,
                        post(BASE_PATH),
                        body(f, f.properties.get("emp_name"), f.columns.get("emp_name"), ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transformSteps").isEmpty());
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("sourceMappingId", String.valueOf(f.mapping))
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].propertyDefinitionId",
                                contains(
                                        f.properties.get("emp_no").intValue(),
                                        f.properties.get("emp_name").intValue())))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
        mockMvc.perform(
                        get(BASE_PATH)
                                .param(
                                        "sourceMappingId",
                                        String.valueOf(fixture(null, true).mapping))
                                .cookie(admin.session()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void pausedObjectMappingDoesNotReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, false);

        long id =
                createMapping(
                        admin, f, f.properties.get("emp_no"), f.columns.get("emp_no"), "", false);

        assertThat(loadedLsn(f.resource)).isEqualTo(4096L);
        send(admin, delete(BASE_PATH + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.reloadRequired").value(false))
                .andExpect(jsonPath("$.data.reloadReason").value(nullValue()));
        assertThat(loadedLsn(f.resource)).isEqualTo(4096L);
    }

    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(null, true);
        Fixture other = fixture(null, true);
        long empNo = f.properties.get("emp_no");
        long column = f.columns.get("emp_no");

        // 형식 오류는 400
        send(admin, post(BASE_PATH), body(f, empNo, null, ", \"sourcePath\": \"$.emp_no\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("sourcePath"));
        send(admin, post(BASE_PATH), body(f, empNo, null, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("sourceColumnId"));
        send(
                        admin,
                        post(BASE_PATH),
                        body(f, empNo, column, ", \"transformSteps\": [{\"op\": \"LOWER\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("transformSteps[0].op"));
        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                empNo,
                                column,
                                ", \"transformSteps\": [{\"op\": \"PAD_ZEROS\", \"arg\": 0}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("transformSteps[0].arg"));

        // 업무 규칙 오류는 422
        send(admin, post(BASE_PATH), body(f, other.properties.get("emp_no"), column, ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("propertyDefinitionId"));
        send(admin, post(BASE_PATH), body(f, empNo, other.columns.get("emp_no"), ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("sourceColumnId"));
        send(admin, post(BASE_PATH), body(f, empNo, f.columns.get("old_code"), ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("sourceColumnId"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"sourceMappingId": %d, "sourceResourceId": %d, "propertyDefinitionId": %d,
                         "sourceColumnId": %d}
                        """
                                .formatted(f.mapping, other.resource, empNo, column))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("sourceResourceId"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"sourceMappingId": 999999999, "sourceResourceId": %d, "propertyDefinitionId": %d,
                         "sourceColumnId": %d}
                        """
                                .formatted(f.resource, empNo, column))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("sourceMappingId"));
    }

    @Test
    void checksTransformResultTypes() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(null, true);

        // 정수로 바꾼 값은 문자열만 받는 함수가 받을 수 없다.
        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                f.properties.get("emp_no"),
                                f.columns.get("emp_no"),
                                ", \"transformSteps\": [{\"op\": \"TO_INTEGER\"}, {\"op\": \"UPPER\"}]"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("transformSteps[1].op"))
                .andExpect(jsonPath("$.error.details.inputType").value("INTEGER"));
        // 날짜로 바꾼 값은 정수 속성에 넣을 수 없다.
        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                f.properties.get("headcount"),
                                f.columns.get("emp_name"),
                                ", \"transformSteps\": [{\"op\": \"TO_DATE\"}]"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.outputType").value("DATE"))
                .andExpect(jsonPath("$.error.details.propertyDataType").value("INTEGER"));
        // 문자열 결과는 적재가 속성 자료형으로 바꾸므로 날짜 속성에도 넣을 수 있다.
        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                f.properties.get("hire_date"),
                                f.columns.get("hire_date"),
                                ", \"transformSteps\": [{\"op\": \"TRIM\"}]"))
                .andExpect(status().isCreated());
        send(
                        admin,
                        post(BASE_PATH),
                        body(
                                f,
                                f.properties.get("headcount"),
                                f.columns.get("emp_name"),
                                ", \"transformSteps\": [{\"op\": \"TO_INTEGER\"}, {\"op\": \"PAD_ZEROS\", \"arg\": 4}]"))
                .andExpect(status().isCreated());
    }

    @Test
    void updatesAndRejectsDuplicates() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(null, true);
        long empNo = f.properties.get("emp_no");
        long empName = f.properties.get("emp_name");
        long first = createMapping(admin, f, empNo, f.columns.get("emp_no"), "", true);
        createMapping(admin, f, empName, f.columns.get("emp_name"), "", true);

        send(admin, post(BASE_PATH), body(f, empNo, f.columns.get("emp_name"), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        send(
                        admin,
                        patch(BASE_PATH + "/" + first),
                        updateBody(f, empName, f.columns.get("emp_no"), "", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.field").value("propertyDefinitionId"));

        // 바뀐 값이 없으면 버전도 그대로고 다시 읽지 않는다.
        setLoadedLsn(f.resource, 8192L);
        send(
                        admin,
                        patch(BASE_PATH + "/" + first),
                        updateBody(f, empNo, f.columns.get("emp_no"), "", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value(0))
                .andExpect(jsonPath("$.data.reloadRequired").value(false));
        assertThat(loadedLsn(f.resource)).isEqualTo(8192L);

        send(
                        admin,
                        patch(BASE_PATH + "/" + first),
                        updateBody(
                                f,
                                empNo,
                                f.columns.get("emp_no"),
                                ", \"transformSteps\": [{\"op\": \"MAP_VALUE\", \"arg\": {\"W1\": \"1\"}}]",
                                0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value(1))
                .andExpect(jsonPath("$.data.transformSteps[0].arg.W1").value("1"))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.reloadReason").value("PROPERTY_MAPPING_CHANGED"));
        assertThat(loadedLsn(f.resource)).isNull();

        send(
                        admin,
                        patch(BASE_PATH + "/" + first),
                        updateBody(f, empNo, f.columns.get("emp_no"), "", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        send(
                        admin,
                        patch(BASE_PATH + "/999999999"),
                        updateBody(f, empNo, f.columns.get("emp_no"), "", 0))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAndRequestsFullReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(null, true);
        long id =
                createMapping(
                        admin, f, f.properties.get("emp_no"), f.columns.get("emp_no"), "", true);
        setLoadedLsn(f.resource, 8192L);

        send(admin, delete(BASE_PATH + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.sourceMappingId").value(f.mapping))
                .andExpect(jsonPath("$.data.sourceResourceId").value(f.resource))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.reloadReason").value("PROPERTY_MAPPING_DELETED"));
        assertThat(loadedLsn(f.resource)).isNull();
        send(admin, delete(BASE_PATH + "/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        mockMvc.perform(get(BASE_PATH).cookie(viewer.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        send(viewer, delete(BASE_PATH + "/1"), null).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
    }

    private long createMapping(
            LoginSession admin, Fixture f, long property, long column, String extra, boolean reload)
            throws Exception {
        MvcResult result =
                send(admin, post(BASE_PATH), body(f, property, column, extra))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.reloadRequired").value(reload))
                        .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
                .longValue();
    }

    private static String body(Fixture f, long property, Long column, String extra) {
        return """
                {"sourceMappingId": %d, "sourceResourceId": %d, "propertyDefinitionId": %d,
                 "sourceColumnId": %s%s}
                """
                .formatted(f.mapping, f.resource, property, column, extra);
    }

    private static String updateBody(
            Fixture f, long property, long column, String extra, long rowVersion) {
        return """
                {"sourceMappingId": %d, "sourceResourceId": %d, "propertyDefinitionId": %d,
                 "sourceColumnId": %d%s, "rowVersion": %d}
                """
                .formatted(f.mapping, f.resource, property, column, extra, rowVersion);
    }

    /**
     * 원천 하나와 CDC 테이블 하나(emp_no·emp_name·hire_date, 사라진 old_code), 객체 유형과 속성 셋(emp_no·emp_name 문자열,
     * hire_date 날짜, headcount 정수), 그 둘을 잇는 객체 원천 매핑을 넣는다.
     */
    private Fixture fixture(Long loadedLsn, boolean active) {
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
                                values (:source, 'erp.employee', 'TABLE', true, 'CDC', :lsn)
                                returning id
                                """)
                        .param("source", source)
                        .param("lsn", loadedLsn, Types.BIGINT)
                        .query(Long.class)
                        .single();
        Map<String, Long> columns = new HashMap<>();
        int position = 1;
        for (String column : new String[] {"emp_no", "emp_name", "hire_date", "old_code"}) {
            columns.put(
                    column,
                    jdbcClient
                            .sql(
                                    """
                                    insert into source_column
                                        (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position, is_present)
                                    values (:resource, :name, 'varchar', false, :position, :present)
                                    returning id
                                    """)
                            .param("resource", resource)
                            .param("name", column)
                            .param("position", position++)
                            .param("present", !column.equals("old_code"))
                            .query(Long.class)
                            .single());
        }
        long type =
                jdbcClient
                        .sql(
                                "insert into object_type (code, name) values (:code, '직원') returning id")
                        .param("code", uniqueCode())
                        .query(Long.class)
                        .single();
        Map<String, Long> properties = new HashMap<>();
        for (String[] property :
                new String[][] {
                    {"emp_no", "STRING"},
                    {"emp_name", "STRING"},
                    {"hire_date", "DATE"},
                    {"headcount", "INTEGER"}
                }) {
            properties.put(
                    property[0],
                    jdbcClient
                            .sql(
                                    """
                                    insert into property_definition (object_type_id, code, name, data_type)
                                    values (:type, :code, :code, :dataType) returning id
                                    """)
                            .param("type", type)
                            .param("code", property[0])
                            .param("dataType", property[1])
                            .query(Long.class)
                            .single());
        }
        long mapping =
                jdbcClient
                        .sql(
                                """
                                insert into object_source_mapping
                                    (source_resource_id, object_type_id, key_columns, is_active)
                                values (:resource, :type, '["emp_no"]'::jsonb, :active) returning id
                                """)
                        .param("resource", resource)
                        .param("type", type)
                        .param("active", active)
                        .query(Long.class)
                        .single();
        return new Fixture(resource, mapping, columns, properties);
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

    private record Fixture(
            long resource, long mapping, Map<String, Long> columns, Map<String, Long> properties) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

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

    /** 원천 데이터 정책상 들이지 않는 컬럼은 매핑할 수 없다(391). 같은 표의 다른 컬럼은 매핑된다. */
    @Test
    void rejectsForbiddenSourceColumn() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        renameResource(f, "erp.employee");

        send(
                        admin,
                        post(BASE_PATH),
                        body(f, f.properties.get("emp_name"), f.columns.get("emp_name"), ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.forbiddenColumns", contains("emp_name")));
        createMapping(admin, f, f.properties.get("emp_no"), f.columns.get("emp_no"), "", true);
    }

    /**
     * 401: 매핑을 끄고 지운 뒤 민감 속성을 일반으로 내리는 우회를 막는다. 꺼진 매핑의 객체에는 값이 남고, 그 값이 남아 있는 동안은 지우기를 명시해야만 내릴 수
     * 있다(속성 지우기는 원래부터 막힌다). 지우기를 명시하면 남은 값을 지우고 내린다.
     */
    @Test
    void remainingRestrictedValuesBlockLoweringAfterMappingIsGone() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        renameResource(f, "mes.worker");
        long skill = f.columns.get("emp_name");
        jdbcClient
                .sql("update source_column set column_name = 'skill_level' where id = :id")
                .param("id", skill)
                .update();
        long property = f.properties.get("emp_name");
        long mapping = createMapping(admin, f, property, skill, "", true);
        long object = storeObject(f, "{\"emp_no\": \"W1\", \"emp_name\": \"A등급\"}");

        // 객체 매핑을 끄고(036) 속성 매핑을 지운다(101). 부모가 꺼져 있어 다시 읽기가 없으니 값은 객체에 남는다.
        send(
                        admin,
                        patch("/api/v1/ontology/object-mappings/" + f.mapping),
                        objectMappingBody(f, "\"emp_no\"", false))
                .andExpect(status().isOk());
        send(admin, delete(BASE_PATH + "/" + mapping), null).andExpect(status().isOk());

        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        sensitivityBody(property, "NORMAL", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_VALUES_REMAIN"))
                .andExpect(jsonPath("$.error.details.objectCount").value(1))
                .andExpect(jsonPath("$.error.details.objectIds", contains((int) object)));
        // 지우기(029)는 값이 남은 객체를 참조로 보고 원래부터 막는다.
        send(admin, delete("/api/v1/ontology/properties/" + property), null)
                .andExpect(status().isConflict());

        // 남은 값을 지우고 내리기를 명시하면 객체에서 값을 지우고 내린다. 다시 읽기로는 꺼진 매핑의 값을 비울 수 없다.
        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        sensitivityBody(property, "NORMAL", ", \"clearRestrictedValues\": true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sensitivity").value("NORMAL"));
        assertThat(
                        jdbcClient
                                .sql(
                                        "select jsonb_exists(properties, 'emp_name') from object_instance where id = :id")
                                .param("id", object)
                                .query(Boolean.class)
                                .single())
                .isFalse();
        // 내린 시각을 남겨 그 전에 만든 저장된 사본은 계속 가린다(431)
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select count(*) from property_restriction_history h
                                        join property_definition p
                                          on p.object_type_id = h.object_type_id and p.code = h.property_code
                                        where p.id = :id
                                        """)
                                .param("id", property)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    /** 401: 사람이 지정한 민감 속성도 값이 남아 있으면 일반으로 내릴 수 없다. 매핑이 없어도 같다. 남은 것이 JSON null뿐이면 내릴 수 있다. */
    @Test
    void manuallyRestrictedPropertyWithValuesCannotBeLowered() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        long property = f.properties.get("hire_date");
        jdbcClient
                .sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", property)
                .update();
        long filled = storeObject(f, "{\"emp_no\": \"E1\", \"hire_date\": \"2020-01-01\"}");
        storeObject(f, "{\"emp_no\": \"E2\", \"hire_date\": null}");

        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        sensitivityBody(property, "NORMAL", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_VALUES_REMAIN"))
                .andExpect(jsonPath("$.error.details.objectCount").value(1));

        jdbcClient
                .sql(
                        "update object_instance set properties = jsonb_set(properties, '{hire_date}', 'null') where id = :id")
                .param("id", filled)
                .update();
        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        sensitivityBody(property, "NORMAL", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sensitivity").value("NORMAL"));
    }

    /** 401: 매핑을 일반 컬럼으로 옮겨도 다시 읽기 전에는 예전 민감 값이 객체에 남아 있어 내릴 수 없다. */
    @Test
    void movingTheMappingDoesNotLowerBeforeTheValuesAreReloaded() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        long property = f.properties.get("emp_name");
        jdbcClient
                .sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", property)
                .update();
        long mapping = createMapping(admin, f, property, f.columns.get("emp_name"), "", true);
        storeObject(f, "{\"emp_no\": \"E1\", \"emp_name\": \"홍길동\"}");

        send(
                        admin,
                        patch(BASE_PATH + "/" + mapping),
                        updateBody(f, property, f.columns.get("hire_date"), "", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reloadRequired").value(true));
        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        sensitivityBody(property, "NORMAL", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_VALUES_REMAIN"));
    }

    /**
     * 401: 민감 속성은 객체 매핑의 키 컬럼에 매핑할 수 없다. 키 값은 원천 키·표시 이름으로 남고, 원천 키 가림은 그 컬럼에 민감 속성이 매핑돼 있을 때만
     * 동작하므로 매핑을 바꾸는 순간 드러난다. 매핑할 때, 민감으로 올릴 때, 객체 매핑의 키를 바꿀 때 모두 막는다.
     */
    @Test
    void restrictedPropertiesStayOffKeyColumns() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        long restricted = f.properties.get("hire_date");
        jdbcClient
                .sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", restricted)
                .update();
        send(admin, post(BASE_PATH), body(f, restricted, f.columns.get("emp_no"), ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_KEY"));

        long normal = f.properties.get("emp_name");
        createMapping(admin, f, normal, f.columns.get("emp_no"), "", true);
        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + normal),
                        sensitivityBody(normal, "RESTRICTED", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_KEY"));

        createMapping(admin, f, restricted, f.columns.get("hire_date"), "", true);
        send(
                        admin,
                        patch("/api/v1/ontology/object-mappings/" + f.mapping),
                        objectMappingBody(f, "\"emp_no\", \"hire_date\"", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_KEY"));
    }

    private long storeObject(Fixture f, String properties) {
        return jdbcClient
                .sql(
                        """
                        insert into object_instance (object_type_id, source_mapping_id, source_key, display_name, properties)
                        select p.object_type_id, :mapping, cast(:key as jsonb), 'obj', cast(:properties as jsonb)
                        from property_definition p where p.id = :property
                        returning id
                        """)
                .param("mapping", f.mapping)
                .param("key", "{\"emp_no\": \"" + UUID.randomUUID() + "\"}")
                .param("properties", properties)
                .param("property", f.properties.get("emp_no"))
                .query(Long.class)
                .single();
    }

    private String sensitivityBody(long property, String sensitivity, String extra) {
        PropertyRow row =
                jdbcClient
                        .sql(
                                "select code, data_type, row_version from property_definition where id = :id")
                        .param("id", property)
                        .query(PropertyRow.class)
                        .single();
        return """
                {"name": "%s", "dataType": "%s", "isRequired": false, "sensitivity": "%s",
                 "rowVersion": %d%s}
                """
                .formatted(row.code(), row.dataType(), sensitivity, row.rowVersion(), extra);
    }

    private String objectMappingBody(Fixture f, String keys, boolean active) {
        Map<String, Object> row =
                jdbcClient
                        .sql(
                                "select object_type_id, row_version from object_source_mapping where id = :id")
                        .param("id", f.mapping)
                        .query()
                        .singleRow();
        return """
                {"sourceResourceId": %d, "objectTypeId": %d, "keyColumns": [%s], "rowFilter": {},
                 "isActive": %s, "rowVersion": %d}
                """
                .formatted(
                        f.resource,
                        ((Number) row.get("object_type_id")).longValue(),
                        keys,
                        active,
                        ((Number) row.get("row_version")).longValue());
    }

    private record PropertyRow(String code, String dataType, long rowVersion) {}

    /**
     * 민감 컬럼을 매핑하면 속성을 민감으로 올리고, 대표 속성이었으면 대표를 비운다. 그 뒤 속성을 일반으로 내리면 거절한다. 식별 속성에는 민감 컬럼을 매핑할 수
     * 없다(391).
     */
    @Test
    void restrictedSourceColumnRaisesThePropertyAndBlocksLowering() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        renameResource(f, "mes.worker");
        long skill = f.columns.get("emp_name");
        jdbcClient
                .sql("update source_column set column_name = 'skill_level' where id = :id")
                .param("id", skill)
                .update();
        long property = f.properties.get("emp_name");
        jdbcClient
                .sql(
                        """
                        update object_type set display_property_id = :property
                        where id = (select object_type_id from property_definition where id = :property)
                        """)
                .param("property", property)
                .update();

        long created = createMapping(admin, f, property, skill, "", true);
        // 속성 민감 승격과 대표 비움도 이 편집의 변경으로 남는다.
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select c.table_name from entity_change c
                                        join action_execution e on e.id = c.action_execution_id
                                        where e.target_type = 'property_source_mapping' and e.target_id = :id
                                          and e.status = 'SUCCEEDED'
                                        order by c.id
                                        """)
                                .param("id", created)
                                .query(String.class)
                                .list())
                .containsExactly("property_source_mapping", "property_definition", "object_type");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select p.sensitivity || '/' || coalesce(t.display_property_id::text, '-')
                                        from property_definition p join object_type t on t.id = p.object_type_id
                                        where p.id = :id
                                        """)
                                .param("id", property)
                                .query(String.class)
                                .single())
                .isEqualTo("RESTRICTED/-");
        long rowVersion =
                jdbcClient
                        .sql("select row_version from property_definition where id = :id")
                        .param("id", property)
                        .query(Long.class)
                        .single();
        send(
                        admin,
                        patch("/api/v1/ontology/properties/" + property),
                        """
                        {"name": "emp_name", "dataType": "STRING", "isRequired": false,
                         "sensitivity": "NORMAL", "rowVersion": %d}
                        """
                                .formatted(rowVersion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.column").value("skill_level"));

        long identifier = f.properties.get("emp_no");
        jdbcClient
                .sql("update property_definition set is_identifier = true where id = :id")
                .param("id", identifier)
                .update();
        send(admin, post(BASE_PATH), body(f, identifier, skill, ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.column").value("skill_level"));
    }

    /**
     * 391: 391 이전에 민감 컬럼을 일반 속성에 매핑해 둔 표는 적재 계획이 실패해 CDC가 건너뛴다. 관리자가 속성을 민감으로 올리면(028) 그 표를 다시 읽어
     * 빠진 변경을 되찾는다. 이미 민감인 속성을 다시 저장하면 다시 읽지 않는다.
     */
    @Test
    void raisingAPre391PropertyToRestrictedRequestsReload() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        Fixture f = fixture(4096L, true);
        renameResource(f, "mes.worker");
        long skill = f.columns.get("emp_name");
        jdbcClient
                .sql("update source_column set column_name = 'skill_level' where id = :id")
                .param("id", skill)
                .update();
        long property = f.properties.get("emp_name");
        // 391 이전처럼 서비스를 거치지 않고 일반 속성에 민감 컬럼을 매핑해 둔다.
        jdbcClient
                .sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        """)
                .param("mapping", f.mapping)
                .param("resource", f.resource)
                .param("property", property)
                .param("column", skill)
                .update();
        setLoadedLsn(f.resource, 8192L);

        send(admin, patch("/api/v1/ontology/properties/" + property), restrict(property))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sensitivity").value("RESTRICTED"));
        assertThat(loadedLsn(f.resource)).isNull();

        setLoadedLsn(f.resource, 8192L);
        send(admin, patch("/api/v1/ontology/properties/" + property), restrict(property))
                .andExpect(status().isOk());
        assertThat(loadedLsn(f.resource)).isEqualTo(8192L);
    }

    private String restrict(long property) {
        long rowVersion =
                jdbcClient
                        .sql("select row_version from property_definition where id = :id")
                        .param("id", property)
                        .query(Long.class)
                        .single();
        return """
                {"name": "skill", "dataType": "STRING", "isRequired": false,
                 "sensitivity": "RESTRICTED", "rowVersion": %d}
                """
                .formatted(rowVersion);
    }

    private void renameResource(Fixture f, String resourceKey) {
        jdbcClient
                .sql("update source_resource set resource_key = :key where id = :id")
                .param("key", resourceKey)
                .param("id", f.resource)
                .update();
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
                                values (:source, 'erp.staff', 'TABLE', true, 'CDC', :lsn)
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

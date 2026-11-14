package com.axcore.platform.ontology;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** 속성 관리(API 026~029)를 실제 보안 설정과 DB로 확인한다. 기존 객체 값과의 충돌은 SQL로 검사하므로 여기서 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class PropertyApiTests {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsAndListsProperties() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();

        send(
                        admin,
                        post(propertiesPath(typeId)),
                        """
                        {"code": "tonnage", "name": "톤수", "dataType": "DECIMAL", "isRequired": true,
                         "unit": "ton", "constraints": {"min": 0}, "description": "설비의 정격 톤수"}
                        """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.objectTypeId").value(typeId))
                .andExpect(jsonPath("$.data.isRequired").value(true))
                .andExpect(jsonPath("$.data.isIdentifier").value(false))
                .andExpect(jsonPath("$.data.sensitivity").value("NORMAL"))
                .andExpect(jsonPath("$.data.constraints.min").value(0))
                .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                .andExpect(jsonPath("$.data.rowVersion").value(0));
        createProperty(admin, typeId, "status", "STRING");

        mockMvc.perform(get(propertiesPath(typeId)).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value("tonnage"))
                .andExpect(jsonPath("$.data[1].code").value("status"));
        mockMvc.perform(get(propertiesPath(Long.MAX_VALUE)).cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"code\": \"Tonnage\", \"name\": \"톤수\", \"dataType\": \"STRING\", \"isRequired\": false} | code",
                "{\"code\": \"tonnage\", \"name\": \"톤수\", \"dataType\": \"NUMBER\", \"isRequired\": false} | dataType",
                "{\"code\": \"tonnage\", \"name\": \"톤수\", \"dataType\": \"STRING\"} | isRequired",
                "{\"code\": \"base_date\", \"name\": \"기준일\", \"dataType\": \"STRING\", \"isRequired\": false, \"temporalRole\": \"AS_OF\"} | temporalRole",
                "{\"code\": \"tonnage\", \"name\": \"톤수\", \"dataType\": \"INTEGER\", \"isRequired\": false, \"constraints\": {\"maxLength\": 3}} | constraints",
                "{\"code\": \"tonnage\", \"name\": \"톤수\", \"dataType\": \"INTEGER\", \"isRequired\": false, \"constraints\": {\"precision\": 2}} | constraints",
                "{\"code\": \"grade\", \"name\": \"등급\", \"dataType\": \"INTEGER\", \"isRequired\": false, \"constraints\": {\"allowedValues\": [1, \"A\"]}} | constraints"
            })
    void rejectsInvalidCreateRequest(String body, String field) throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();

        send(admin, post(propertiesPath(typeId)), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value(field));
    }

    @Test
    void rejectsDuplicateCodeOnlyInSameType() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long otherTypeId = insertObjectType();
        createProperty(admin, typeId, "status", "STRING");

        send(
                        admin,
                        post(propertiesPath(typeId)),
                        "{\"code\": \"status\", \"name\": \"상태\", \"dataType\": \"STRING\", \"isRequired\": false}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        createProperty(admin, otherTypeId, "status", "STRING");
    }

    @Test
    void allowsOnlyAdminToManage() throws Exception {
        LoginSession viewer = loginAs("VIEWER");
        long typeId = insertObjectType();

        // 그룹이 없는 유형은 조회 사용자에게 없는 유형과 같다
        mockMvc.perform(get(propertiesPath(typeId)).cookie(viewer.session()))
                .andExpect(status().isNotFound());
        send(
                        viewer,
                        post(propertiesPath(typeId)),
                        "{\"code\": \"status\", \"name\": \"상태\", \"dataType\": \"STRING\", \"isRequired\": false}")
                .andExpect(status().isForbidden());
    }

    @Test
    void listsViewablePropertiesForNonAdmin() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        LoginSession viewer = loginAs("VIEWER");
        LoginSession planner = loginAs("PLANNER");
        long typeId = insertObjectType();
        jdbcClient
                .sql("update object_type set access_group = 'PRODUCTION' where id = :id")
                .param("id", typeId)
                .update();
        createProperty(admin, typeId, "status", "STRING");
        long restrictedId = createProperty(admin, typeId, "technician", "STRING");
        jdbcClient
                .sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", restrictedId)
                .update();

        // 관리자는 정의를 관리하므로 민감 속성도 목록에 있다
        mockMvc.perform(get(propertiesPath(typeId)).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        // 검색 조건용(scope=viewable)이면 관리자도 객체 조회 범위로 받는다(420). 관리자는 민감 값을 볼 수 없다
        mockMvc.perform(get(propertiesPath(typeId) + "?scope=viewable").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("status"));
        // 민감 값을 볼 수 없는 역할은 민감 속성을 받지 않는다(그 속성으로 검색할 수 없다)
        mockMvc.perform(get(propertiesPath(typeId)).cookie(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("status"));
        // 민감 값을 볼 수 있는 역할은 모두 받는다
        mockMvc.perform(get(propertiesPath(typeId)).cookie(planner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void rejectsMakingDisplayPropertyRestricted() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long propertyId = createProperty(admin, typeId, "machine_no", "STRING");
        jdbcClient
                .sql("update object_type set display_property_id = :property where id = :type")
                .param("property", propertyId)
                .param("type", typeId)
                .update();

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        """
                        {"name": "설비 번호", "dataType": "STRING", "isRequired": false,
                         "sensitivity": "RESTRICTED", "rowVersion": 0}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value("sensitivity"));
        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        """
                        {"name": "설비 번호", "dataType": "STRING", "isRequired": false, "rowVersion": 0}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sensitivity").value("NORMAL"));
    }

    @Test
    void updatesKeepingOmittedFieldsAndChecksVersion() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long propertyId =
                idOf(
                        send(
                                        admin,
                                        post(propertiesPath(typeId)),
                                        """
                                        {"code": "tonnage", "name": "톤수", "dataType": "DECIMAL",
                                         "isRequired": false, "unit": "ton", "sensitivity": "RESTRICTED"}
                                        """)
                                .andReturn());

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        "{\"name\": \"정격 톤수\", \"dataType\": \"DECIMAL\", \"isRequired\": false, \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("정격 톤수"))
                .andExpect(jsonPath("$.data.unit").value("ton"))
                .andExpect(jsonPath("$.data.sensitivity").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        "{\"name\": \"톤수\", \"dataType\": \"DECIMAL\", \"isRequired\": false, \"rowVersion\": 0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        "{\"code\": \"capacity\", \"name\": \"톤수\", \"dataType\": \"DECIMAL\", \"isRequired\": false, \"rowVersion\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("code"));

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        "{\"name\": \"톤수\", \"dataType\": \"DECIMAL\", \"isRequired\": false, \"unit\": null, \"rowVersion\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").isEmpty())
                .andExpect(jsonPath("$.data.code").value("tonnage"));
    }

    @Test
    void rejectsTypeChangeThatBreaksExistingValues() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long propertyId = createProperty(admin, typeId, "capacity", "STRING");
        long textObject = insertObject(typeId, "{\"capacity\": \"large\"}");
        insertObject(typeId, "{\"capacity\": null}");
        insertObject(typeId, "{}");

        updateType(admin, propertyId, "INTEGER", 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value("dataType"))
                .andExpect(jsonPath("$.error.details.count").value(1))
                .andExpect(jsonPath("$.error.details.objectIds[0]").value(textObject));

        jdbcClient
                .sql("update object_instance set properties = '{\"capacity\": 400}' where id = :id")
                .param("id", textObject)
                .update();
        updateType(admin, propertyId, "INTEGER", 0).andExpect(status().isOk());
        updateType(admin, propertyId, "DECIMAL", 1).andExpect(status().isOk());
    }

    @Test
    void checksDateTimeFormat() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long propertyId = createProperty(admin, typeId, "started_at", "STRING");
        insertObject(typeId, "{\"started_at\": \"2026-10-28T12:00:00+09:00\"}");
        long noOffset = insertObject(typeId, "{\"started_at\": \"2026-10-28T12:00:00\"}");

        updateType(admin, propertyId, "DATETIME", 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.objectIds[0]").value(noOffset));
    }

    @Test
    void rejectsRequiredWhenValuesAreMissing() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long propertyId = createProperty(admin, typeId, "status", "STRING");
        insertObject(typeId, "{\"status\": \"RUN\"}");
        long missing = insertObject(typeId, "{}");

        send(
                        admin,
                        patch(propertyPath(propertyId)),
                        "{\"name\": \"상태\", \"dataType\": \"STRING\", \"isRequired\": true, \"rowVersion\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("isRequired"))
                .andExpect(jsonPath("$.error.details.objectIds[0]").value(missing));
    }

    @Test
    void rejectsConstraintsThatBreakExistingValues() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long capacityId = createProperty(admin, typeId, "capacity", "INTEGER");
        long statusId = createProperty(admin, typeId, "status", "STRING");
        long small = insertObject(typeId, "{\"capacity\": 5, \"status\": \"RUN\"}");
        insertObject(typeId, "{\"capacity\": 400, \"status\": \"STOP\"}");

        send(
                        admin,
                        patch(propertyPath(capacityId)),
                        "{\"name\": \"용량\", \"dataType\": \"INTEGER\", \"isRequired\": false,"
                                + " \"constraints\": {\"min\": 10}, \"rowVersion\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("constraints"))
                .andExpect(jsonPath("$.error.details.objectIds[0]").value(small));

        send(
                        admin,
                        patch(propertyPath(statusId)),
                        "{\"name\": \"상태\", \"dataType\": \"STRING\", \"isRequired\": false,"
                                + " \"constraints\": {\"allowedValues\": [\"RUN\", \"STOP\"]}, \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.constraints.allowedValues.length()").value(2));
        send(
                        admin,
                        patch(propertyPath(statusId)),
                        "{\"name\": \"상태\", \"dataType\": \"STRING\", \"isRequired\": false,"
                                + " \"constraints\": {\"allowedValues\": [\"RUN\"]}, \"rowVersion\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.count").value(1));
    }

    @Test
    void deletesOnlyUnusedProperty() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();
        long displayId = createProperty(admin, typeId, "name", "STRING");
        long valuedId = createProperty(admin, typeId, "status", "STRING");
        long unusedId = createProperty(admin, typeId, "memo", "STRING");
        jdbcClient
                .sql("update object_type set display_property_id = :propertyId where id = :typeId")
                .param("propertyId", displayId)
                .param("typeId", typeId)
                .update();
        long object = insertObject(typeId, "{\"status\": \"RUN\", \"memo\": null}");

        send(admin, delete(propertyPath(displayId)), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IN_USE"))
                .andExpect(jsonPath("$.error.details.references[0].tableName").value("object_type"))
                .andExpect(jsonPath("$.error.details.references[0].ids[0]").value(typeId));
        send(admin, delete(propertyPath(valuedId)), null)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.details.references[0].tableName")
                                .value("object_instance"))
                .andExpect(jsonPath("$.error.details.references[0].ids[0]").value(object));

        send(admin, delete(propertyPath(unusedId)), null).andExpect(status().isNoContent());
        mockMvc.perform(get(propertiesPath(typeId)).cookie(admin.session()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private ResultActions updateType(
            LoginSession admin, long propertyId, String dataType, long rowVersion)
            throws Exception {
        return send(
                admin,
                patch(propertyPath(propertyId)),
                "{\"name\": \"속성\", \"dataType\": \"%s\", \"isRequired\": false, \"rowVersion\": %d}"
                        .formatted(dataType, rowVersion));
    }

    private long createProperty(LoginSession admin, long typeId, String code, String dataType)
            throws Exception {
        MvcResult result =
                send(
                                admin,
                                post(propertiesPath(typeId)),
                                "{\"code\": \"%s\", \"name\": \"속성\", \"dataType\": \"%s\", \"isRequired\": false}"
                                        .formatted(code, dataType))
                        .andExpect(status().isCreated())
                        .andReturn();
        return idOf(result);
    }

    private long insertObjectType() {
        return jdbcClient
                .sql("insert into object_type (code, name) values (:code, '설비') returning id")
                .param(
                        "code",
                        "TYPE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .query(Long.class)
                .single();
    }

    /** 원천 매핑 없이 만든 플랫폼 객체. properties는 JSON 객체 문자열이다. */
    private long insertObject(long typeId, String properties) {
        return jdbcClient
                .sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:typeId, '객체', cast(:properties as jsonb))
                        returning id
                        """)
                .param("typeId", typeId)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }

    private static String propertiesPath(long typeId) {
        return "/api/v1/ontology/object-types/" + typeId + "/properties";
    }

    private static String propertyPath(long propertyId) {
        return "/api/v1/ontology/properties/" + propertyId;
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

    private static long idOf(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return id.longValue();
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

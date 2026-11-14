package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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

/** 객체 유형 관리(API 021~025)를 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectTypeApiTests {

    private static final String BASE_PATH = "/api/v1/ontology/object-types";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsAndReadsObjectType() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String code = uniqueCode();

        MvcResult created =
                send(
                                admin,
                                post(BASE_PATH),
                                """
                                {"code": "%s", "name": "설비", "description": "생산 설비",
                                 "synonyms": ["기계", "Machine"], "accessGroup": "production"}
                                """
                                        .formatted(code))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.code").value(code))
                        .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                        .andExpect(jsonPath("$.data.displayPropertyId").isEmpty())
                        .andExpect(jsonPath("$.data.proposalItemId").isEmpty())
                        .andExpect(jsonPath("$.data.rowVersion").value(0))
                        .andReturn();
        long id = idOf(created);

        mockMvc.perform(get(BASE_PATH + "/" + id).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.synonyms[1]").value("Machine"));
        mockMvc.perform(get(BASE_PATH).param("q", "machine").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code", hasItem(code)))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20));
        Long createdBy =
                jdbcClient
                        .sql("select created_by from object_type where id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single();
        assertThat(createdBy).as("등록한 관리자가 남는다").isEqualTo(admin.accountId());
    }

    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        LoginSession admin = loginAs("ADMIN");

        send(admin, post(BASE_PATH), "{\"code\": \"equipment\", \"name\": \"설비\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("code"));
        send(admin, post(BASE_PATH), "{\"code\": \"%s\"}".formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("name"));
        send(
                        admin,
                        post(BASE_PATH),
                        "{\"code\": \"%s\", \"name\": \"설비\", \"displayPropertyId\": 1}"
                                .formatted(uniqueCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("displayPropertyId"));
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        String code = uniqueCode();
        createObjectType(admin, code);

        send(admin, post(BASE_PATH), "{\"code\": \"%s\", \"name\": \"다른 설비\"}".formatted(code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        mockMvc.perform(get(BASE_PATH).cookie(viewer.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        send(
                        viewer,
                        post(BASE_PATH),
                        "{\"code\": \"%s\", \"name\": \"설비\"}".formatted(uniqueCode()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsMissingObjectType() throws Exception {
        LoginSession admin = loginAs("ADMIN");

        mockMvc.perform(get(BASE_PATH + "/" + Long.MAX_VALUE).cookie(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updatesOnlySentFieldsAndChecksVersion() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long id = createObjectType(admin, uniqueCode());

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"프레스 설비\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("프레스 설비"))
                .andExpect(jsonPath("$.data.description").value("생산 설비"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \"설비\", \"rowVersion\": 0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.expected").value(0))
                .andExpect(jsonPath("$.error.details.actual").value(1));

        send(admin, patch(BASE_PATH + "/" + id), "{\"name\": \" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.errors[*].field", hasItem("rowVersion")))
                .andExpect(jsonPath("$.error.details.errors[*].field", hasItem("name")));

        send(admin, patch(BASE_PATH + "/" + id), "{\"description\": null, \"rowVersion\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").isEmpty())
                .andExpect(jsonPath("$.data.name").value("프레스 설비"));
    }

    @Test
    void setsDisplayPropertyOnlyFromSameType() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = createObjectType(admin, uniqueCode());
        long otherTypeId = createObjectType(admin, uniqueCode());
        long propertyId = insertProperty(typeId);
        long otherPropertyId = insertProperty(otherTypeId);

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"displayPropertyId\": %d, \"rowVersion\": 0}".formatted(otherPropertyId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("대표 속성은 같은 객체 유형의 속성이어야 합니다."));

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"displayPropertyId\": %d, \"rowVersion\": 0}".formatted(propertyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayPropertyId").value(propertyId));

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"displayPropertyId\": null, \"rowVersion\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayPropertyId").isEmpty());
    }

    @Test
    void marksMappedTablesForReloadOnlyWhenDisplayPropertyChanges() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = createObjectType(admin, uniqueCode());
        long propertyId = insertProperty(typeId);
        long mappedTable = insertCdcTable(100L);
        long stoppedTable = insertCdcTable(200L);
        insertMapping(mappedTable, typeId, true);
        insertMapping(stoppedTable, typeId, false);

        send(admin, patch(BASE_PATH + "/" + typeId), "{\"name\": \"프레스 설비\", \"rowVersion\": 0}")
                .andExpect(status().isOk());
        assertThat(loadedLsn(mappedTable)).isEqualTo(100L);

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"displayPropertyId\": %d, \"rowVersion\": 1}".formatted(propertyId))
                .andExpect(status().isOk());
        assertThat(loadedLsn(mappedTable)).isNull();
        assertThat(loadedLsn(stoppedTable)).isEqualTo(200L);
    }

    @Test
    void rejectsRestrictedPropertyAsDisplayProperty() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = createObjectType(admin, uniqueCode());
        long propertyId = insertProperty(typeId);
        jdbcClient
                .sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", propertyId)
                .update();

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"displayPropertyId\": %d, \"rowVersion\": 0}".formatted(propertyId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("민감 속성은 대표 속성으로 지정할 수 없습니다."))
                .andExpect(jsonPath("$.error.details.field").value("displayPropertyId"));
    }

    /**
     * 공개 전 검사(402). 켜진 매핑이 원천 데이터 정책을 어기면 그룹을 지정할 수 없다. 매핑을 고치면 지정할 수 있다. 그룹을 비우는 것과 그룹을 그대로 둔 다른
     * 수정은 막지 않는다.
     */
    @Test
    void refusesToPublishTypeWhoseMappingBreaksSourcePolicy() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = createObjectType(admin, uniqueCode());
        long table = insertCdcTable(100L);
        jdbcClient
                .sql("update source_resource set resource_key = 'erp.employee' where id = :id")
                .param("id", table)
                .update();
        long mapping = insertMappingReturningId(table, typeId, "emp_no");
        long otherType = createObjectType(admin, uniqueCode());
        long otherTable = insertCdcTable(100L);
        insertMapping(otherTable, otherType, true);
        long property = insertProperty(typeId);
        long column = insertColumn(table, "emp_name");
        long propertyMapping = insertPropertyMapping(table, mapping, property, column);

        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"accessGroup\": \"PRODUCTION\", \"rowVersion\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value("accessGroup"))
                .andExpect(jsonPath("$.error.details.violations[0].kind").value("FORBIDDEN_COLUMN"))
                .andExpect(
                        jsonPath("$.error.details.violations[0].resourceKey").value("erp.employee"))
                .andExpect(jsonPath("$.error.details.violations[0].columnName").value("emp_name"))
                .andExpect(
                        jsonPath("$.error.details.violations[0].propertyMappingId")
                                .value(propertyMapping));
        // 위반이 없는 다른 유형은 지정할 수 있다.
        send(
                        admin,
                        patch(BASE_PATH + "/" + otherType),
                        "{\"accessGroup\": \"PRODUCTION\", \"rowVersion\": 0}")
                .andExpect(status().isOk());

        jdbcClient
                .sql("delete from property_source_mapping where id = :id")
                .param("id", propertyMapping)
                .update();
        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"accessGroup\": \"PRODUCTION\", \"rowVersion\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessGroup").value("PRODUCTION"));

        // 공개 뒤 정책이 바뀌어 위반이 생겨도 그룹을 비우거나 그룹을 그대로 둔 수정은 된다.
        insertPropertyMapping(table, mapping, property, column);
        send(admin, patch(BASE_PATH + "/" + typeId), "{\"name\": \"직원\", \"rowVersion\": 1}")
                .andExpect(status().isOk());
        send(
                        admin,
                        patch(BASE_PATH + "/" + typeId),
                        "{\"accessGroup\": \"QUALITY\", \"rowVersion\": 2}")
                .andExpect(status().isUnprocessableContent());
        send(admin, patch(BASE_PATH + "/" + typeId), "{\"accessGroup\": null, \"rowVersion\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessGroup").isEmpty());
    }

    @Test
    void deletesUnusedTypeButNotTypeInUse() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long unusedId = createObjectType(admin, uniqueCode());
        long usedId = createObjectType(admin, uniqueCode());
        long propertyId = insertProperty(usedId);

        send(admin, delete(BASE_PATH + "/" + usedId), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IN_USE"))
                .andExpect(
                        jsonPath("$.error.details.references[0].tableName")
                                .value("property_definition"))
                .andExpect(jsonPath("$.error.details.references[0].count").value(1))
                .andExpect(jsonPath("$.error.details.references[0].ids[0]").value(propertyId));

        send(admin, delete(BASE_PATH + "/" + unusedId), null).andExpect(status().isNoContent());
        mockMvc.perform(get(BASE_PATH + "/" + unusedId).cookie(admin.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE_PATH + "/" + usedId).cookie(admin.session()))
                .andExpect(status().isOk());
    }

    private long createObjectType(LoginSession admin, String code) throws Exception {
        MvcResult result =
                send(
                                admin,
                                post(BASE_PATH),
                                "{\"code\": \"%s\", \"name\": \"설비\", \"description\": \"생산 설비\"}"
                                        .formatted(code))
                        .andExpect(status().isCreated())
                        .andReturn();
        return idOf(result);
    }

    private long insertProperty(long objectTypeId) {
        return jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:objectTypeId, 'name', '이름', 'STRING')
                        returning id
                        """)
                .param("objectTypeId", objectTypeId)
                .query(Long.class)
                .single();
    }

    private long insertCdcTable(Long loadedLsn) {
        long source =
                jdbcClient
                        .sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, 'MES', 'POSTGRES') returning id
                                """)
                        .param("code", uniqueCode())
                        .query(Long.class)
                        .single();
        return jdbcClient
                .sql(
                        """
                        insert into source_resource
                            (data_source_id, resource_key, resource_kind, is_readable, sync_strategy, loaded_lsn)
                        values (:source, 'mes.equipment', 'TABLE', true, 'CDC', :lsn)
                        returning id
                        """)
                .param("source", source)
                .param("lsn", loadedLsn, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    private void insertMapping(long resourceId, long objectTypeId, boolean active) {
        jdbcClient
                .sql(
                        """
                        insert into object_source_mapping
                            (source_resource_id, object_type_id, key_columns, is_active)
                        values (:resource, :type, '["code"]'::jsonb, :active)
                        """)
                .param("resource", resourceId)
                .param("type", objectTypeId)
                .param("active", active)
                .update();
    }

    private long insertMappingReturningId(long resourceId, long objectTypeId, String key) {
        return jdbcClient
                .sql(
                        """
                        insert into object_source_mapping
                            (source_resource_id, object_type_id, key_columns, is_active)
                        values (:resource, :type, jsonb_build_array(:key), true)
                        returning id
                        """)
                .param("resource", resourceId)
                .param("type", objectTypeId)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long insertColumn(long resourceId, String name) {
        return jdbcClient
                .sql(
                        """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, is_nullable,
                             ordinal_position, is_present)
                        values (:resource, :name, 'varchar', false, 1, true)
                        returning id
                        """)
                .param("resource", resourceId)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    /** 서비스는 정책상 금지 컬럼 매핑을 거절하므로(391) 행으로 넣는다. 391 이전 매핑이나 정책이 바뀐 경우다. */
    private long insertPropertyMapping(
            long resourceId, long mappingId, long propertyId, long columnId) {
        return jdbcClient
                .sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        returning id
                        """)
                .param("mapping", mappingId)
                .param("resource", resourceId)
                .param("property", propertyId)
                .param("column", columnId)
                .query(Long.class)
                .single();
    }

    private Long loadedLsn(long resourceId) {
        return jdbcClient
                .sql("select loaded_lsn from source_resource where id = :id")
                .param("id", resourceId)
                .query((rs, row) -> rs.getObject(1, Long.class))
                .list()
                .getFirst();
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

    private static String uniqueCode() {
        return "TYPE_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private LoginSession loginAs(String roleCode) throws Exception {
        String loginId = "user-" + UUID.randomUUID();
        Long accountId =
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
                accountId,
                result.getResponse().getCookie("SESSION"),
                result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private record LoginSession(Long accountId, Cookie session, Cookie csrf) {}
}

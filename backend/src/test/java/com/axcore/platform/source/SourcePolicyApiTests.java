package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
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

/**
 * 원천 데이터 정책 위반 보고(S15P31C106-402)를 실제 보안 설정과 DB로 확인한다. 서비스는 위반 매핑을 거절하므로(391) 행으로 넣는다. 같은 DB를 다른
 * 시험과 함께 쓰므로 이 시험이 만든 테이블의 결과만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourcePolicyApiTests {

    private static final String PATH = "/api/v1/sources/policy-violations";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void reportsViolationsOfActiveMappingsAndTablesThatCannotBeLoaded() throws Exception {
        long source = insertSource();
        long type = insertType("PRODUCTION");
        long employee = insertTable(source, "erp.employee");
        long employeeMapping = insertMapping(employee, type, "emp_no", true);
        long name = insertProperty(type, "emp_name", "NORMAL");
        long nameMapping =
                insertPropertyMapping(
                        employee, employeeMapping, name, insertColumn(employee, "emp_name"));

        long worker = insertTable(source, "mes.worker");
        long workerMapping = insertMapping(worker, type, "id", true);
        long skill = insertProperty(type, "skill_level", "NORMAL");
        insertPropertyMapping(worker, workerMapping, skill, insertColumn(worker, "skill_level"));

        long order = insertTable(source, "mes.maintenance_order");
        insertMapping(order, type, "technician", true);

        // 꺼진 매핑과 위반 없는 매핑은 나오지 않는다.
        long stopped = insertTable(source, "erp.attendance");
        insertMapping(stopped, type, "emp_no", false);
        long clean = insertTable(source, "erp.department");
        insertMapping(clean, type, "dept_code", true);

        MvcResult result =
                mockMvc.perform(get(PATH).cookie(loginAs("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.policyVersion").isNotEmpty())
                        .andReturn();
        String body = result.getResponse().getContentAsString();

        List<Map<String, Object>> violations =
                JsonPath.read(body, "$.data.violations[?(@.objectTypeId == %d)]".formatted(type));
        assertThat(violations)
                .extracting(v -> v.get("kind"), v -> v.get("resourceKey"), v -> v.get("columnName"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "FORBIDDEN_COLUMN", "erp.employee", "emp_name"),
                        org.assertj.core.groups.Tuple.tuple(
                                "RESTRICTED_UNDER_CLASSIFIED", "mes.worker", "skill_level"),
                        org.assertj.core.groups.Tuple.tuple(
                                "RESTRICTED_KEY", "mes.maintenance_order", "technician"));
        Map<String, Object> forbidden =
                violations.stream()
                        .filter(v -> "FORBIDDEN_COLUMN".equals(v.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertThat(((Number) forbidden.get("propertyMappingId")).longValue())
                .isEqualTo(nameMapping);
        assertThat(forbidden.get("propertyCode")).isEqualTo("emp_name");
        assertThat(forbidden.get("accessGroup")).isEqualTo("PRODUCTION");

        List<Map<String, Object>> blocked =
                JsonPath.read(
                        body, "$.data.blockedResources[?(@.dataSourceId == %d)]".formatted(source));
        assertThat(blocked)
                .extracting(b -> ((Number) b.get("sourceResourceId")).longValue())
                .containsExactlyInAnyOrder(employee, worker, order);
        assertThat(blocked)
                .allSatisfy(
                        b -> {
                            assertThat((String) b.get("reason")).startsWith("원천 데이터 정책상 ");
                            assertThat(b.get("reloadPending")).isEqualTo(false);
                        });
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).cookie(loginAs("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
    }

    private long insertSource() {
        return jdbc.sql(
                        """
                        insert into data_source (code, name, source_kind)
                        values (:code, '정책 보고 원천', 'POSTGRES') returning id
                        """)
                .param("code", "PV_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .query(Long.class)
                .single();
    }

    private long insertType(String accessGroup) {
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values ('T-' || gen_random_uuid(), '정책 보고 유형', :group) returning id
                        """)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private long insertTable(long source, String key) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy, loaded_lsn)
                        values (:source, :key, 'TABLE', true, 'CDC', 10) returning id
                        """)
                .param("source", source)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long insertMapping(long resource, long type, String key, boolean active) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id,
                                                           key_columns, is_active)
                        values (:resource, :type, jsonb_build_array(:key), :active) returning id
                        """)
                .param("resource", resource)
                .param("type", type)
                .param("key", key)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private long insertProperty(long type, String code, String sensitivity) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity) returning id
                        """)
                .param("type", type)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .query(Long.class)
                .single();
    }

    private long insertColumn(long resource, String name) {
        return jdbc.sql(
                        """
                        insert into source_column (source_resource_id, column_name, source_data_type,
                                                   is_nullable, ordinal_position, is_present)
                        values (:resource, :name, 'varchar', false, 1, true) returning id
                        """)
                .param("resource", resource)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertPropertyMapping(long resource, long mapping, long property, long column) {
        return jdbc.sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column) returning id
                        """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", property)
                .param("column", column)
                .query(Long.class)
                .single();
    }

    private Cookie loginAs(String roleCode) throws Exception {
        String loginId = "policy-" + UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id,
                                             must_change_password)
                        values (:loginId, '정책 보고 사용자', :hash,
                                (select id from role where code = :roleCode), false)
                        """)
                .param("loginId", loginId)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("roleCode", roleCode)
                .update();
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                .formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
    }
}

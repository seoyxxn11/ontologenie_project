package com.axcore.platform.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 매핑 시험 변환 접수(API 040)와 원천 미리보기 결과 조회(API 102)를 실제 보안 설정과 DB로 확인한다. 원천은 읽지 않는다(배치가 읽는다). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class MappingPreviewApiTests {

    private static final String PATH = "/api/v1/ontology/source-resources/{id}/mapping-previews";
    private static final String RESULT_PATH = "/api/v1/ontology/source-previews/{previewId}";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private long accountId;
    private long resourceId;
    private long typeId;
    private long otherTypeId;
    private long woNo;
    private long otherProperty;
    private long woNoColumn;
    private long dueColumn;

    @BeforeEach
    void setUp() {
        accountId =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '관리자', 'not-used',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("loginId", "preview-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long sourceId =
                jdbc.sql(
                                "insert into data_source (code, name, source_kind) values (:code, '미리보기', 'POSTGRES') returning id")
                        .param(
                                "code",
                                "PRV_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
        resourceId = resource(sourceId, "erp.work_order", true);
        column(resourceId, "id", 1);
        woNoColumn = column(resourceId, "wo_no", 2);
        dueColumn = column(resourceId, "due", 3);
        typeId = type();
        otherTypeId = type();
        woNo = property(typeId, "wo_no", "STRING");
        property(typeId, "due", "DATE");
        otherProperty = property(otherTypeId, "name", "STRING");
    }

    @Test
    void acceptsDraftAndJoinsSamePreview() throws Exception {
        String body = body(propertyJson(woNo, woNoColumn, "[]"), null);

        String response =
                send(post(PATH, resourceId), body, "ADMIN")
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.joined").value(false))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long previewId = ((Number) JsonPath.read(response, "$.data.previewId")).longValue();
        Instant requestedAt = Instant.parse(JsonPath.read(response, "$.data.requestedAt"));
        Instant expiresAt = Instant.parse(JsonPath.read(response, "$.data.expiresAt"));
        assertThat(expiresAt).isEqualTo(requestedAt.plusSeconds(3600));

        Map<String, Object> row =
                jdbc.sql(
                                """
                                select preview_kind, row_limit, status, requested_by, request::text as request
                                from source_preview where id = :id
                                """)
                        .param("id", previewId)
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("preview_kind", "MAPPING_PREVIEW")
                .containsEntry("row_limit", 20)
                .containsEntry("status", "QUEUED")
                .containsEntry("requested_by", accountId);
        assertThat((String) row.get("request"))
                .contains("\"rowFilter\": {}")
                .contains("\"transformSteps\": []")
                .contains("\"sourcePath\": null");

        // 빈 칸을 기본값으로 채워 보내도 같은 초안이라 합류한다. 행 수가 다르면 새로 넣는다.
        send(
                        post(PATH, resourceId),
                        """
                        {"mapping": {"objectTypeId": %d, "keyColumns": ["id"], "rowFilter": {},
                                     "propertyMappings": [%s]},
                         "rowLimit": 20}
                        """
                                .formatted(typeId, propertyJson(woNo, woNoColumn, "[]")),
                        "ADMIN")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.previewId").value(previewId))
                .andExpect(jsonPath("$.data.joined").value(true));
        send(post(PATH, resourceId), body(propertyJson(woNo, woNoColumn, "[]"), 5), "ADMIN")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.joined").value(false));

        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from action_execution
                                        where event_kind = 'SOURCE_PREVIEW' and target_id = :id
                                          and actor_id = :account
                                        """)
                                .param("id", previewId)
                                .param("account", accountId)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void rejectsMalformedDraft() throws Exception {
        send(post(PATH, resourceId), "{\"rowLimit\": 5}", "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        send(
                        post(PATH, resourceId),
                        "{\"mapping\": {\"objectTypeId\": %d, \"keyColumns\": []}}"
                                .formatted(typeId),
                        "ADMIN")
                .andExpect(status().isBadRequest());
        send(post(PATH, resourceId), body(propertyJson(woNo, woNoColumn, "[]"), 21), "ADMIN")
                .andExpect(status().isBadRequest());
        send(
                        post(PATH, resourceId),
                        body(
                                """
                                {"propertyDefinitionId": %d, "sourceColumnId": %d, "sourcePath": "a.b"}
                                """
                                        .formatted(woNo, woNoColumn),
                                null),
                        "ADMIN")
                .andExpect(status().isBadRequest());
        send(
                        post(PATH, resourceId),
                        body(
                                propertyJson(
                                        woNo, woNoColumn, "[{\"op\": \"NO_SUCH\", \"arg\": null}]"),
                                null),
                        "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.details.field")
                                .value("mapping.propertyMappings[0].transformSteps[0].op"));
        send(
                        post(PATH, resourceId),
                        body(
                                propertyJson(woNo, woNoColumn, "[]")
                                        + ","
                                        + propertyJson(woNo, dueColumn, "[]"),
                                null),
                        "ADMIN")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDraftThatDoesNotFitDefinitions() throws Exception {
        assertViolation(
                """
                {"mapping": {"objectTypeId": 999999999, "keyColumns": ["id"]}}
                """,
                "mapping.objectTypeId");
        assertViolation(
                """
                {"mapping": {"objectTypeId": %d, "keyColumns": ["nope"]}}
                """
                        .formatted(typeId),
                "mapping.keyColumns");
        assertViolation(
                body(propertyJson(otherProperty, woNoColumn, "[]"), null),
                "mapping.propertyMappings[0].propertyDefinitionId");
        assertViolation(
                body(propertyJson(woNo, 999999999L, "[]"), null),
                "mapping.propertyMappings[0].sourceColumnId");
        assertViolation(
                """
                {"mapping": {"objectTypeId": %d, "keyColumns": ["id"], "rowFilter": {"done": false}}}
                """
                        .formatted(typeId),
                "mapping.rowFilter");
        assertViolation(
                body(
                        """
                        {"propertyDefinitionId": %d, "sourcePath": "a.b"}
                        """
                                .formatted(woNo),
                        null),
                "mapping.propertyMappings[0].sourcePath");
        // 정수로 바꾼 값을 DATE 속성에 넣을 수 없다
        long dueProperty =
                jdbc.sql(
                                "select id from property_definition where object_type_id = :t and code = 'due'")
                        .param("t", typeId)
                        .query(Long.class)
                        .single();
        assertViolation(
                body(
                        propertyJson(
                                dueProperty,
                                dueColumn,
                                "[{\"op\": \"TO_INTEGER\", \"arg\": null}]"),
                        null),
                "mapping.propertyMappings[0].transformSteps");

        long unreadable =
                resource(
                        jdbc.sql("select data_source_id from source_resource where id = :id")
                                .param("id", resourceId)
                                .query(Long.class)
                                .single(),
                        "erp.secret",
                        false);
        send(post(PATH, unreadable), body("", null), "ADMIN")
                .andExpect(status().isUnprocessableContent());
        send(post(PATH, 999999999L), body("", null), "ADMIN").andExpect(status().isNotFound());
        assertThat(
                        jdbc.sql(
                                        "select count(*) from source_preview where source_resource_id = :r")
                                .param("r", resourceId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void returnsPreviewResultUntilItExpires() throws Exception {
        long queued = insertPreview("QUEUED", null, null, "now() + interval '1 hour'");
        long succeeded =
                insertPreview(
                        "SUCCEEDED",
                        """
                        {"objects": [{"sourceKey": {"id": "1"}, "displayName": "WO-1", "properties": {"wo_no": "WO-1"}}],
                         "rowErrors": [{"rowIndex": 1, "column": "due", "transform": null, "message": "날짜가 아님"}]}
                        """,
                        null,
                        "now() + interval '1 hour'");
        long failed = insertPreview("FAILED", null, "원천에 접속하지 못했습니다.", "now() + interval '1 hour'");
        long expired =
                insertPreview(
                        "SUCCEEDED",
                        "{\"objects\": [], \"rowErrors\": []}",
                        null,
                        "now() - interval '1 second'");

        mvc.perform(get(RESULT_PATH, queued).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(queued))
                .andExpect(jsonPath("$.data.previewKind").value("MAPPING_PREVIEW"))
                .andExpect(jsonPath("$.data.sourceResourceId").value(resourceId))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.result").value(nullValue()))
                .andExpect(jsonPath("$.data.errorMessage").value(nullValue()));
        mvc.perform(get(RESULT_PATH, succeeded).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.result.objects[0].sourceKey.id").value("1"))
                .andExpect(jsonPath("$.data.result.objects[0].properties.wo_no").value("WO-1"))
                .andExpect(jsonPath("$.data.result.rowErrors[0].column").value("due"))
                .andExpect(jsonPath("$.data.result.rowErrors[0].transform").value(nullValue()));
        mvc.perform(get(RESULT_PATH, failed).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result").value(nullValue()))
                .andExpect(jsonPath("$.data.errorMessage").value("원천에 접속하지 못했습니다."));
        mvc.perform(get(RESULT_PATH, expired).with(actor("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("source_preview"));
        mvc.perform(get(RESULT_PATH, 999999999L).with(actor("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get(RESULT_PATH, 0).with(actor("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        long preview = insertPreview("QUEUED", null, null, "now() + interval '1 hour'");

        send(post(PATH, resourceId), body("", null), "VIEWER").andExpect(status().isForbidden());
        mvc.perform(get(RESULT_PATH, preview).with(actor("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(RESULT_PATH, preview)).andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH, resourceId)
                                .with(actor("ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("", null)))
                .andExpect(status().isForbidden());
    }

    /**
     * 391: 미리보기도 원천 데이터 정책을 따른다. 금지 표·컬럼은 매핑과 같은 이유로, 민감 컬럼은 관리자가 값을 볼 수 없어 미리보기에서 읽지 않는다. 원천을 읽기
     * 전에 막으므로 작업도 만들지 않는다.
     */
    @Test
    void rejectsColumnsTheSourcePolicyKeepsOut() throws Exception {
        long sourceId =
                jdbc.sql(
                                "insert into data_source (code, name, source_kind) values (:code, '정책', 'POSTGRES') returning id")
                        .param(
                                "code",
                                "POL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
        long employee = resource(sourceId, "erp.employee", true);
        column(employee, "id", 1);
        long empName = column(employee, "emp_name", 2);
        long maintenance = resource(sourceId, "mes.maintenance_order", true);
        column(maintenance, "id", 1);
        long technician = column(maintenance, "technician", 2);
        long attendance = resource(sourceId, "erp.attendance", true);
        column(attendance, "id", 1);

        send(post(PATH, employee), body(propertyJson(woNo, empName, "[]"), null), "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.error.details.field")
                                .value("mapping.propertyMappings[0].sourceColumnId"))
                .andExpect(jsonPath("$.error.details.forbiddenColumns[0]").value("emp_name"));
        send(
                        post(PATH, employee),
                        """
                        {"mapping": {"objectTypeId": %d, "keyColumns": ["emp_name"]}}
                        """
                                .formatted(typeId),
                        "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("mapping.keyColumns"));
        send(post(PATH, attendance), body("", null), "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.forbiddenColumns[0]").value("*"));
        send(post(PATH, maintenance), body(propertyJson(woNo, technician, "[]"), null), "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.error.details.field")
                                .value("mapping.propertyMappings[0].sourceColumnId"))
                .andExpect(jsonPath("$.error.details.restrictedColumns[0]").value("technician"));

        assertThat(
                        jdbc.sql(
                                        "select count(*) from source_preview where source_resource_id in (:ids)")
                                .param("ids", List.of(employee, maintenance, attendance))
                                .query(Long.class)
                                .single())
                .isZero();
    }

    /** 민감 속성은 일반 컬럼에 매핑해도 미리보기에서 읽지 않는다. 미리보기 결과는 가리지 않은 원천 값이다(391). */
    @Test
    void rejectsRestrictedProperty() throws Exception {
        jdbc.sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", woNo)
                .update();

        send(post(PATH, resourceId), body(propertyJson(woNo, woNoColumn, "[]"), null), "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.error.details.field")
                                .value("mapping.propertyMappings[0].propertyDefinitionId"))
                .andExpect(jsonPath("$.error.details.reason").value("RESTRICTED_PROPERTY"));
    }

    private void assertViolation(String body, String field) throws Exception {
        send(post(PATH, resourceId), body, "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value(field));
    }

    private ResultActions send(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body,
            String role)
            throws Exception {
        return mvc.perform(
                request.with(actor(role))
                        .with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String body(String propertyMappings, Integer rowLimit) {
        return """
                {"mapping": {"objectTypeId": %d, "keyColumns": ["id"], "propertyMappings": [%s]}%s}
                """
                .formatted(
                        typeId,
                        propertyMappings,
                        rowLimit == null ? "" : ", \"rowLimit\": " + rowLimit);
    }

    private static String propertyJson(long propertyId, long columnId, String steps) {
        return """
                {"propertyDefinitionId": %d, "sourceColumnId": %d, "transformSteps": %s}
                """
                .formatted(propertyId, columnId, steps);
    }

    private RequestPostProcessor actor(String role) {
        return authentication(
                new LoginAccount(accountId, "preview-tester", role, false).toAuthentication());
    }

    /** 화면처럼 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 같은 값으로 보낸다. */
    private static RequestPostProcessor csrfToken() {
        String token = UUID.randomUUID().toString();
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    private long insertPreview(String status, String result, String error, String expiresAt) {
        return jdbc.sql(
                        """
                        insert into source_preview (source_resource_id, preview_kind, request, row_limit, status,
                                                    requested_by, requested_at, started_at, finished_at, result,
                                                    error_message, expires_at)
                        values (:r, 'MAPPING_PREVIEW', '{"mapping": {}}', 20, :status, :account,
                                now() - interval '1 minute',
                                case when :status = 'QUEUED' then null else now() - interval '50 seconds' end,
                                case when :status in ('SUCCEEDED', 'FAILED') then now() end,
                                cast(:result as jsonb), :error, %s)
                        returning id
                        """
                                .formatted(expiresAt))
                .param("r", resourceId)
                .param("status", status)
                .param("account", accountId)
                .param("result", result)
                .param("error", error)
                .query(Long.class)
                .single();
    }

    private long resource(long sourceId, String key, boolean readable) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind, is_readable)
                        values (:s, :key, 'TABLE', :readable) returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .param("readable", readable)
                .query(Long.class)
                .single();
    }

    private long column(long resourceId, String name, int position) {
        return jdbc.sql(
                        """
                        insert into source_column (source_resource_id, column_name, source_data_type,
                                                   is_nullable, is_key_part, ordinal_position)
                        values (:r, :name, 'text', true, :key, :position) returning id
                        """)
                .param("r", resourceId)
                .param("name", name)
                .param("key", name.equals("id"))
                .param("position", position)
                .query(Long.class)
                .single();
    }

    private long type() {
        return jdbc.sql(
                        "insert into object_type (code, name) values ('PRV-' || gen_random_uuid(), '미리보기') returning id")
                .query(Long.class)
                .single();
    }

    private long property(long objectTypeId, String code, String dataType) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:t, :code, :code, :type) returning id
                        """)
                .param("t", objectTypeId)
                .param("code", code)
                .param("type", dataType)
                .query(Long.class)
                .single();
    }
}

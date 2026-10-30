package com.axcore.platform.ontology;

import static org.hamcrest.Matchers.contains;
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

/** 관계 유형 관리(API 030~033)를 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class LinkTypeApiTests {

    private static final String BASE_PATH = "/api/v1/ontology/link-types";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsAndSearchesLinkTypes() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long workOrder = insertObjectType();
        long equipment = insertObjectType();
        long employee = insertObjectType();
        String code = uniqueCode();

        send(admin, post(BASE_PATH), body(code, workOrder, equipment, "MANY_TO_ONE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.sourceTypeId").value(workOrder))
                .andExpect(jsonPath("$.data.targetTypeId").value(equipment))
                .andExpect(jsonPath("$.data.cardinality").value("MANY_TO_ONE"))
                .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                .andExpect(jsonPath("$.data.rowVersion").value(0));
        String otherCode = createLinkType(admin, workOrder, employee).code();

        mockMvc.perform(
                        get(BASE_PATH)
                                .param("sourceTypeId", String.valueOf(workOrder))
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code", contains(code, otherCode)))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
        mockMvc.perform(
                        get(BASE_PATH)
                                .param("sourceTypeId", String.valueOf(workOrder))
                                .param("targetTypeId", String.valueOf(equipment))
                                .cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].code", contains(code)));
        mockMvc.perform(get(BASE_PATH).param("q", code.toLowerCase()).cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].code", hasItem(code)));
    }

    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long typeId = insertObjectType();

        send(admin, post(BASE_PATH), body("uses_equipment", typeId, typeId, "ONE_TO_ONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("code"));
        send(admin, post(BASE_PATH), body(uniqueCode(), typeId, typeId, "ONE_TO_FEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("cardinality"));
        send(admin, post(BASE_PATH), body(uniqueCode(), Long.MAX_VALUE, typeId, "ONE_TO_ONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("sourceTypeId"));
        send(admin, post(BASE_PATH), body(uniqueCode(), typeId, Long.MAX_VALUE, "ONE_TO_ONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("targetTypeId"));
        send(
                        admin,
                        post(BASE_PATH),
                        """
                        {"code": "%s", "name": "관계", "sourceTypeId": %d, "targetTypeId": %d,
                         "cardinality": "ONE_TO_ONE"}
                        """
                                .formatted(uniqueCode(), typeId, typeId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("description"));
    }

    @Test
    void rejectsDuplicateCodeAndNonAdmin() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        LoginSession viewer = loginAs("VIEWER");
        long typeId = insertObjectType();
        String code = createLinkType(admin, typeId, typeId).code();

        send(admin, post(BASE_PATH), body(code, typeId, typeId, "ONE_TO_ONE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        mockMvc.perform(get(BASE_PATH).cookie(viewer.session())).andExpect(status().isForbidden());
        send(viewer, post(BASE_PATH), body(uniqueCode(), typeId, typeId, "ONE_TO_ONE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatesFreelyWhileUnused() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long source = insertObjectType();
        long target = insertObjectType();
        long otherTarget = insertObjectType();
        CreatedLinkType linkType = createLinkType(admin, source, target);
        String newCode = uniqueCode();

        send(
                        admin,
                        patch(BASE_PATH + "/" + linkType.id()),
                        updateBody(newCode, source, otherTarget, "ONE_TO_MANY", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(newCode))
                .andExpect(jsonPath("$.data.targetTypeId").value(otherTarget))
                .andExpect(jsonPath("$.data.cardinality").value("ONE_TO_MANY"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        send(
                        admin,
                        patch(BASE_PATH + "/" + linkType.id()),
                        updateBody(newCode, source, target, "ONE_TO_MANY", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        send(
                        admin,
                        patch(BASE_PATH + "/" + Long.MAX_VALUE),
                        updateBody(newCode, source, target, "ONE_TO_MANY", 0))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsKeyFieldsOnceRulesExist() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long source = insertObjectType();
        long target = insertObjectType();
        CreatedLinkType linkType = createLinkType(admin, source, target);
        long mappingId = insertRetiredRule(linkType.id());

        send(
                        admin,
                        patch(BASE_PATH + "/" + linkType.id()),
                        updateBody(linkType.code(), target, target, "ONE_TO_ONE", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IN_USE"))
                .andExpect(jsonPath("$.error.details.field").value("sourceTypeId"))
                .andExpect(
                        jsonPath("$.error.details.references[0].tableName").value("link_mapping"))
                .andExpect(jsonPath("$.error.details.references[0].ids[0]").value(mappingId));
        send(
                        admin,
                        patch(BASE_PATH + "/" + linkType.id()),
                        updateBody(uniqueCode(), source, target, "ONE_TO_ONE", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.field").value("code"));

        send(
                        admin,
                        patch(BASE_PATH + "/" + linkType.id()),
                        updateBody(linkType.code(), source, target, "ONE_TO_ONE", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardinality").value("ONE_TO_ONE"));
    }

    @Test
    void deletesOnlyUnusedLinkType() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long source = insertObjectType();
        long target = insertObjectType();
        CreatedLinkType used = createLinkType(admin, source, target);
        CreatedLinkType unused = createLinkType(admin, source, target);
        long mappingId = insertRetiredRule(used.id());

        send(admin, delete(BASE_PATH + "/" + used.id()), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IN_USE"))
                .andExpect(jsonPath("$.error.details.references[0].ids[0]").value(mappingId));
        send(admin, delete(BASE_PATH + "/" + unused.id()), null).andExpect(status().isNoContent());
        send(admin, delete(BASE_PATH + "/" + unused.id()), null).andExpect(status().isNotFound());

        // 관계 유형이 객체 유형을 쓰고 있으면 객체 유형도 지우지 않는다(API 025).
        send(admin, delete("/api/v1/ontology/object-types/" + source), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.references[0].tableName").value("link_type"));
    }

    private CreatedLinkType createLinkType(LoginSession admin, long source, long target)
            throws Exception {
        String code = uniqueCode();
        MvcResult result =
                send(admin, post(BASE_PATH), body(code, source, target, "MANY_TO_ONE"))
                        .andExpect(status().isCreated())
                        .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return new CreatedLinkType(id.longValue(), code);
    }

    /** 지난 개정본만 남은 규칙도 관계 유형을 막는다. */
    private long insertRetiredRule(long linkTypeId) {
        return jdbcClient
                .sql(
                        """
                        insert into link_mapping (link_type_id, code, revision, status, retired_at)
                        values (:linkTypeId, :code, 1, 'RETIRED', now())
                        returning id
                        """)
                .param("linkTypeId", linkTypeId)
                .param("code", "RULE_" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private long insertObjectType() {
        return jdbcClient
                .sql("insert into object_type (code, name) values (:code, '유형') returning id")
                .param("code", uniqueCode())
                .query(Long.class)
                .single();
    }

    private static String body(String code, long source, long target, String cardinality) {
        return """
                {"code": "%s", "name": "관계", "sourceTypeId": %d, "targetTypeId": %d,
                 "cardinality": "%s", "description": "작업지시가 쓰는 설비"}
                """
                .formatted(code, source, target, cardinality);
    }

    private static String updateBody(
            String code, long source, long target, String cardinality, long rowVersion) {
        return """
                {"code": "%s", "name": "관계", "sourceTypeId": %d, "targetTypeId": %d,
                 "cardinality": "%s", "description": "작업지시가 쓰는 설비", "rowVersion": %d}
                """
                .formatted(code, source, target, cardinality, rowVersion);
    }

    private static String uniqueCode() {
        return "C_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
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

    private record CreatedLinkType(long id, String code) {}

    private record LoginSession(Cookie session, Cookie csrf) {}
}

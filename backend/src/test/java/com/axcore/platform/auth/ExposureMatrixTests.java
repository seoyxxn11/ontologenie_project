package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.service.AgentObjectDetailTools;
import com.axcore.platform.agent.service.AgentObjectListTools;
import com.axcore.platform.agent.service.AgentQueryRecorder;
import com.axcore.platform.agent.service.AgentQuerySession;
import com.axcore.platform.agent.service.AgentQueryTools;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경로 × 역할 × 등급 교차 확인(S15P31C106-396). 역할 범위는 시험 행을 더하지 않고 V21 기본값을 그대로 쓴다: PRODUCTION 그룹을 세 역할 모두
 * 보고, 민감 값은 PLANNER만 본다. 같은 시험 값이 허용된 곳에만 나오는지 화면 API와 Agent 도구(외부 모델에 넘기는 결과)에서 함께 본다.
 *
 * <ul>
 *   <li>일반 값({@value #NORMAL}): 세 역할의 화면과 모델 입력에 모두 나온다.
 *   <li>민감 값({@value #RESTRICTED}): PLANNER 화면에만 나온다. 모델 입력에는 PLANNER가 물어도 없다.
 *   <li>그룹 없는(미분류) 유형의 값({@value #UNGROUPED}): 관리자 화면에만 있다. 다른 역할과 모델 입력에는 없다(384 보완).
 * </ul>
 *
 * 금지 값은 원천 계정·publication(infra/tests/source_exposure_check.sh), 적재 계획(FullLoadPlannerPolicyTests),
 * 매핑·분석 접수 거절에서 플랫폼에 들어오기 전에 막으므로 여기서는 다루지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ExposureMatrixTests {

    private static final String PASSWORD = "Passw0rd!";
    private static final String NORMAL = "N396-REF";
    private static final String RESTRICTED = "R396-OWNER";
    private static final String UNGROUPED = "U396-HIDDEN";
    private static final String PARTNER_NORMAL = "N434-PARTNER";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JsonMapper json;
    @Autowired ObjectSearchQuery objectSearch;
    @Autowired ObjectDetailQuery objectDetail;
    @Autowired ModelSummaryService models;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired PropertyValueSampleService values;
    @Autowired AuditExecutionQueryService executions;

    private long typeId;
    private long objectId;
    private long hiddenId;
    private long plannerId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        typeId = type("EXP_" + suffix, "PRODUCTION");
        property(typeId, "ref", "NORMAL");
        property(typeId, "owner", "RESTRICTED");
        objectId =
                object(
                        typeId,
                        NORMAL,
                        "{\"ref\": \"%s\", \"owner\": \"%s\"}".formatted(NORMAL, RESTRICTED));
        // 관계 조회는 상대 객체의 속성도 함께 준다(434). 상대의 민감 값도 모델 입력에 나가지 않아야 한다.
        long partner =
                object(
                        typeId,
                        PARTNER_NORMAL,
                        "{\"ref\": \"%s\", \"owner\": \"%s\"}"
                                .formatted(PARTNER_NORMAL, RESTRICTED));
        link(linkType("EXPL_" + suffix, typeId), objectId, partner);
        long hiddenType = type("EXPH_" + suffix, null);
        property(hiddenType, "ref", "NORMAL");
        hiddenId = object(hiddenType, UNGROUPED, "{\"ref\": \"%s\"}".formatted(UNGROUPED));
        plannerId = account("PLANNER");
    }

    @Test
    void screenShowsRestrictedValueOnlyToPlannerAndNormalValueToEveryone() throws Exception {
        for (String role : new String[] {"ADMIN", "PLANNER", "VIEWER"}) {
            Cookie session = login(account(role));
            String list = body(get("/api/v1/objects").param("objectTypeId", "" + typeId), session);
            String detail = body(get("/api/v1/objects/" + objectId), session);

            assertThat(list).as(role + " 076").contains(NORMAL);
            assertThat(detail).as(role + " 077").contains(NORMAL);
            if (role.equals("PLANNER")) {
                assertThat(list).as(role + " 076 민감").contains(RESTRICTED);
                assertThat(detail).as(role + " 077 민감").contains(RESTRICTED);
            } else {
                assertThat(list).as(role + " 076 민감").doesNotContain(RESTRICTED);
                assertThat(detail).as(role + " 077 민감").doesNotContain(RESTRICTED);
            }
        }
    }

    @Test
    void ungroupedTypeIsVisibleOnlyToAdmin() throws Exception {
        for (String role : new String[] {"PLANNER", "VIEWER"}) {
            Cookie session = login(account(role));
            mockMvc.perform(get("/api/v1/objects/" + hiddenId).cookie(session))
                    .andExpect(status().isNotFound());
            assertThat(body(get("/api/v1/objects").param("q", UNGROUPED), session))
                    .as(role + " 076 검색")
                    .doesNotContain(UNGROUPED);
        }
        // 미분류 유형은 관리자에게만 보인다. 그룹을 정하는 사람이 값을 보고 검토한 뒤 공개한다(384 보완, V29).
        Cookie admin = login(account("ADMIN"));
        mockMvc.perform(get("/api/v1/objects/" + hiddenId).cookie(admin))
                .andExpect(status().isOk());
        assertThat(body(get("/api/v1/objects").param("q", UNGROUPED), admin))
                .as("ADMIN 076 검색")
                .contains(UNGROUPED);
    }

    /**
     * Agent 도구가 돌려주는 결과가 그대로 외부 모델 입력이 된다. 민감 값을 볼 수 있는 PLANNER가 물어도 결과(JSON)에 민감 값이 없고, 민감 속성으로
     * 거르는 것도 거절해 값을 떠볼 수 없다.
     */
    @Test
    void modelInputHasNoRestrictedValueEvenWhenPlannerAsks() {
        LoginAccount planner = new LoginAccount(plannerId, "planner", "PLANNER", false);
        AgentQuerySession session =
                new AgentQuerySession(
                        planner, 396L, mock(AgentQueryRecorder.class), Clock.systemUTC());
        AgentObjectListTools list =
                new AgentObjectListTools(session, objectSearch, models, dataAccess, values);
        AgentObjectDetailTools detail =
                new AgentObjectDetailTools(session, objectDetail, objectSearch);
        AgentQueryTools search = new AgentQueryTools(session, executions, objectSearch);

        String listed =
                json.writeValueAsString(list.objectList(typeId, null, null, null, null, null));
        String detailed = json.writeValueAsString(detail.objectDetail(objectId));
        String linked = json.writeValueAsString(detail.objectLinks(objectId, null, null, null));
        String found = json.writeValueAsString(search.objectSearch(NORMAL, typeId, null));
        String filtered =
                json.writeValueAsString(
                        list.objectList(
                                typeId, Map.of("owner", RESTRICTED), null, null, null, null));
        String hidden = json.writeValueAsString(detail.objectDetail(hiddenId));

        assertThat(listed).contains(NORMAL).doesNotContain(RESTRICTED);
        assertThat(detailed).contains(NORMAL).doesNotContain(RESTRICTED);
        assertThat(linked).contains(PARTNER_NORMAL, "relatedObjects").doesNotContain(RESTRICTED);
        assertThat(found).contains(NORMAL).doesNotContain(RESTRICTED);
        assertThat(filtered).contains("DENIED").doesNotContain("\"total\":1");
        assertThat(hidden).doesNotContain(UNGROUPED);
    }

    private String body(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Cookie session)
            throws Exception {
        return mockMvc.perform(request.cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long type(String code, String group) {
        return jdbc.sql(
                        "insert into object_type (code, name, access_group) values (:code, '노출 확인', :group) returning id")
                .param("code", code)
                .param("group", group)
                .query(Long.class)
                .single();
    }

    private void property(long type, String code, String sensitivity) {
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity)
                        """)
                .param("type", type)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .update();
    }

    private long object(long type, String name, String properties) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }

    private long linkType(String code, long type) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, :code, :type, :type, 'MANY_TO_MANY', 'Test') returning id
                        """)
                .param("code", code)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private void link(long linkType, long source, long target) {
        jdbc.sql(
                        """
                        with rule as (
                            insert into link_mapping (link_type_id, code, revision)
                            values (:type, :code, 1) returning id
                        )
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, link_mapping_id, status)
                        select :type, :source, :target, 'RULE', id, 'ACTIVE' from rule
                        """)
                .param("type", linkType)
                .param("code", "EXPM-" + linkType)
                .param("source", source)
                .param("target", target)
                .update();
    }

    private long account(String role) {
        return jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                        values (:loginId, '노출 확인', :hash, (select id from role where code = :role), false)
                        returning id
                        """)
                .param(
                        "loginId",
                        role.toLowerCase() + "-396-" + UUID.randomUUID().toString().substring(0, 8))
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private Cookie login(long accountId) throws Exception {
        String loginId =
                jdbc.sql("select login_id from account where id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .single();
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

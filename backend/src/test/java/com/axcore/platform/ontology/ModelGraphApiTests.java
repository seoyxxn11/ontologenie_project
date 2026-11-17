package com.axcore.platform.ontology;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "test.context=model-graph-api")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ModelGraphApiTests {

    private static final String PATH = "/api/v1/ontology/model-graph";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;

    @Test
    void returnsAnEmptyGraphThenCountsStoredLinksByRelationshipType() throws Exception {
        mockMvc.perform(get(PATH).with(account("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));

        int workOrder = insertType("WORK_ORDER", "작업지시");
        int equipment = insertType("EQUIPMENT", "설비");
        int isolated = insertType("DOCUMENT", "문서");
        // 관리자도 자기 데이터 범위(PRODUCTION·SALES_SUPPLY)의 유형만 받는다(427)
        jdbc.sql("update object_type set access_group = 'PRODUCTION' where id in (:ids)")
                .param("ids", java.util.List.of(workOrder, equipment, isolated))
                .update();
        int uses = insertLinkType("USES_EQUIPMENT", workOrder, equipment, "MANY_TO_ONE");
        int returnLink = insertLinkType("EQUIPMENT_FOR", equipment, workOrder, "ONE_TO_MANY");
        int empty = insertLinkType("RELATED_ORDER", workOrder, workOrder, "MANY_TO_MANY");
        int assigned = insertLinkType("ASSIGNED_EQUIPMENT", workOrder, equipment, "ONE_TO_ONE");
        int firstOrder = insertObject(workOrder);
        int deletedOrder = insertDeletedSourceObject(workOrder);
        int thirdOrder = insertObject(workOrder);
        int machine = insertObject(equipment);
        long confirmer =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id)
                                select 'model-graph-confirmer', '테스트 사용자', 'unused', id
                                from role where code = 'ADMIN'
                                returning id
                                """)
                        .query(Long.class)
                        .single();
        long rule =
                jdbc.sql(
                                """
                                insert into link_mapping (link_type_id, code, revision)
                                values (:type, 'EQUIPMENT_RULE', 1) returning id
                                """)
                        .param("type", uses)
                        .query(Long.class)
                        .single();
        insertLink(uses, firstOrder, machine, null, "RULE", rule, true);
        insertLink(uses, deletedOrder, machine, confirmer, "DOCUMENT", null, true);
        insertLink(uses, thirdOrder, machine, confirmer, "MANUAL", null, false);
        insertLink(returnLink, machine, firstOrder, confirmer, "MANUAL", null, false);
        insertLink(assigned, firstOrder, machine, confirmer, "MANUAL", null, true);

        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get(PATH).with(account("ADMIN", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta").doesNotExist())
                    .andExpect(jsonPath("$.data.nodes", hasSize(3)))
                    .andExpect(jsonPath("$.data.nodes[0].id").value(workOrder))
                    .andExpect(jsonPath("$.data.nodes[0].code").value("WORK_ORDER"))
                    .andExpect(jsonPath("$.data.nodes[0].name").value("작업지시"))
                    .andExpect(jsonPath("$.data.nodes[1].id").value(equipment))
                    .andExpect(jsonPath("$.data.nodes[2].id").value(isolated))
                    .andExpect(jsonPath("$.data.edges", hasSize(4)))
                    .andExpect(jsonPath("$.data.edges[0].id").value(uses))
                    .andExpect(jsonPath("$.data.edges[0].code").value("USES_EQUIPMENT"))
                    .andExpect(jsonPath("$.data.edges[0].name").value("USES_EQUIPMENT"))
                    .andExpect(jsonPath("$.data.edges[0].sourceTypeId").value(workOrder))
                    .andExpect(jsonPath("$.data.edges[0].targetTypeId").value(equipment))
                    .andExpect(jsonPath("$.data.edges[0].cardinality").value("MANY_TO_ONE"))
                    .andExpect(jsonPath("$.data.edges[0].relationCount").value(3))
                    .andExpect(jsonPath("$.data.edges[0].activeLinkCount").value(2))
                    .andExpect(jsonPath("$.data.edges[1].id").value(returnLink))
                    .andExpect(jsonPath("$.data.edges[1].code").value("EQUIPMENT_FOR"))
                    .andExpect(jsonPath("$.data.edges[1].sourceTypeId").value(equipment))
                    .andExpect(jsonPath("$.data.edges[1].targetTypeId").value(workOrder))
                    .andExpect(jsonPath("$.data.edges[1].cardinality").value("ONE_TO_MANY"))
                    .andExpect(jsonPath("$.data.edges[1].relationCount").value(1))
                    .andExpect(jsonPath("$.data.edges[1].activeLinkCount").value(0))
                    .andExpect(jsonPath("$.data.edges[2].id").value(empty))
                    .andExpect(jsonPath("$.data.edges[2].code").value("RELATED_ORDER"))
                    .andExpect(jsonPath("$.data.edges[2].sourceTypeId").value(workOrder))
                    .andExpect(jsonPath("$.data.edges[2].targetTypeId").value(workOrder))
                    .andExpect(jsonPath("$.data.edges[2].cardinality").value("MANY_TO_MANY"))
                    .andExpect(jsonPath("$.data.edges[2].relationCount").value(0))
                    .andExpect(jsonPath("$.data.edges[2].activeLinkCount").value(0))
                    .andExpect(jsonPath("$.data.edges[3].id").value(assigned))
                    .andExpect(jsonPath("$.data.edges[3].code").value("ASSIGNED_EQUIPMENT"))
                    .andExpect(jsonPath("$.data.edges[3].sourceTypeId").value(workOrder))
                    .andExpect(jsonPath("$.data.edges[3].targetTypeId").value(equipment))
                    .andExpect(jsonPath("$.data.edges[3].cardinality").value("ONE_TO_ONE"))
                    .andExpect(jsonPath("$.data.edges[3].relationCount").value(1))
                    .andExpect(jsonPath("$.data.edges[3].activeLinkCount").value(1));
        }
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void everyRoleSeesOnlyVisibleTypesAndTheirRelations() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        int shown = insertType("SHOWN_" + suffix, "보이는 유형");
        int hidden = insertType("HIDDEN_" + suffix, "숨은 유형");
        int unclassified = insertType("UNCLASSIFIED_T_" + suffix, "미분류 유형");
        jdbc.sql("update object_type set access_group = 'PRODUCTION' where id = :id")
                .param("id", shown)
                .update();
        // 어느 역할에도 허용 행이 없는 그룹
        jdbc.sql("update object_type set access_group = :group where id = :id")
                .param("group", "UNLISTED_" + suffix)
                .param("id", hidden)
                .update();
        int self = insertLinkType("SELF_" + suffix, shown, shown, "MANY_TO_MANY");
        int toHidden = insertLinkType("TO_HIDDEN_" + suffix, shown, hidden, "MANY_TO_ONE");
        int toUnclassified =
                insertLinkType("TO_UNCLASSIFIED_" + suffix, shown, unclassified, "MANY_TO_ONE");
        try {
            // 조회 사용자: 볼 수 있는 유형과 그 유형끼리의 관계만(S15P31C106-420)
            mockMvc.perform(get(PATH).with(account("VIEWER", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nodes[?(@.id == " + shown + ")]", hasSize(1)))
                    .andExpect(jsonPath("$.data.nodes[?(@.id == " + hidden + ")]", hasSize(0)))
                    .andExpect(jsonPath("$.data.edges[?(@.id == " + self + ")]", hasSize(1)))
                    .andExpect(jsonPath("$.data.edges[?(@.id == " + toHidden + ")]", hasSize(0)))
                    .andExpect(
                            jsonPath("$.data.nodes[?(@.id == " + unclassified + ")]", hasSize(0)))
                    .andExpect(
                            jsonPath(
                                    "$.data.edges[?(@.id == " + toUnclassified + ")]", hasSize(0)));
            // 관리자도 범위 밖(허용 행이 없는 그룹) 유형과 그 관계는 받지 않는다(427).
            // 미분류(그룹 없음) 유형은 관리자에게만 보인다(384 보완, V29).
            mockMvc.perform(get(PATH).with(account("ADMIN", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nodes[?(@.id == " + shown + ")]", hasSize(1)))
                    .andExpect(jsonPath("$.data.nodes[?(@.id == " + hidden + ")]", hasSize(0)))
                    .andExpect(jsonPath("$.data.edges[?(@.id == " + toHidden + ")]", hasSize(0)))
                    .andExpect(
                            jsonPath("$.data.nodes[?(@.id == " + unclassified + ")]", hasSize(1)))
                    .andExpect(
                            jsonPath(
                                    "$.data.edges[?(@.id == " + toUnclassified + ")]", hasSize(1)));
            // 범위가 없는 역할은 빈 관계도
            mockMvc.perform(get(PATH).with(account("MAINTENANCE", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nodes[?(@.id == " + shown + ")]", hasSize(0)))
                    .andExpect(jsonPath("$.data.edges[?(@.id == " + self + ")]", hasSize(0)));
        } finally {
            jdbc.sql("delete from link_type where id in (:ids)")
                    .param("ids", java.util.List.of(self, toHidden, toUnclassified))
                    .update();
            jdbc.sql("delete from object_type where id in (:ids)")
                    .param("ids", java.util.List.of(shown, hidden, unclassified))
                    .update();
        }
    }

    @Test
    void rejectsAdminRequiredToChangePassword() throws Exception {
        mockMvc.perform(get(PATH).with(account("ADMIN", true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static RequestPostProcessor account(String role, boolean mustChangePassword) {
        return authentication(
                new LoginAccount(1L, "tester", role, mustChangePassword).toAuthentication());
    }

    private int insertType(String code, String name) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :name) returning id")
                .param("code", code)
                .param("name", name)
                .query(Integer.class)
                .single();
    }

    private int insertLinkType(String code, int source, int target, String cardinality) {
        return jdbc.sql(
                        """
                        insert into link_type
                            (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, :code, :source, :target, :cardinality, '')
                        returning id
                        """)
                .param("code", code)
                .param("source", source)
                .param("target", target)
                .param("cardinality", cardinality)
                .query(Integer.class)
                .single();
    }

    private int insertObject(int type) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name)
                        values (:type, '테스트 객체') returning id
                        """)
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private int insertDeletedSourceObject(int type) {
        return jdbc.sql(
                        """
                        with source as (
                            insert into data_source (code, name, source_kind)
                            values ('GRAPH_TEST_SOURCE', '테스트 원천', 'POSTGRES') returning id
                        ), resource as (
                            insert into source_resource (data_source_id, resource_key, resource_kind)
                            select id, 'public.work_orders', 'TABLE' from source returning id
                        ), mapping as (
                            insert into object_source_mapping
                                (source_resource_id, object_type_id, key_columns)
                            select id, :type, '["id"]'::jsonb from resource returning id
                        )
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, status)
                        select :type, id, '{"id":1}'::jsonb, '원천 삭제 객체', 'DELETED_AT_SOURCE'
                        from mapping returning id
                        """)
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private void insertLink(
            int type,
            int source,
            int target,
            Long confirmer,
            String origin,
            Long mapping,
            boolean active) {
        jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, confirmed_by,
                             origin, link_mapping_id, evidence, status, inactivated_at)
                        values (:type, :source, :target, :confirmer, :origin, :mapping,
                                case when :origin = 'DOCUMENT' then '{"expression_kind":"OBSERVED"}'::jsonb
                                     else '{}'::jsonb end,
                                :status, case when :active then null else now() end)
                        """)
                .param("type", type)
                .param("source", source)
                .param("target", target)
                .param("confirmer", confirmer)
                .param("origin", origin)
                .param("mapping", mapping)
                .param("status", active ? "ACTIVE" : "INACTIVE")
                .param("active", active)
                .update();
    }
}

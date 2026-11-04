package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class HeldItemApiIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    long accountId;
    long objectTypeId;
    long sourceObjectId;
    long linkTypeId;
    long mappingId;
    long proposalId;

    @BeforeEach
    void prepareReferences() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '보류 조회 관리자', 'test-hash', (select id from role where code='ADMIN'), false)
                returning id
                """)
                        .param("login", "held-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        objectTypeId =
                jdbc.sql(
                                "insert into object_type(code, name) values (:code, '보류 테스트 유형') returning id")
                        .param("code", UUID.randomUUID().toString())
                        .query(Long.class)
                        .single();
        sourceObjectId = object();
        linkTypeId = linkType();
        mappingId =
                jdbc.sql(
                                """
                insert into link_mapping(link_type_id, code, revision)
                values (:type, :code, 1) returning id
                """)
                        .param("type", linkTypeId)
                        .param("code", UUID.randomUUID().toString())
                        .query(Long.class)
                        .single();
        proposalId =
                jdbc.sql(
                                """
                insert into proposal_item(origin, item_kind, payload, review_status, created_by)
                values ('MANUAL', 'LINK_RULE', '{}', 'APPROVED', :account) returning id
                """)
                        .param("account", accountId)
                        .query(Long.class)
                        .single();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "APPROVED", "AUTO_RESOLVED", "DISMISSED"})
    void returnsSameCompleteItemInListAndDetail(String itemStatus) throws Exception {
        held("HELD", linkType(), true);
        long id = held(itemStatus, linkTypeId, false);
        JsonNode detail =
                data(
                        read("/api/v1/held-items/" + id)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.meta").doesNotExist()));
        JsonNode list =
                data(
                        read("/api/v1/held-items?status="
                                        + itemStatus
                                        + "&linkTypeId="
                                        + linkTypeId)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.meta.totalElements").value(1)));
        assertThat(list.get(0)).isEqualTo(detail);
        assertThat(detail.size()).isEqualTo(15);
        assertThat(detail.path("id").asLong()).isEqualTo(id);
        assertThat(detail.path("proposalItemId").isNull()).isTrue();
        assertThat(detail.path("linkMappingId").asLong()).isEqualTo(mappingId);
        assertThat(detail.path("sourceObjectId").asLong()).isEqualTo(sourceObjectId);
        assertThat(detail.path("linkTypeId").asLong()).isEqualTo(linkTypeId);
        assertThat(detail.path("status").asText()).isEqualTo(itemStatus);
        assertThat(detail.path("rawValue").asText()).isEqualTo("W23");
        assertThat(detail.path("transformedValue").asText()).isEqualTo("E023");
        assertThat(detail.path("holdReason").asText()).isEqualTo("직원 E023이 없습니다.");
        assertThat(detail.path("createdAt").asText()).isEqualTo("2026-11-02T01:00:00Z");
        boolean closed = !"HELD".equals(itemStatus);
        boolean manual = List.of("APPROVED", "DISMISSED").contains(itemStatus);
        boolean linked = List.of("APPROVED", "AUTO_RESOLVED").contains(itemStatus);
        assertThat(detail.path("resolvedLinkId").isNumber()).isEqualTo(linked);
        assertThat(detail.path("resolvedSyncRunId").isNumber())
                .isEqualTo("AUTO_RESOLVED".equals(itemStatus));
        assertThat(detail.path("resolvedBy").isNumber()).isEqualTo(manual);
        if (manual) assertThat(detail.path("resolvedBy").asLong()).isEqualTo(accountId);
        assertThat(detail.path("resolvedAt").isNull()).isEqualTo(!closed);
        assertThat(detail.path("resolutionNote").isNull()).isEqualTo(!closed);
        if (closed) {
            assertThat(detail.path("resolvedAt").asText()).isEqualTo("2026-11-03T02:00:00Z");
            assertThat(detail.path("resolutionNote").asText()).isEqualTo("처리 근거");
        }
    }

    @Test
    void combinesFiltersAndCountsOnlyMatchingItemsBeforePaging() throws Exception {
        long first = held("HELD", linkTypeId, false);
        held("DISMISSED", linkTypeId, false);
        held("HELD", linkType(), true);
        long second = held("HELD", linkTypeId, false);
        String path = "/api/v1/held-items?status=HELD&linkTypeId=" + linkTypeId + "&size=1";
        read(path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        read(path + "&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        read(path + "&page=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(2));
        read("/api/v1/held-items?linkTypeId=" + linkTypeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(3));
    }

    @Test
    void includesEveryStatusWhenStatusIsOmitted() throws Exception {
        for (String itemStatus : List.of("HELD", "APPROVED", "AUTO_RESOLVED", "DISMISSED")) {
            held(itemStatus, linkTypeId, false);
        }
        held("HELD", linkType(), true);
        read("/api/v1/held-items?linkTypeId=" + linkTypeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(4));
    }

    @Test
    void readsCandidateOnlyItemAndKeepsNullableSourceValues() throws Exception {
        long id = held("HELD", linkTypeId, true);
        jdbc.sql("update held_item set raw_value=null, transformed_value=null where id=:id")
                .param("id", id)
                .update();
        read("/api/v1/held-items/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalItemId").value(proposalId))
                .andExpect(jsonPath("$.data.linkMappingId").value(nullValue()))
                .andExpect(jsonPath("$.data.rawValue").value(nullValue()))
                .andExpect(jsonPath("$.data.transformedValue").value(nullValue()));
    }

    @Test
    void returnsBothCandidateAndRuleReferencesWhenPresent() throws Exception {
        long id = held("HELD", linkTypeId, false);
        jdbc.sql("update held_item set proposal_item_id=:proposal where id=:id")
                .param("proposal", proposalId)
                .param("id", id)
                .update();
        read("/api/v1/held-items/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalItemId").value(proposalId))
                .andExpect(jsonPath("$.data.linkMappingId").value(mappingId));
    }

    @Test
    void returnsEmptyPageWhenNoItemsMatch() throws Exception {
        long queriedLinkType = linkType();
        held("HELD", queriedLinkType, true);
        held("APPROVED", linkTypeId, false);
        read("/api/v1/held-items?status=APPROVED&linkTypeId=" + queriedLinkType)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        read("/api/v1/held-items?linkTypeId=9223372036854775807")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void returnsNotFoundForMissingItem() throws Exception {
        read("/api/v1/held-items/9223372036854775807")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("held_item"));
    }

    @Test
    void repeatedReadsDoNotResolveHeldItemsOrRelatedIssues() throws Exception {
        long id = held("AUTO_RESOLVED", linkTypeId, false);
        long check =
                jdbc.sql(
                                """
                insert into check_run(trigger_kind, work_key) values ('CANDIDATE', :key) returning id
                """)
                        .param("key", UUID.randomUUID().toString())
                        .query(Long.class)
                        .single();
        long issue =
                jdbc.sql(
                                """
                insert into issue(issue_kind, dedupe_key, held_item_id, detected_check_run_id, failure_reason)
                values ('UNMATCHED', :key, :held, :check, '직원 없음') returning id
                """)
                        .param("key", UUID.randomUUID().toString())
                        .param("held", id)
                        .param("check", check)
                        .query(Long.class)
                        .single();
        String before =
                jdbc.sql("select to_jsonb(h)::text from held_item h where id=:id")
                        .param("id", id)
                        .query(String.class)
                        .single();
        for (int i = 0; i < 2; i++) {
            read("/api/v1/held-items").andExpect(status().isOk());
            read("/api/v1/held-items/" + id).andExpect(status().isOk());
        }
        assertThat(
                        jdbc.sql("select to_jsonb(h)::text from held_item h where id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(before);
        assertThat(
                        jdbc.sql("select status from issue where id=:id")
                                .param("id", issue)
                                .query(String.class)
                                .single())
                .isEqualTo("NEW");
        assertThat(
                        jdbc.sql("select review_status from proposal_item where id=:id")
                                .param("id", proposalId)
                                .query(String.class)
                                .single())
                .isEqualTo("APPROVED");
    }

    private long held(String itemStatus, long relationType, boolean candidateOnly) {
        boolean linked = List.of("APPROVED", "AUTO_RESOLVED").contains(itemStatus);
        Long link = linked ? resolvedLink("AUTO_RESOLVED".equals(itemStatus)) : null;
        Long sync = "AUTO_RESOLVED".equals(itemStatus) ? syncRun() : null;
        return jdbc.sql(
                        """
                insert into held_item(proposal_item_id, link_mapping_id, link_type_id, source_object_id,
                    raw_value, transformed_value, hold_reason, status, resolved_link_id, resolved_sync_run_id,
                    resolved_by, resolved_at, resolution_note, created_at)
                values (:proposal, :mapping, :type, :source, 'W23', 'E023', '직원 E023이 없습니다.', :status,
                    :link, :sync, :resolver,
                    case when :status='HELD' then null else timestamptz '2026-11-03 02:00:00Z' end,
                    case when :status='HELD' then null else '처리 근거' end, timestamptz '2026-11-02 01:00:00Z')
                returning id
                """)
                .param("proposal", candidateOnly ? proposalId : null, Types.BIGINT)
                .param("mapping", candidateOnly ? null : mappingId, Types.BIGINT)
                .param("type", relationType)
                .param("source", sourceObjectId)
                .param("status", itemStatus)
                .param("link", link, Types.BIGINT)
                .param("sync", sync, Types.BIGINT)
                .param(
                        "resolver",
                        List.of("APPROVED", "DISMISSED").contains(itemStatus) ? accountId : null,
                        Types.BIGINT)
                .query(Long.class)
                .single();
    }

    private long object() {
        return jdbc.sql(
                        "insert into object_instance(object_type_id, display_name) values (:type, '테스트 객체') returning id")
                .param("type", objectTypeId)
                .query(Long.class)
                .single();
    }

    private long linkType() {
        return jdbc.sql(
                        """
                insert into link_type(code, name, source_type_id, target_type_id, cardinality, description)
                values (:code, '담당 직원', :type, :type, 'MANY_TO_ONE', '보류 테스트') returning id
                """)
                .param("code", UUID.randomUUID().toString())
                .param("type", objectTypeId)
                .query(Long.class)
                .single();
    }

    private long resolvedLink(boolean automatic) {
        return jdbc.sql(
                        """
                insert into object_link(link_type_id, source_object_id, target_object_id, origin, link_mapping_id, confirmed_by)
                values (:type, :source, :target, :origin, :mapping, :account) returning id
                """)
                .param("type", linkTypeId)
                .param("source", sourceObjectId)
                .param("target", object())
                .param("origin", automatic ? "RULE" : "MANUAL")
                .param("mapping", automatic ? mappingId : null, Types.BIGINT)
                .param("account", automatic ? null : accountId, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    private long syncRun() {
        long source =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '수집 원천', 'POSTGRES') returning id")
                        .param("code", UUID.randomUUID().toString())
                        .query(Long.class)
                        .single();
        return jdbc.sql(
                        """
                insert into sync_run(data_source_id, trigger_kind, status, started_at, finished_at)
                values (:source, 'SCHEDULED', 'SUCCEEDED', now(), now()) returning id
                """)
                .param("source", source)
                .query(Long.class)
                .single();
    }

    private ResultActions read(String path) throws Exception {
        return mvc.perform(
                get(path)
                        .with(
                                authentication(
                                        new LoginAccount(accountId, "admin", "ADMIN", false)
                                                .toAuthentication())));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}

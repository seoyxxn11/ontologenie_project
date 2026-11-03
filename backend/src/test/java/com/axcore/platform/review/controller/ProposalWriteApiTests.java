package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "test.context=proposal-write")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ProposalWriteApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean AuditRecorder auditRecorder;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.axcore.platform.check.service.CandidateCheckService candidateChecks;
    long accountId;
    long linkTypeId;

    @BeforeEach
    void fixtures() {
        accountId =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '관리자', 'test-hash', (select id from role where code='ADMIN'), false)
                returning id
                """)
                        .param("login", "write-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long source = type("SOURCE_" + UUID.randomUUID().toString().replace("-", ""));
        long target = type("TARGET_" + UUID.randomUUID().toString().replace("-", ""));
        for (long id : List.of(source, target)) {
            jdbc.sql(
                            "insert into property_definition(object_type_id, code, name, data_type) values (:id,'code','번호','STRING')")
                    .param("id", id)
                    .update();
        }
        linkTypeId =
                jdbc.sql(
                                """
                insert into link_type(code,name,source_type_id,target_type_id,cardinality,description)
                values (:code,'연결',:source,:target,'MANY_TO_ONE','테스트 연결') returning id
                """)
                        .param("code", "RULE_" + UUID.randomUUID().toString().replace("-", ""))
                        .param("source", source)
                        .param("target", target)
                        .query(Long.class)
                        .single();
    }

    @Test
    void createsManualRevisionAndQueuedCheckInOneRequest() throws Exception {
        MvcResult result =
                create().andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                        .andExpect(jsonPath("$.data.revision").value(1))
                        .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                        .andExpect(jsonPath("$.data.applyStatus").value("NOT_APPLIED"))
                        .andExpect(jsonPath("$.data.aiConfidence").value(nullValue()))
                        .andExpect(jsonPath("$.data.evidence").isEmpty())
                        .andExpect(jsonPath("$.data.createdBy").value(accountId))
                        .andReturn();
        long id = id(result);
        long check =
                json.readTree(result.getResponse().getContentAsString())
                        .at("/data/checkRunId")
                        .asLong();
        assertThat(count("proposal_revision", id)).isEqualTo(1);
        assertThat(
                        jdbc.sql("select status from check_run where id=:id")
                                .param("id", check)
                                .query(String.class)
                                .single())
                .isEqualTo("QUEUED");
        assertThat(
                        jdbc.sql(
                                        "select scope->'proposalItems'->0->>'revision' from check_run where id=:id")
                                .param("id", check)
                                .query(String.class)
                                .single())
                .isEqualTo("1");
    }

    @Test
    void revisesHeldCandidatePreservingOldOptionsAndClearingNewOptions() throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        jdbc.sql("update proposal_item set review_status='HELD' where id=:id")
                .param("id", id)
                .update();
        jdbc.sql(
                        "insert into proposal_option(proposal_item_id,revision,option_no,payload) values (:id,1,1,'{}')")
                .param("id", id)
                .update();
        revise(id, 1, Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));
        assertThat(count("proposal_revision", id)).isEqualTo(2);
        assertThat(
                        jdbc.sql("select revision from proposal_option where proposal_item_id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .list())
                .containsExactly(1);
        revise(id, 1, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVISION_CONFLICT"));
        assertThat(count("proposal_revision", id)).isEqualTo(2);
    }

    @Test
    void savesExplicitOptionsOnlyInNewRevisionWithNullConfidence() throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        revise(id, 1, Map.of("options", List.of(Map.of("payload", payload()))))
                .andExpect(status().isCreated());
        assertThat(
                        jdbc.sql("select revision from proposal_option where proposal_item_id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .list())
                .containsExactly(2);
        assertThat(
                        jdbc.sql(
                                        "select count(*) from proposal_option where proposal_item_id=:id and ai_confidence is null and option_no=1")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void refusesDecidedCandidates(String state) throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        jdbc.sql("update proposal_item set review_status=:state where id=:id")
                .param("state", state)
                .param("id", id)
                .update();
        revise(id, 1, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROPOSAL_ALREADY_DECIDED"));
        assertThat(count("proposal_revision", id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "OBJECT_TYPE",
                "PROPERTY",
                "LINK_TYPE",
                "OBJECT_MAPPING",
                "PROPERTY_MAPPING",
                "DOCUMENT_LINK"
            })
    void permitsOtherExistingCandidateKindsOnRevision(String kind) throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        jdbc.sql("update proposal_item set item_kind=:kind where id=:id")
                .param("kind", kind)
                .param("id", id)
                .update();
        mvc.perform(
                        post("/api/v1/proposals/{id}/revisions", id)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"baseRevision\":1,\"payload\":{\"code\":\"AI_CANDIDATE\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.itemKind").value(kind));
    }

    @Test
    void rollsBackCandidateRevisionAndCheckWhenAuditFails() throws Exception {
        doThrow(new IllegalStateException("감사 저장 실패 테스트")).when(auditRecorder).record(any());
        create().andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.sql("select count(*) from proposal_item where created_by=:id")
                                .param("id", accountId)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql("select count(*) from check_run where requested_by=:id")
                                .param("id", accountId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void onlyOneConcurrentRevisionOfSameBaseSucceeds() throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> revise(id, 1, Map.of()).andReturn());
            var second = workers.submit(() -> revise(id, 1, Map.of()).andReturn());
            assertThat(
                            List.of(
                                    first.get().getResponse().getStatus(),
                                    second.get().getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("proposal_revision", id)).isEqualTo(2);
        assertThat(
                        jdbc.sql("select count(*) from check_run where requested_by=:id")
                                .param("id", accountId)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void rejectsMalformedRulesBeforeAnyWrite() throws Exception {
        for (Object bad :
                List.of(
                        Map.of(),
                        Map.of("linkTypeId", linkTypeId, "conditions", List.of()),
                        Map.of(
                                "linkTypeId",
                                linkTypeId,
                                "conditions",
                                List.of(
                                        Map.of(
                                                "sourceProperty",
                                                "code",
                                                "targetProperty",
                                                "code",
                                                "operator",
                                                "NE"))))) {
            mvc.perform(
                            post("/api/v1/proposals/manual")
                                    .with(admin())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "itemKind",
                                                            "LINK_RULE",
                                                            "payload",
                                                            bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(
                        jdbc.sql("select count(*) from proposal_item where created_by=:id")
                                .param("id", accountId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void enforcesAuthenticationRoleAndCsrf() throws Exception {
        String body =
                json.writeValueAsString(Map.of("itemKind", "LINK_RULE", "payload", payload()));
        mvc.perform(
                        post("/api/v1/proposals/manual")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/proposals/manual")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/proposals/manual")
                                .with(
                                        authentication(
                                                new LoginAccount(accountId, "test", "VIEWER", false)
                                                        .toAuthentication()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void retainsAiOriginAndCodeReferencesAndClearsNewConfidence() throws Exception {
        long analysis = analysis();
        String code =
                jdbc.sql("select code from link_type where id=:id")
                        .param("id", linkTypeId)
                        .query(String.class)
                        .single();
        var rule =
                Map.<String, Object>of(
                        "linkTypeCode", code, "conditions", payload().get("conditions"));
        long id = aiCandidate(analysis, "LINK_RULE", rule);
        mvc.perform(
                        post("/api/v1/proposals/{id}/revisions", id)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("baseRevision", 1, "payload", rule))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.origin").value("AI"))
                .andExpect(jsonPath("$.data.analysisRunId").value(analysis))
                .andExpect(jsonPath("$.data.payload.linkTypeCode").value(code))
                .andExpect(jsonPath("$.data.aiConfidence").value(nullValue()));
        assertThat(
                        jdbc.sql(
                                        "select ai_confidence from proposal_revision where proposal_item_id=:id and revision=1")
                                .param("id", id)
                                .query(java.math.BigDecimal.class)
                                .single())
                .isEqualByComparingTo("0.9");
    }

    @Test
    void acceptsReferencesToDefinitionsStillPendingInSameAnalysis() throws Exception {
        long analysis = analysis();
        String source = "PENDING_S_" + accountId,
                target = "PENDING_T_" + accountId,
                relation = "PENDING_R_" + accountId;
        aiCandidate(analysis, "OBJECT_TYPE", Map.of("code", source));
        aiCandidate(analysis, "OBJECT_TYPE", Map.of("code", target));
        aiCandidate(analysis, "PROPERTY", Map.of("code", "code", "objectTypeCode", source));
        aiCandidate(analysis, "PROPERTY", Map.of("code", "code", "objectTypeCode", target));
        aiCandidate(
                analysis,
                "LINK_TYPE",
                Map.of("code", relation, "sourceTypeCode", source, "targetTypeCode", target));
        var rule =
                Map.<String, Object>of(
                        "linkTypeCode",
                        relation,
                        "conditions",
                        List.of(
                                Map.of(
                                        "sourceProperty",
                                        source + ".code",
                                        "targetProperty",
                                        target + ".code",
                                        "operator",
                                        "EQ")));
        long id = aiCandidate(analysis, "LINK_RULE", rule);
        mvc.perform(
                        post("/api/v1/proposals/{id}/revisions", id)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("baseRevision", 1, "payload", rule))))
                .andExpect(status().isCreated());
        assertThat(
                        jdbc.sql("select count(*) from link_type where code=:code")
                                .param("code", relation)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void recordsLastSuccessfulSyncPerResourceAndPreservesUncollectedNull() throws Exception {
        long source =
                jdbc.sql("select source_type_id from link_type where id=:id")
                        .param("id", linkTypeId)
                        .query(Long.class)
                        .single();
        long dataSource =
                jdbc.sql(
                                "insert into data_source(code,name,source_kind) values (:code,'테스트','POSTGRES') returning id")
                        .param("code", "DS_" + accountId)
                        .query(Long.class)
                        .single();
        long first = resource(dataSource, "first"), second = resource(dataSource, "second");
        for (long resource : List.of(second, first)) {
            jdbc.sql(
                            "insert into object_source_mapping(source_resource_id,object_type_id,key_columns) values (:resource,:type,'[\"id\"]')")
                    .param("resource", resource)
                    .param("type", source)
                    .update();
        }
        jdbc.sql("update object_source_mapping set is_active=false where source_resource_id=:id")
                .param("id", first)
                .update();
        jdbc.sql(
                        """
                insert into object_instance(object_type_id,source_mapping_id,source_key,display_name)
                select object_type_id,id,'{"id":1}','기존 객체' from object_source_mapping where source_resource_id=:id
                """)
                .param("id", first)
                .update();
        long succeeded = sync(dataSource, first, "SUCCEEDED");
        sync(dataSource, first, "FAILED");
        var result = create().andExpect(status().isCreated()).andReturn();
        long check =
                json.readTree(result.getResponse().getContentAsString())
                        .at("/data/checkRunId")
                        .asLong();
        var basis =
                json.readTree(
                        jdbc.sql("select data_basis::text from check_run where id=:id")
                                .param("id", check)
                                .query(String.class)
                                .single());
        assertThat(basis.path("resources").size()).isEqualTo(2);
        assertThat(basis.at("/resources/0/sourceResourceId").asLong()).isEqualTo(first);
        assertThat(basis.at("/resources/0/lastSuccessfulSyncRunId").asLong()).isEqualTo(succeeded);
        assertThat(basis.at("/resources/1/lastSuccessfulSyncRunId").isNull()).isTrue();
        long id = id(result);
        assertThat(candidateChecks.enqueue(id, 1, null, java.util.Set.of(source), accountId))
                .isEqualTo(check);
        sync(dataSource, first, "SUCCEEDED");
        long refreshed = candidateChecks.enqueue(id, 1, null, java.util.Set.of(source), accountId);
        assertThat(refreshed).isNotEqualTo(check);
        jdbc.sql(
                        "update check_run set status='SUCCEEDED',started_at=now(),finished_at=now() where id=:id")
                .param("id", refreshed)
                .update();
        assertThat(candidateChecks.enqueue(id, 1, null, java.util.Set.of(source), accountId))
                .isEqualTo(refreshed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBJECT_MAPPING", "PROPERTY_MAPPING"})
    void includesMappingResourcesChangedOutsideOriginalAnalysisTargets(String kind)
            throws Exception {
        long analysis = analysis();
        long ds =
                jdbc.sql(
                                "insert into data_source(code,name,source_kind) values (:code,'원천','POSTGRES') returning id")
                        .param("code", "MAPPING_" + accountId)
                        .query(Long.class)
                        .single();
        String sourceCode = "MAPPING_" + accountId;
        long first = resource(ds, sourceCode + ".first"),
                second = resource(ds, sourceCode + ".second");
        jdbc.sql(
                        "insert into analysis_run_target(analysis_run_id,source_resource_id) values (:analysis,:resource)")
                .param("analysis", analysis)
                .param("resource", first)
                .update();
        long id = aiCandidate(analysis, kind, mappingPayload(kind, sourceCode, "first"));
        var result =
                mvc.perform(
                                post("/api/v1/proposals/{id}/revisions", id)
                                        .with(admin())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "baseRevision",
                                                                1,
                                                                "payload",
                                                                mappingPayload(
                                                                        kind,
                                                                        sourceCode,
                                                                        "second")))))
                        .andExpect(status().isCreated())
                        .andReturn();
        long check =
                json.readTree(result.getResponse().getContentAsString())
                        .at("/data/checkRunId")
                        .asLong();
        assertThat(
                        jdbc.sql(
                                        "select (r->>'sourceResourceId')::bigint from check_run, jsonb_array_elements(data_basis->'resources') r where id=:id order by 1")
                                .param("id", check)
                                .query(Long.class)
                                .list())
                .containsExactly(first, second);
    }

    private static Map<String, Object> mappingPayload(
            String kind, String sourceCode, String table) {
        return "OBJECT_MAPPING".equals(kind)
                ? Map.of("sourceId", sourceCode, "resourceKey", sourceCode + "." + table)
                : Map.of(
                        "sourceColumn",
                        Map.of("sourceId", sourceCode, "table", table, "column", "id"));
    }

    @Test
    void rollsBackRevisionAndOptionsWhenAuditFails() throws Exception {
        long id = id(create().andExpect(status().isCreated()).andReturn());
        doThrow(new IllegalStateException("감사 저장 실패 테스트")).when(auditRecorder).record(any());
        revise(id, 1, Map.of("options", List.of(Map.of("payload", payload()))))
                .andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.sql("select revision from proposal_item where id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(count("proposal_revision", id)).isEqualTo(1);
        assertThat(count("proposal_option", id)).isZero();
        assertThat(
                        jdbc.sql("select count(*) from check_run where requested_by=:id")
                                .param("id", accountId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void rejectsMissingFieldsUnknownCandidateAndInvalidOptions() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"itemKind\":\"LINK_TYPE\",\"payload\":{}}",
                        "{\"itemKind\":\"LINK_RULE\",\"payload\":[]}")) {
            mvc.perform(
                            post("/api/v1/proposals/manual")
                                    .with(admin())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        revise(Long.MAX_VALUE, 1, Map.of()).andExpect(status().isNotFound());
        long id = id(create().andExpect(status().isCreated()).andReturn());
        revise(id, 0, Map.of()).andExpect(status().isBadRequest());
        revise(id, 1, Map.of("options", List.of(Map.of("payload", Map.of()))))
                .andExpect(status().isBadRequest());
        assertThat(count("proposal_revision", id)).isEqualTo(1);
        mvc.perform(
                        post("/api/v1/proposals/{id}/revisions", id)
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/proposals/{id}/revisions", id)
                                .with(
                                        authentication(
                                                new LoginAccount(accountId, "test", "VIEWER", false)
                                                        .toAuthentication()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("baseRevision", 1, "payload", payload()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidReferencesAndTransformArguments() throws Exception {
        for (Map<String, Object> rule :
                List.of(
                        Map.of(
                                "linkTypeId",
                                Long.MAX_VALUE,
                                "conditions",
                                payload().get("conditions")),
                        Map.of(
                                "linkTypeId",
                                linkTypeId,
                                "conditions",
                                List.of(
                                        Map.of(
                                                "sourceProperty",
                                                "missing",
                                                "targetProperty",
                                                "code",
                                                "operator",
                                                "EQ"))),
                        Map.of(
                                "linkTypeId",
                                linkTypeId,
                                "conditions",
                                List.of(
                                        Map.of(
                                                "sourceProperty",
                                                "code",
                                                "targetProperty",
                                                "code",
                                                "operator",
                                                "EQ",
                                                "sourceTransforms",
                                                List.of(Map.of("op", "PAD_ZEROS", "arg", 0))))))) {
            mvc.perform(
                            post("/api/v1/proposals/manual")
                                    .with(admin())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "itemKind",
                                                            "LINK_RULE",
                                                            "payload",
                                                            rule))))
                    .andExpect(status().isBadRequest());
        }
    }

    private long analysis() {
        return jdbc.sql(
                        "insert into analysis_run(run_kind,work_key,requested_by) values ('INITIAL',:key,:account) returning id")
                .param("key", UUID.randomUUID().toString())
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    private long aiCandidate(long analysis, String kind, Map<String, Object> payload) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(
                        status -> {
                            long id =
                                    jdbc.sql(
                                                    """
                    insert into proposal_item(analysis_run_id,origin,item_kind,revision,payload,ai_confidence)
                    values (:analysis,'AI',:kind,1,cast(:payload as jsonb),0.9) returning id
                    """)
                                            .param("analysis", analysis)
                                            .param("kind", kind)
                                            .param("payload", json.writeValueAsString(payload))
                                            .query(Long.class)
                                            .single();
                            jdbc.sql(
                                            "insert into proposal_revision(proposal_item_id,revision,payload,ai_confidence) values (:id,1,cast(:payload as jsonb),0.9)")
                                    .param("id", id)
                                    .param("payload", json.writeValueAsString(payload))
                                    .update();
                            return id;
                        });
    }

    private long resource(long source, String key) {
        return jdbc.sql(
                        "insert into source_resource(data_source_id,resource_key,resource_kind) values (:source,:key,'TABLE') returning id")
                .param("source", source)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long sync(long source, long resource, String state) {
        long run =
                jdbc.sql(
                                """
                insert into sync_run(data_source_id,trigger_kind,status,started_at,finished_at,error_message)
                values (:source,'SCHEDULED',:state,now(),now(),:error) returning id
                """)
                        .param("source", source)
                        .param("state", state)
                        .param(
                                "error",
                                state.equals("FAILED") ? "실패" : null,
                                java.sql.Types.VARCHAR)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into sync_run_resource(sync_run_id,data_source_id,source_resource_id,mode,status,started_at,finished_at,error_message)
                values (:run,:source,:resource,'FULL',:state,now(),now(),:error)
                """)
                .param("run", run)
                .param("source", source)
                .param("resource", resource)
                .param("state", state)
                .param("error", state.equals("FAILED") ? "실패" : null, java.sql.Types.VARCHAR)
                .update();
        return run;
    }

    private org.springframework.test.web.servlet.ResultActions create() throws Exception {
        return mvc.perform(
                post("/api/v1/proposals/manual")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of("itemKind", "LINK_RULE", "payload", payload()))));
    }

    private org.springframework.test.web.servlet.ResultActions revise(
            long id, int base, Map<String, Object> extras) throws Exception {
        var request = new java.util.LinkedHashMap<String, Object>(extras);
        request.put("baseRevision", base);
        request.put("payload", payload());
        return mvc.perform(
                post("/api/v1/proposals/{id}/revisions", id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)));
    }

    private Map<String, Object> payload() {
        return Map.of(
                "linkTypeId",
                linkTypeId,
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                "code",
                                "targetProperty",
                                "code",
                                "operator",
                                "EQ")));
    }

    private long id(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private RequestPostProcessor admin() {
        return authentication(
                new LoginAccount(accountId, "test", "ADMIN", false).toAuthentication());
    }

    private long type(String code) {
        return jdbc.sql("insert into object_type(code,name) values (:code,'유형') returning id")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long count(String table, long id) {
        return jdbc.sql("select count(*) from " + table + " where proposal_item_id=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}

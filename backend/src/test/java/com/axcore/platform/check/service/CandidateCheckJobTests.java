package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.SourceTestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.AiProposalRequest;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository;
import com.axcore.platform.review.repository.ProposalLatestCheckRepository;
import com.axcore.platform.review.service.AiProposalService;
import com.axcore.platform.review.service.ProposalDecisionService;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.service.CdcReloadJob;
import com.axcore.platform.source.service.SourceCredentialService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@ActiveProfiles("batch")
@Import(SourceTestcontainersConfiguration.class)
class CandidateCheckJobTests {

    @MockitoBean QueuedJobPoller poller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired QueuedJobRunner runner;
    @Autowired ObjectProvider<QueuedJobHandler<?>> handlers;
    @Autowired CandidateCheckService candidates;
    @Autowired AiProposalService aiProposals;
    @Autowired ProposalDecisionService decisions;
    @Autowired CheckRunJob approvedRuleJob;
    @Autowired CheckResultRepository results;
    @Autowired ProposalDecisionCheckRepository decisionChecks;
    @Autowired ProposalLatestCheckRepository latestChecks;
    @Autowired SourceCredentialService credentials;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PostgreSQLContainer postgres;

    private CheckRunTestFixtures fixtures;
    private long admin;

    @BeforeEach
    void setUp() {
        fixtures = new CheckRunTestFixtures(jdbc);
        admin = fixtures.insertAdmin();
    }

    @Test
    void savesActualCountsFromLiveSourceForTheQueuedRevision() {
        jdbc.sql(
                        """
                create schema if not exists candidate_src;
                create table candidate_src.orders (id bigint primary key, equipment_code text);
                create table candidate_src.equipment (id bigint primary key, code text);
                insert into candidate_src.equipment values (1, 'E1');
                insert into candidate_src.orders
                select id, case when id <= 7 then ' E1 ' else 'X' end from generate_series(1, 10) id;
                """)
                .update();
        long dataSource = source();
        long sourceType = fixtures.insertObjectType("WO");
        long targetType = fixtures.insertObjectType("EQ");
        long sourceResource = mappedResource(dataSource, "orders", sourceType, "equipment_code");
        mappedResource(dataSource, "equipment", targetType, "code");
        long linkType = fixtures.insertLinkType(sourceType, targetType, "MANY_TO_ONE");

        String revisionOne =
                """
                {"code":"equipment_rule","linkTypeId":%d,"conditions":[
                    {"sourceProperty":"equipment_code","targetProperty":"code",
                     "sourceTransforms":[{"op":"TRIM"}]}]}
                """
                        .formatted(linkType);
        long proposal = proposal("LINK_RULE", revisionOne);
        long runId = candidates.enqueue(proposal, 1, null, Set.of(sourceType, targetType), admin);
        assertThat(decisionChecks.findSuccessfulCheckRunId(proposal, 1)).isEmpty();
        assertThat(runner.runNext(approvedRuleJob)).isFalse();
        long otherProposal = proposal("LINK_RULE", revisionOne);
        long otherRun =
                candidates.enqueue(otherProposal, 1, null, Set.of(sourceType, targetType), admin);
        long selfLinkType = fixtures.insertLinkType(targetType, targetType, "ONE_TO_ONE");
        long selfProposal =
                proposal(
                        "LINK_RULE",
                        """
                {"code":"self_rule","linkTypeId":%d,"conditions":[
                    {"sourceProperty":"code","targetProperty":"code"}]}
                """
                                .formatted(selfLinkType));
        candidates.enqueue(selfProposal, 1, null, Set.of(targetType), admin);

        jdbc.sql("insert into candidate_src.orders values (11, 'E1'), (12, 'X')").update();
        fixtures.insertSync(dataSource, sourceResource, admin, "SUCCEEDED", Instant.now());
        String revisionTwo = revisionOne.replace("equipment_code", "wrong_property");
        jdbc.sql(
                        """
                with added as (
                    insert into proposal_revision (proposal_item_id, revision, payload)
                    values (:id, 2, cast(:payload as jsonb)) returning proposal_item_id
                )
                update proposal_item set revision=2, payload=cast(:payload as jsonb)
                where id in (select proposal_item_id from added)
                """)
                .param("id", proposal)
                .param("payload", revisionTwo)
                .update();

        new QueuedJobPoller(handlers, runner).poll();
        assertThat(status(runId)).isEqualTo("SUCCEEDED");
        assertThat(
                        results.findAll(
                                (root, query, builder) ->
                                        builder.equal(root.get("checkRunId"), runId)))
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.getProposalItemId()).isEqualTo(proposal);
                            assertThat(result.getProposalItemRevision()).isEqualTo(1);
                            assertThat(result.getLinkMappingId()).isNull();
                            assertThat(result.getTotalCount()).isEqualTo(12);
                            assertThat(result.getMatchedCount()).isEqualTo(8);
                            assertThat(result.getUnmatchedCount()).isEqualTo(4);
                        });
        assertThat(decisionChecks.findSuccessfulCheckRunId(proposal, 1)).contains(runId);
        assertThat(decisionChecks.findSuccessfulCheckRunId(proposal, 2)).isEmpty();
        assertThat(decisionChecks.findSuccessfulCheckRunId(otherProposal, 1)).contains(otherRun);
        var selfCheck =
                latestChecks
                        .findLatestByProposalRevisions(Map.of(selfProposal, 1))
                        .get(selfProposal);
        assertThat(selfCheck.status()).isEqualTo("SUCCEEDED");
        assertThat(selfCheck.totalCount()).isEqualTo(1);
        assertThat(selfCheck.matchedCount()).isZero();
        assertThat(
                        jdbc.sql("select count(*) from link_mapping where link_type_id=:id")
                                .param("id", linkType)
                                .query(Integer.class)
                                .single())
                .isZero();

        var approval =
                decisions.decide(
                        otherProposal,
                        new ProposalDecisionRequest(1, "APPROVE", null, "시험 결과 확인"),
                        new LoginAccount(admin, "candidate-admin", "ADMIN", false));
        assertThat(approval.applyStatus()).isEqualTo("APPLIED");
        assertThat(
                        jdbc.sql(
                                        """
                select (result->>'basisCheckRunId')::bigint from action_execution where id=:id
                """)
                                .param("id", approval.actionExecutionId())
                                .query(Long.class)
                                .single())
                .isEqualTo(otherRun);
    }

    @Test
    void excludesSelfBeforeCountingInCandidateApprovalAndSync() {
        jdbc.sql(
                        """
                create schema if not exists candidate_src;
                create table candidate_src.self_test (id bigint primary key, code text);
                insert into candidate_src.self_test values (1, 'SHARED'), (2, 'SHARED'), (3, 'ALONE');
                """)
                .update();
        long dataSource = source();
        long type = fixtures.insertObjectType("EQ_SELF");
        long resource = mappedResource(dataSource, "self_test", type, "code");
        long linkType = fixtures.insertLinkType(type, type, "ONE_TO_ONE");
        long proposal =
                proposal(
                        "LINK_RULE",
                        """
                        {"code":"self_count_rule","linkTypeId":%d,"conditions":[
                            {"sourceProperty":"code","targetProperty":"code"}]}
                        """
                                .formatted(linkType));
        long candidateRun = candidates.enqueue(proposal, 1, null, Set.of(type), admin);
        assertThat(
                        runner.runNext(
                                handlers.orderedStream()
                                        .filter(CandidateCheckJob.class::isInstance)
                                        .findFirst()
                                        .orElseThrow()))
                .isTrue();
        assertThat(status(candidateRun)).isEqualTo("SUCCEEDED");
        var candidate =
                latestChecks.findLatestByProposalRevisions(Map.of(proposal, 1)).get(proposal);
        assertThat(candidate.totalCount()).isEqualTo(3);
        assertThat(candidate.matchedCount()).isEqualTo(2);

        long mapping =
                jdbc.sql("select id from object_source_mapping where source_resource_id=:resource")
                        .param("resource", resource)
                        .query(Long.class)
                        .single();
        long first = fixtures.insertObject(mapping, type, "1", "{\"code\":\"SHARED\"}", "ACTIVE");
        long second = fixtures.insertObject(mapping, type, "2", "{\"code\":\"SHARED\"}", "ACTIVE");
        fixtures.insertObject(mapping, type, "3", "{\"code\":\"ALONE\"}", "ACTIVE");
        long sync = fixtures.insertSync(dataSource, resource, admin, "SUCCEEDED", Instant.now());
        var approval =
                decisions.decide(
                        proposal,
                        new ProposalDecisionRequest(1, "APPROVE", null, "시험 결과 확인"),
                        new LoginAccount(admin, "candidate-admin", "ADMIN", false));
        assertThat(approval.applyStatus()).isEqualTo("APPLIED");
        long rule = approval.appliedEntityIds().getFirst();
        assertThat(
                        jdbc.sql(
                                        """
                                select source_object_id, target_object_id from object_link
                                where link_mapping_id=:rule and status='ACTIVE'
                                """)
                                .param("rule", rule)
                                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2)))
                                .list())
                .containsExactlyInAnyOrder(List.of(first, second), List.of(second, first));
        // 혼자인 객체는 승인 반영이 따로 기록하지 않고 아래 SYNC가 판정한다(366).

        long syncRun =
                fixtures.insertQueuedRun(
                        "SYNC",
                        "{\"kind\":\"ALL\",\"linkMappingIds\":[%d]}".formatted(rule),
                        """
                        {"resources":[{"sourceResourceId":%d,"lastSuccessfulSyncRunId":%d}]}
                        """
                                .formatted(resource, sync),
                        null);
        assertThat(runner.runNext(approvedRuleJob)).isTrue();
        assertThat(status(syncRun)).isEqualTo("SUCCEEDED");
        assertThat(
                        results.findAll(
                                (root, query, builder) ->
                                        builder.equal(root.get("checkRunId"), syncRun)))
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.getTotalCount()).isEqualTo(3);
                            assertThat(result.getMatchedCount()).isEqualTo(2);
                            assertThat(result.getUnmatchedCount()).isEqualTo(1);
                            assertThat(result.getDuplicateCount()).isZero();
                        });
    }

    @Test
    void failsWithoutResultsWhenSyncCredentialIsMissing() {
        long dataSource = sourceWithoutCredentials();
        long type = fixtures.insertObjectType("EQ_WITHOUT_CREDENTIAL");
        mappedResource(dataSource, "equipment_without_credential", type, "code");
        long linkType = fixtures.insertLinkType(type, type, "MANY_TO_ONE");
        long proposal =
                proposal(
                        "LINK_RULE",
                        """
                        {"code":"no_credential_rule","linkTypeId":%d,"conditions":[
                            {"sourceProperty":"code","targetProperty":"code"}]}
                        """
                                .formatted(linkType));
        long runId = candidates.enqueue(proposal, 1, null, Set.of(type), admin);

        new QueuedJobPoller(handlers, runner).poll();

        assertThat(status(runId)).isEqualTo("FAILED");
        assertThat(
                        jdbc.sql("select error_message from check_run where id=:id")
                                .param("id", runId)
                                .query(String.class)
                                .single())
                .isEqualTo("SYNC 계정이 등록되지 않아 원천을 읽을 수 없습니다.");
        assertThat(
                        results.findAll(
                                (root, query, builder) ->
                                        builder.equal(root.get("checkRunId"), runId)))
                .isEmpty();
        assertThat(decisionChecks.findSuccessfulCheckRunId(proposal, 1)).isEmpty();
    }

    @Test
    void marksOtherKindsAndRulesWithOptionsUnsupportedWithoutResults() {
        long object = proposal("OBJECT_TYPE", "{}");
        long withOption = proposal("LINK_RULE", "{}");
        jdbc.sql(
                        """
                insert into proposal_option (proposal_item_id, revision, option_no, payload)
                values (:id, 1, 1, '{}'::jsonb)
                """)
                .param("id", withOption)
                .update();
        long objectRun = candidates.enqueue(object, 1, null, Set.of(), admin);
        long optionRun = candidates.enqueue(withOption, 1, null, Set.of(), admin);

        new QueuedJobPoller(handlers, runner).poll();

        assertThat(status(objectRun)).isEqualTo("FAILED");
        assertThat(status(optionRun)).isEqualTo("FAILED");
        assertThat(
                        jdbc.sql("select count(*) from check_result where check_run_id in (:runs)")
                                .param("runs", Set.of(objectRun, optionRun))
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql("select error_message from check_run where id in (:runs)")
                                .param("runs", Set.of(objectRun, optionRun))
                                .query(String.class)
                                .list())
                .containsOnly("이 후보는 아직 자동 시험을 지원하지 않음");
        assertThat(decisionChecks.findSuccessfulCheckRunId(withOption, 1)).isEmpty();
        assertThat(
                        latestChecks
                                .findLatestByProposalRevisions(Map.of(object, 1, withOption, 1))
                                .values())
                .hasSize(2)
                .allSatisfy(check -> assertThat(check.unsupported()).isTrue());
    }

    @Test
    void checksAiBundledRuleUsingPendingAndActiveMappings() {
        jdbc.sql(
                        """
                create schema candidate_ai;
                create table candidate_ai.orders (id bigint primary key, eq_ref text, status text);
                create table candidate_ai.equipment (id bigint primary key, code text);
                insert into candidate_ai.orders values (1, ' E1 ', 'OPEN'), (2, 'E1', 'DONE'), (3, 'X', 'OPEN');
                insert into candidate_ai.equipment values (1, 'E1');
                """)
                .update();
        long dataSource = source();
        String sourceCode =
                jdbc.sql("select code from data_source where id=:id")
                        .param("id", dataSource)
                        .query(String.class)
                        .single();
        String orders = "candidate_ai.orders";
        String equipment = "candidate_ai.equipment";
        long ordersId = resource(dataSource, orders, "eq_ref", "status");
        long equipmentId = resource(dataSource, equipment, "code");
        long analysis =
                jdbc.sql(
                                """
                insert into analysis_run (run_kind, work_key, status, requested_by, started_at, finished_at)
                values ('INITIAL', 'candidate-check', 'SUCCEEDED', :admin, now(), now()) returning id
                """)
                        .param("admin", admin)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into analysis_run_target (analysis_run_id, source_resource_id)
                values (:analysis, :orders), (:analysis, :equipment)
                """)
                .param("analysis", analysis)
                .param("orders", ordersId)
                .param("equipment", equipmentId)
                .update();

        var order =
                aiProposal(
                        "OBJECT_TYPE",
                        Map.of(
                                "code", "AI_WO",
                                "objectMapping",
                                        Map.of(
                                                "sourceId",
                                                sourceCode,
                                                "resourceKey",
                                                orders,
                                                "keyColumns",
                                                List.of("id")),
                                "properties",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "equipment_code",
                                                        "dataType",
                                                        "STRING",
                                                        "sourceColumn",
                                                        Map.of(
                                                                "sourceId",
                                                                sourceCode,
                                                                "resourceKey",
                                                                orders,
                                                                "column",
                                                                "eq_ref"),
                                                        "transformSteps",
                                                        List.of(Map.of("op", "TRIM"))),
                                                Map.of(
                                                        "code",
                                                        "status",
                                                        "dataType",
                                                        "STRING",
                                                        "sourceColumn",
                                                        Map.of(
                                                                "sourceId",
                                                                sourceCode,
                                                                "resourceKey",
                                                                orders,
                                                                "column",
                                                                "status")))));
        long targetType = fixtures.insertObjectType("AI_EQ");
        String targetCode = fixtures.typeCode(targetType);
        fixtures.insertObjectMapping(equipmentId, targetType, true);
        fixtures.insertProperty(targetType, "code");
        var target =
                aiProposal(
                        "PROPERTY_MAPPING",
                        Map.of(
                                "objectTypeCode",
                                targetCode,
                                "propertyCode",
                                "code",
                                "sourceColumn",
                                Map.of(
                                        "sourceId",
                                        sourceCode,
                                        "resourceKey",
                                        equipment,
                                        "column",
                                        "code")));
        var rule =
                aiProposal(
                        "LINK_RULE",
                        Map.of(
                                "code",
                                "ai_rule",
                                "linkTypeCode",
                                "AI_USES",
                                "linkType",
                                Map.of(
                                        "code",
                                        "AI_USES",
                                        "sourceTypeCode",
                                        "AI_WO",
                                        "targetTypeCode",
                                        targetCode,
                                        "cardinality",
                                        "MANY_TO_ONE"),
                                "sourceFilter",
                                Map.of("property", "AI_WO.status", "equals", "OPEN"),
                                "conditions",
                                List.of(
                                        Map.of(
                                                "sourceProperty",
                                                "AI_WO.equipment_code",
                                                "targetProperty",
                                                targetCode + ".code",
                                                "operator",
                                                "EQ"))));
        long proposal = aiProposals.save(analysis, List.of(order, target, rule)).getLast();

        new QueuedJobPoller(handlers, runner).poll();

        var check = latestChecks.findLatestByProposalRevisions(Map.of(proposal, 1)).get(proposal);
        assertThat(check.status()).isEqualTo("SUCCEEDED");
        assertThat(check.totalCount()).isEqualTo(2);
        assertThat(check.matchedCount()).isEqualTo(1);
        assertThat(check.unsupported()).isFalse();
    }

    private static AiProposalRequest aiProposal(String kind, Map<String, Object> payload) {
        return new AiProposalRequest(kind, payload, Map.of(), null, List.of());
    }

    private long resource(long dataSource, String table, String... properties) {
        long resource = fixtures.insertResource(dataSource, table);
        jdbc.sql("update source_resource set is_readable=true where id=:id")
                .param("id", resource)
                .update();
        sourceColumn(resource, "id", "int8", 1);
        for (int i = 0; i < properties.length; i++) {
            sourceColumn(resource, properties[i], "text", i + 2);
        }
        return resource;
    }

    private void sourceColumn(long resource, String column, String type, int position) {
        jdbc.sql(
                        """
                insert into source_column (
                    source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                values (:resource, :column, :type, true, :position)
                """)
                .param("resource", resource)
                .param("column", column)
                .param("type", type)
                .param("position", position)
                .update();
    }

    private long source() {
        long dataSource = sourceWithoutCredentials();
        credentials.store(
                dataSource,
                CredentialPurpose.SYNC,
                postgres.getUsername(),
                postgres.getPassword(),
                null);
        return dataSource;
    }

    private long sourceWithoutCredentials() {
        long dataSource = fixtures.insertDataSource();
        jdbc.sql("update data_source set connection_config = cast(:config as jsonb) where id = :id")
                .param(
                        "config",
                        json.writeValueAsString(
                                Map.of(
                                        "host",
                                        postgres.getHost(),
                                        "port",
                                        postgres.getMappedPort(5432),
                                        "database",
                                        postgres.getDatabaseName())))
                .param("id", dataSource)
                .update();
        return dataSource;
    }

    private long mappedResource(long dataSource, String table, long type, String property) {
        long resource = resource(dataSource, "candidate_src." + table, property);
        long mapping = fixtures.insertObjectMapping(resource, type, true);
        long propertyId = fixtures.insertProperty(type, property);
        long column =
                jdbc.sql(
                                """
                select id from source_column where source_resource_id = :resource and column_name = :column
                """)
                        .param("resource", resource)
                        .param("column", property)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into property_source_mapping (
                    source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                values (:mapping, :resource, :property, :column)
                """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", propertyId)
                .param("column", column)
                .update();
        return resource;
    }

    private long proposal(String kind, String payload) {
        return jdbc.sql(
                        """
                with added as (
                    insert into proposal_item (origin, item_kind, payload, created_by)
                    values ('MANUAL', :kind, cast(:payload as jsonb), :admin) returning id
                )
                insert into proposal_revision (proposal_item_id, revision, payload, created_by)
                select id, 1, cast(:payload as jsonb), :admin from added returning proposal_item_id
                """)
                .param("kind", kind)
                .param("payload", payload)
                .param("admin", admin)
                .query(Long.class)
                .single();
    }

    private String status(long runId) {
        return jdbc.sql("select status from check_run where id=:id")
                .param("id", runId)
                .query(String.class)
                .single();
    }
}

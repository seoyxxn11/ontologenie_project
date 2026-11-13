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

    @Test
    void skipsCandidateWithForbiddenTableOrColumnWithoutReadingSource() {
        // 원천 DB에는 mes.worker·erp.attendance 표가 없다. 원천을 읽었다면 "원천 테이블을 읽을 수 없습니다"로 끝난다.
        long dataSource = source();
        long worker = fixtures.insertObjectType("POLICY_WK");
        mappedResourceAt(dataSource, "mes.worker", worker, "name");
        long workerRun = selfRuleCheck(worker, "name");
        long attendance = fixtures.insertObjectType("POLICY_AT");
        mappedResourceAt(dataSource, "erp.attendance", attendance, "emp_no");
        long attendanceRun = selfRuleCheck(attendance, "emp_no");

        CandidateCheckJob job = candidateJob();
        drain(job);

        assertThat(status(workerRun)).isEqualTo("FAILED");
        assertThat(errorMessage(workerRun))
                .isEqualTo(CandidateCheckJob.FORBIDDEN_MESSAGE + "mes.worker.name");
        assertThat(status(attendanceRun)).isEqualTo("FAILED");
        assertThat(errorMessage(attendanceRun))
                .isEqualTo(CandidateCheckJob.FORBIDDEN_MESSAGE + "erp.attendance.*");
        assertThat(
                        jdbc.sql("select count(*) from check_result where check_run_id in (:runs)")
                                .param("runs", List.of(workerRun, attendanceRun))
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void storesOnlyCountsAndClassificationForRestrictedValues() {
        jdbc.sql(
                        """
                create schema if not exists mes;
                drop table if exists mes.maintenance_order;
                create table mes.maintenance_order (id bigint primary key, technician text);
                insert into mes.maintenance_order values (1, '김정비'), (2, '박비밀');
                create schema if not exists candidate_src;
                create table candidate_src.policy_techs (id bigint primary key, name text);
                insert into candidate_src.policy_techs values (1, '김정비');
                create table candidate_src.policy_orders (id bigint primary key, code text);
                create table candidate_src.policy_parts (id bigint primary key, code text);
                insert into candidate_src.policy_orders values (1, 'P1'), (2, 'SECRET-9');
                insert into candidate_src.policy_parts values (1, 'P1');
                """)
                .update();
        long dataSource = source();

        // 1) 민감 컬럼(mes.maintenance_order.technician)이 조건 키다. 속성 정의는 아직 NORMAL이다.
        long order = fixtures.insertObjectType("POLICY_MO");
        long tech = fixtures.insertObjectType("POLICY_TECH");
        mappedResourceAt(dataSource, "mes.maintenance_order", order, "technician");
        mappedResource(dataSource, "policy_techs", tech, "name");
        long columnRun =
                ruleCheck(
                        fixtures.insertLinkType(order, tech, "MANY_TO_ONE"),
                        "technician",
                        "name",
                        Set.of(order, tech));

        // 2) 원천 컬럼은 일반이지만 도착 속성 정의가 민감(RESTRICTED)이다.
        long part = fixtures.insertObjectType("POLICY_PO");
        long partType = fixtures.insertObjectType("POLICY_PT");
        mappedResource(dataSource, "policy_orders", part, "code");
        mappedResource(dataSource, "policy_parts", partType, "code");
        jdbc.sql(
                        "update property_definition set sensitivity='RESTRICTED' where object_type_id=:type")
                .param("type", partType)
                .update();
        long propertyRun =
                ruleCheck(
                        fixtures.insertLinkType(part, partType, "MANY_TO_ONE"),
                        "code",
                        "code",
                        Set.of(part, partType));

        CandidateCheckJob job = candidateJob();
        drain(job);

        for (long run : List.of(columnRun, propertyRun)) {
            assertThat(status(run)).isEqualTo("SUCCEEDED");
            var result =
                    results.findAll(
                                    (root, query, builder) ->
                                            builder.equal(root.get("checkRunId"), run))
                            .getFirst();
            assertThat(result.getTotalCount()).isEqualTo(2);
            assertThat(result.getMatchedCount()).isEqualTo(1);
            assertThat(result.getUnmatchedCount()).isEqualTo(1);
            String stored =
                    jdbc.sql("select sample_rows::text from check_result where check_run_id=:id")
                            .param("id", run)
                            .query(String.class)
                            .single();
            assertThat(stored).doesNotContain("김정비", "박비밀", "SECRET-9", "P1");
            var sample = json.readTree(stored).get(0);
            assertThat(sample.path("classification").asString()).isEqualTo("UNMATCHED");
            assertThat(sample.path("reason").asString()).isNotBlank();
            assertThat(sample.get("sourceKey").isNull()).isTrue();
            assertThat(sample.get("rawValue").isNull()).isTrue();
            assertThat(sample.get("transformedValue").isNull()).isTrue();
            assertThat(sample.path("restrictedFields").toString()).isEqualTo(ALL_WITHHELD);
        }
    }

    @Test
    void withholdsKeysThatCouldRevealRestrictedValues() {
        jdbc.sql(
                        """
                create schema if not exists candidate_src;
                create table candidate_src.policy_badges (id bigint primary key, badge text);
                create table candidate_src.policy_holders (id bigint primary key, badge text);
                insert into candidate_src.policy_badges values (1, 'B-77'), (2, 'B-77');
                insert into candidate_src.policy_holders values (1, 'B-77');
                create table candidate_src.policy_cards (id bigint primary key, badge text);
                create table candidate_src.policy_owners (id bigint primary key, badge text, tag text);
                insert into candidate_src.policy_cards values (1, 'C-1'), (2, 'C-1');
                insert into candidate_src.policy_owners values (1, 'C-1', 'T-SECRET');
                """)
                .update();
        long dataSource = source();

        // 1) 도착 조건 속성만 민감하고 키는 id다. 대응한 출발·도착 키 쌍과 일반인 출발 값으로 민감 값을 알 수 있어 키도 남기지 않는다.
        long badge = fixtures.insertObjectType("POLICY_BG");
        long holder = fixtures.insertObjectType("POLICY_HD");
        mappedResource(dataSource, "policy_badges", badge, "badge");
        mappedResource(dataSource, "policy_holders", holder, "badge");
        jdbc.sql(
                        "update property_definition set sensitivity='RESTRICTED' where object_type_id=:type")
                .param("type", holder)
                .update();
        long pairRun =
                ruleCheck(
                        fixtures.insertLinkType(badge, holder, "ONE_TO_ONE"),
                        "badge",
                        "badge",
                        Set.of(badge, holder));

        // 2) 조건은 일반 속성이지만 도착 매핑의 키 컬럼(tag)이 조건에 쓰지 않는 민감 속성을 담는다. 원천 키로 그 값이 드러난다.
        long card = fixtures.insertObjectType("POLICY_CD");
        long owner = fixtures.insertObjectType("POLICY_OW");
        mappedResource(dataSource, "policy_cards", card, "badge");
        long owners = mappedResource(dataSource, "policy_owners", owner, "badge");
        sourceColumn(owners, "tag", "text", 3);
        long tag =
                jdbc.sql(
                                """
                insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                values (:type, 'tag', 'tag', 'STRING', 'RESTRICTED') returning id
                """)
                        .param("type", owner)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into property_source_mapping (
                    source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                select m.id, m.source_resource_id, :property, c.id
                from object_source_mapping m
                join source_column c on c.source_resource_id = m.source_resource_id and c.column_name = 'tag'
                where m.source_resource_id = :resource
                """)
                .param("property", tag)
                .param("resource", owners)
                .update();
        jdbc.sql(
                        """
                update object_source_mapping set key_columns='["tag"]'::jsonb
                where source_resource_id=:resource
                """)
                .param("resource", owners)
                .update();
        long keyRun =
                ruleCheck(
                        fixtures.insertLinkType(card, owner, "ONE_TO_ONE"),
                        "badge",
                        "badge",
                        Set.of(card, owner));

        drain(candidateJob());

        var pairSamples = samples(pairRun);
        assertThat(pairSamples.toString()).doesNotContain("B-77");
        assertThat(pairSamples.size()).isEqualTo(2);
        for (var sample : pairSamples) {
            assertThat(sample.path("classification").asString()).isNotEqualTo("MATCHED");
            assertThat(sample.get("sourceKey").isNull()).isTrue();
            assertThat(sample.has("targetKey")).isTrue();
            assertThat(sample.get("targetKey").isNull()).isTrue();
            assertThat(sample.path("restrictedFields").toString()).isEqualTo(ALL_WITHHELD);
        }

        var keySamples = samples(keyRun);
        assertThat(keySamples.toString()).doesNotContain("T-SECRET");
        assertThat(keySamples.size()).isEqualTo(2);
        for (var sample : keySamples) {
            assertThat(sample.path("sourceKey").path("id").asString()).isNotBlank();
            assertThat(sample.path("rawValue").asString()).isEqualTo("C-1");
            assertThat(sample.has("targetKey")).isTrue();
            assertThat(sample.get("targetKey").isNull()).isTrue();
            assertThat(sample.path("restrictedFields").toString()).isEqualTo("[\"targetKey\"]");
        }
    }

    private static final String ALL_WITHHELD =
            "[\"rawValue\",\"sourceKey\",\"targetKey\",\"transformedValue\"]";

    private tools.jackson.databind.JsonNode samples(long runId) {
        assertThat(status(runId)).isEqualTo("SUCCEEDED");
        return json.readTree(
                jdbc.sql("select sample_rows::text from check_result where check_run_id=:id")
                        .param("id", runId)
                        .query(String.class)
                        .single());
    }

    @Test
    void skipsAiCandidateFilteringOnRestrictedPropertyWithoutReadingSource() {
        // 원천 DB에는 두 표가 없다. 원천을 읽었다면 "원천 테이블을 읽을 수 없습니다"로 끝난다.
        long dataSource = source();
        String sourceCode =
                jdbc.sql("select code from data_source where id=:id")
                        .param("id", dataSource)
                        .query(String.class)
                        .single();
        String orders = "candidate_src.policy_ai_orders";
        long ordersId = resource(dataSource, orders, "eq_ref", "status");
        long targetType = fixtures.insertObjectType("POLICY_AI_EQ");
        String targetCode = fixtures.typeCode(targetType);
        long equipmentId = mappedResource(dataSource, "policy_ai_equipment", targetType, "code");
        long analysis =
                jdbc.sql(
                                """
                insert into analysis_run (run_kind, work_key, status, requested_by, started_at, finished_at)
                values ('INITIAL', 'candidate-policy', 'SUCCEEDED', :admin, now(), now()) returning id
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
        Map<String, Object> eqRef = Map.of("sourceId", sourceCode, "resourceKey", orders);
        var order =
                aiProposal(
                        "OBJECT_TYPE",
                        Map.of(
                                "code",
                                "POLICY_AI_WO",
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
                                                withColumn(eqRef, "eq_ref")),
                                        // 후보 payload가 민감으로 지정한 속성을 행 조건에 쓴다.
                                        Map.of(
                                                "code",
                                                "status",
                                                "dataType",
                                                "STRING",
                                                "sensitivity",
                                                "RESTRICTED",
                                                "sourceColumn",
                                                withColumn(eqRef, "status")))));
        var rule =
                aiProposal(
                        "LINK_RULE",
                        Map.of(
                                "code",
                                "policy_ai_rule",
                                "linkTypeCode",
                                "POLICY_AI_USES",
                                "linkType",
                                Map.of(
                                        "code",
                                        "POLICY_AI_USES",
                                        "sourceTypeCode",
                                        "POLICY_AI_WO",
                                        "targetTypeCode",
                                        targetCode,
                                        "cardinality",
                                        "MANY_TO_ONE"),
                                "sourceFilter",
                                Map.of("property", "POLICY_AI_WO.status", "equals", "OPEN"),
                                "conditions",
                                List.of(
                                        Map.of(
                                                "sourceProperty",
                                                "POLICY_AI_WO.equipment_code",
                                                "targetProperty",
                                                targetCode + ".code",
                                                "operator",
                                                "EQ"))));
        long proposal = aiProposals.save(analysis, List.of(order, rule)).getLast();
        long run =
                jdbc.sql(
                                """
                select id from check_run
                where trigger_kind='CANDIDATE' and scope #>> '{proposalItems,0,proposalItemId}' = :proposal
                """)
                        .param("proposal", String.valueOf(proposal))
                        .query(Long.class)
                        .single();

        drain(candidateJob());

        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(errorMessage(run))
                .isEqualTo(CandidateCheckJob.RESTRICTED_FILTER_MESSAGE + "POLICY_AI_WO.status");
        assertThat(
                        jdbc.sql("select count(*) from check_result where check_run_id=:id")
                                .param("id", run)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private static Map<String, Object> withColumn(Map<String, Object> reference, String column) {
        var copy = new java.util.LinkedHashMap<>(reference);
        copy.put("column", column);
        return copy;
    }

    /** 다른 시험이 남긴 후보 시험까지 큐가 빌 때까지 처리한다. */
    private void drain(CandidateCheckJob job) {
        while (runner.runNext(job)) {
            // 다음 작업
        }
    }

    private CandidateCheckJob candidateJob() {
        return handlers.orderedStream()
                .filter(CandidateCheckJob.class::isInstance)
                .map(CandidateCheckJob.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private long selfRuleCheck(long type, String property) {
        return ruleCheck(
                fixtures.insertLinkType(type, type, "ONE_TO_ONE"),
                property,
                property,
                Set.of(type));
    }

    private long ruleCheck(long linkType, String source, String target, Set<Long> types) {
        long proposal =
                proposal(
                        "LINK_RULE",
                        """
                        {"code":"policy_rule","linkTypeId":%d,"conditions":[
                            {"sourceProperty":"%s","targetProperty":"%s"}]}
                        """
                                .formatted(linkType, source, target));
        return candidates.enqueue(proposal, 1, null, types, admin);
    }

    private String errorMessage(long runId) {
        return jdbc.sql("select error_message from check_run where id=:id")
                .param("id", runId)
                .query(String.class)
                .single();
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
        return mappedResourceAt(dataSource, "candidate_src." + table, type, property);
    }

    private long mappedResourceAt(long dataSource, String resourceKey, long type, String property) {
        long resource = resource(dataSource, resourceKey, property);
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

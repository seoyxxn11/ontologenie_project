package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.error.ErrorCode;
import com.axcore.platform.ontology.domain.ObjectLinkErrorCode;
import com.axcore.platform.ontology.dto.ConfirmedLinkRequest;
import com.axcore.platform.ontology.dto.SavedLink;
import com.axcore.platform.ontology.dto.SavedLink.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 확정 연결 저장(Jira 329)을 실제 DB로 확인한다. 작업지시(출발) → 설비(도착), MANY_TO_ONE 관계를 쓴다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ConfirmedLinkServiceTests {

    @Autowired ConfirmedLinkService service;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    long account;
    long workOrderType;
    long equipmentType;
    long linkType;

    @BeforeEach
    void setUp() {
        account =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '연결 확정', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "link-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        workOrderType = objectType("WORK_ORDER_");
        equipmentType = objectType("EQUIPMENT_");
        linkType = linkType(workOrderType, equipmentType, "MANY_TO_ONE");
    }

    @Test
    void manualLinkKeepsConfirmerProposalAndEvidence() {
        long wo = object(workOrderType);
        long eq = object(equipmentType);
        long proposal = proposal();

        SavedLink saved =
                manual(
                        new ConfirmedLinkRequest(
                                linkType, wo, eq, account, proposal, Map.of("heldItemId", 7)));

        assertThat(saved.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(saved.previousOrigin()).isNull();
        assertThat(saved.change().kind()).isEqualTo(ChangeKind.INSERT);
        assertThat(saved.change().after())
                .containsEntry("origin", "MANUAL")
                .containsEntry("sourceObjectId", wo)
                .containsEntry("targetObjectId", eq)
                .containsEntry("confirmedBy", account)
                .containsEntry("proposalItemId", proposal);
        assertThat(link(saved.linkId()))
                .containsEntry("origin", "MANUAL")
                .containsEntry("status", "ACTIVE")
                .containsEntry("confirmed_by", account)
                .containsEntry("proposal_item_id", proposal)
                .containsEntry("link_mapping_id", null)
                .containsEntry("evidence", "{\"heldItemId\": 7}");
    }

    @Test
    void inactiveRuleLinkOfThePairBecomesManual() {
        long wo = object(workOrderType);
        long eq = object(equipmentType);
        long rule = rule(linkType);
        long old = ruleLink(linkType, wo, eq, rule, "INACTIVE");

        SavedLink saved = manual(new ConfirmedLinkRequest(linkType, wo, eq, account, null, null));

        assertThat(saved.linkId()).isEqualTo(old);
        assertThat(saved.outcome()).isEqualTo(Outcome.REACTIVATED);
        assertThat(saved.previousOrigin()).isEqualTo("RULE");
        assertThat(saved.change().kind()).isEqualTo(ChangeKind.UPDATE);
        assertThat(saved.change().before())
                .containsEntry("origin", "RULE")
                .containsEntry("status", "INACTIVE")
                .containsEntry("linkMappingId", rule);
        assertThat(saved.change().after())
                .containsEntry("origin", "MANUAL")
                .containsEntry("linkMappingId", null);
        assertThat(link(old))
                .containsEntry("origin", "MANUAL")
                .containsEntry("status", "ACTIVE")
                .containsEntry("inactivated_at", null)
                .containsEntry("link_mapping_id", null)
                .containsEntry("confirmed_by", account)
                .containsEntry("evidence", "{}");
    }

    @Test
    void activePairIsRejectedWhateverItsOrigin() {
        long wo = object(workOrderType);
        long eq = object(equipmentType);
        long existing = ruleLink(linkType, wo, eq, rule(linkType), "ACTIVE");

        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, eq, account, null, null)),
                ObjectLinkErrorCode.LINK_ALREADY_EXISTS);
        assertThat(link(existing)).containsEntry("origin", "RULE");

        long other = object(workOrderType);
        manual(new ConfirmedLinkRequest(linkType, other, eq, account, null, null));
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, other, eq, account, null, null)),
                ObjectLinkErrorCode.LINK_ALREADY_EXISTS);
    }

    @Test
    void sourceAlreadyLinkedByRuleIsRejectedUntilItsTargetIsDeleted() {
        long wo = object(workOrderType);
        long first = object(equipmentType);
        long second = object(equipmentType);
        ruleLink(linkType, wo, first, rule(linkType), "ACTIVE");

        // 수동 연결은 규칙이 잇지 못한 출발의 예외다(366). 관계 수와 상관없이 받지 않는다.
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, second, account, null, null)),
                ObjectLinkErrorCode.LINK_SOURCE_LINKED_BY_RULE);

        deleteAtSource(first, equipmentType);
        assertThat(
                        manual(new ConfirmedLinkRequest(linkType, wo, second, account, null, null))
                                .outcome())
                .isEqualTo(Outcome.CREATED);
    }

    @Test
    void manualLinkKeepsTheSourceValuesItWasConfirmedWith() {
        long code =
                jdbc.sql(
                                """
                                insert into property_definition (object_type_id, code, name, data_type)
                                values (:type, 'equipment_code', 'equipment_code', 'STRING') returning id
                                """)
                        .param("type", workOrderType)
                        .query(Long.class)
                        .single();
        long target =
                jdbc.sql(
                                """
                                insert into property_definition (object_type_id, code, name, data_type)
                                values (:type, 'code', 'code', 'STRING') returning id
                                """)
                        .param("type", equipmentType)
                        .query(Long.class)
                        .single();
        // 비교 항목은 규칙 개정본을 만든 트랜잭션에서만 넣을 수 있다.
        tx.executeWithoutResult(
                status ->
                        jdbc.sql(
                                        """
                                        insert into link_mapping_condition
                                            (link_mapping_id, source_property_id, target_property_id, sequence_no)
                                        values (:rule, :source, :target, 1)
                                        """)
                                .param("rule", rule(linkType))
                                .param("source", code)
                                .param("target", target)
                                .update());
        long wo =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                values (:type, 'W23', '{"equipment_code": "E23", "memo": "x"}'::jsonb) returning id
                                """)
                        .param("type", workOrderType)
                        .query(Long.class)
                        .single();

        SavedLink saved =
                manual(
                        new ConfirmedLinkRequest(
                                linkType, wo, object(equipmentType), account, null, null));

        // 반영 중인 규칙이 출발에서 비교하는 속성만 남긴다. 재계산은 이 값이 바뀌면 이 연결을 끈다.
        assertThat(link(saved.linkId()))
                .containsEntry("evidence", "{\"sourceValues\": {\"equipment_code\": \"E23\"}}");
    }

    @Test
    void cardinalityCountsEveryOriginButSkipsDeletedEnds() {
        long wo = object(workOrderType);
        long first = object(equipmentType);
        long second = object(equipmentType);
        manual(new ConfirmedLinkRequest(linkType, wo, first, account, null, null));

        // MANY_TO_ONE: 작업지시 하나는 설비 하나에만
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, second, account, null, null)),
                ObjectLinkErrorCode.LINK_CARDINALITY_EXCEEDED);

        // 설비 쪽은 MANY라 다른 작업지시는 같은 설비에 이어진다
        long otherWo = object(workOrderType);
        assertThat(
                        manual(
                                        new ConfirmedLinkRequest(
                                                linkType, otherWo, first, account, null, null))
                                .outcome())
                .isEqualTo(Outcome.CREATED);

        // 원천에서 지운 설비로 가는 연결은 세지 않는다
        deleteAtSource(first, equipmentType);
        assertThat(
                        manual(new ConfirmedLinkRequest(linkType, wo, second, account, null, null))
                                .outcome())
                .isEqualTo(Outcome.CREATED);
    }

    @Test
    void oneToOneAlsoLimitsTheTargetSide() {
        long oneToOne = linkType(workOrderType, equipmentType, "ONE_TO_ONE");
        long eq = object(equipmentType);
        manual(new ConfirmedLinkRequest(oneToOne, object(workOrderType), eq, account, null, null));

        assertError(
                () ->
                        manual(
                                new ConfirmedLinkRequest(
                                        oneToOne, object(workOrderType), eq, account, null, null)),
                ObjectLinkErrorCode.LINK_CARDINALITY_EXCEEDED);
    }

    @Test
    void endsMustExistMatchTheLinkTypeAndBeActive() {
        long wo = object(workOrderType);
        long eq = object(equipmentType);

        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, wo, account, null, null)),
                ObjectLinkErrorCode.LINK_SELF_REFERENCE);
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, eq, wo, account, null, null)),
                ObjectLinkErrorCode.LINK_END_TYPE_MISMATCH);
        assertError(
                () ->
                        manual(
                                new ConfirmedLinkRequest(
                                        linkType, wo, Long.MAX_VALUE, account, null, null)),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(
                () -> manual(new ConfirmedLinkRequest(Long.MAX_VALUE, wo, eq, account, null, null)),
                CommonErrorCode.RESOURCE_NOT_FOUND);

        deleteAtSource(eq, equipmentType);
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, eq, account, null, null)),
                ObjectLinkErrorCode.LINK_END_NOT_ACTIVE);
        assertThat(count(wo)).isZero();

        // 387: 꺼진 매핑의 객체도 연결 대상이 아니다(검사·연결 재계산과 같은 정의).
        long paused = object(equipmentType);
        fromSource(paused, equipmentType, "ACTIVE", false);
        assertError(
                () -> manual(new ConfirmedLinkRequest(linkType, wo, paused, account, null, null)),
                ObjectLinkErrorCode.LINK_END_NOT_ACTIVE);
        assertThat(count(wo)).isZero();
    }

    @Test
    void documentLinkNeedsExpressionKindAndRepeatsAsAlreadyLinked() {
        long memoType = objectType("MEMO_");
        long mentions = linkType(memoType, equipmentType, "MANY_TO_MANY");
        long memo = object(memoType);
        long eq = object(equipmentType);

        assertError(
                () ->
                        document(
                                new ConfirmedLinkRequest(
                                        mentions,
                                        memo,
                                        eq,
                                        account,
                                        null,
                                        Map.of("expression_kind", "NEGATED"))),
                ObjectLinkErrorCode.LINK_EVIDENCE_INVALID);
        assertError(
                () -> document(new ConfirmedLinkRequest(mentions, memo, eq, account, null, null)),
                ObjectLinkErrorCode.LINK_EVIDENCE_INVALID);

        SavedLink first =
                document(
                        new ConfirmedLinkRequest(
                                mentions,
                                memo,
                                eq,
                                account,
                                null,
                                Map.of("expression_kind", "ESTIMATED", "quote", "3호기")));
        SavedLink again =
                document(
                        new ConfirmedLinkRequest(
                                mentions,
                                memo,
                                eq,
                                account,
                                null,
                                Map.of("expression_kind", "OBSERVED", "quote", "P201")));

        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(again.linkId()).isEqualTo(first.linkId());
        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_LINKED);
        assertThat(again.change()).isNull();
        assertThat(link(first.linkId()))
                .containsEntry("origin", "DOCUMENT")
                .containsEntry(
                        "evidence", "{\"quote\": \"3호기\", \"expression_kind\": \"ESTIMATED\"}");

        // 같은 쌍이라도 수동 승인은 문서 연결이 있으면 거절한다
        assertError(
                () -> manual(new ConfirmedLinkRequest(mentions, memo, eq, account, null, null)),
                ObjectLinkErrorCode.LINK_ALREADY_EXISTS);
    }

    @Test
    void runsOnlyInsideTheCallersTransaction() {
        long wo = object(workOrderType);
        long eq = object(equipmentType);

        assertThatThrownBy(
                        () ->
                                service.saveManual(
                                        new ConfirmedLinkRequest(
                                                linkType, wo, eq, account, null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * 같은 쌍을 동시에 저장하면 두 번째 트랜잭션은 관계 유형 잠금을 기다렸다가 먼저 커밋된 연결을 보고 409로 끝난다. {@code uq_object_link} 위반으로
     * 끝나지 않는다. 규칙 재계산도 같은 잠금({@code RuleLinkRepository#lockLinkType})을 잡는다.
     */
    @Test
    void concurrentSaveOfTheSamePairWaitsForTheLockAndSeesTheFirstLink() throws Exception {
        long wo = object(workOrderType);
        long eq = object(equipmentType);
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<SavedLink> first =
                CompletableFuture.supplyAsync(
                        () ->
                                tx.execute(
                                        status -> {
                                            SavedLink saved =
                                                    service.saveManual(
                                                            new ConfirmedLinkRequest(
                                                                    linkType, wo, eq, account, null,
                                                                    null));
                                            firstSaved.countDown();
                                            await(releaseFirst);
                                            return saved;
                                        }));
        assertThat(firstSaved.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> second =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                manual(
                                        new ConfirmedLinkRequest(
                                                linkType, wo, eq, account, null, null));
                                return null;
                            } catch (RuntimeException e) {
                                return e;
                            }
                        });
        waitUntilSomeoneWaitsForAnAdvisoryLock();
        releaseFirst.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(second.get(30, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ObjectLinkErrorCode.LINK_ALREADY_EXISTS));
        assertThat(count(wo)).isEqualTo(1);
    }

    private SavedLink manual(ConfirmedLinkRequest request) {
        return tx.execute(status -> service.saveManual(request));
    }

    private SavedLink document(ConfirmedLinkRequest request) {
        return tx.execute(status -> service.saveDocument(request));
    }

    private void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private void waitUntilSomeoneWaitsForAnAdvisoryLock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            long waiting =
                    jdbc.sql(
                                    "select count(*) from pg_locks where locktype = 'advisory' and not granted")
                            .query(Long.class)
                            .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("두 번째 저장이 관계 유형 잠금을 기다리지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long objectType(String prefix) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :code) returning id")
                .param("code", prefix + suffix())
                .query(Long.class)
                .single();
    }

    private long linkType(long source, long target, String cardinality) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, '확정 연결 테스트', :source, :target, :cardinality, '테스트') returning id
                        """)
                .param("code", "LINK_" + suffix())
                .param("source", source)
                .param("target", target)
                .param("cardinality", cardinality)
                .query(Long.class)
                .single();
    }

    /** 원천 삭제는 원천 객체만 될 수 있다(ck_object_instance_platform). 원천 매핑을 붙인 뒤 삭제로 표시한다. */
    private void deleteAtSource(long objectId, long type) {
        fromSource(objectId, type, "DELETED_AT_SOURCE", true);
    }

    /** 객체를 원천 매핑에서 수집한 것으로 바꾼다. */
    private void fromSource(long objectId, long type, String status, boolean mappingActive) {
        long dataSource =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, '원천', 'POSTGRES') returning id")
                        .param("code", "SRC_" + suffix())
                        .query(Long.class)
                        .single();
        long resource =
                jdbc.sql(
                                """
                                insert into source_resource(data_source_id, resource_key, resource_kind)
                                values (:source, 'public.test', 'TABLE') returning id
                                """)
                        .param("source", dataSource)
                        .query(Long.class)
                        .single();
        long mapping =
                jdbc.sql(
                                """
                                insert into object_source_mapping(source_resource_id, object_type_id, key_columns, is_active)
                                values (:resource, :type, '["id"]'::jsonb, :active) returning id
                                """)
                        .param("resource", resource)
                        .param("type", type)
                        .param("active", mappingActive)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        update object_instance
                        set source_mapping_id = :mapping, source_key = jsonb_build_object('id', id::text),
                            status = :status
                        where id = :id
                        """)
                .param("mapping", mapping)
                .param("status", status)
                .param("id", objectId)
                .update();
    }

    private long object(long type) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name)
                        values (:type, 'OBJ') returning id
                        """)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private long rule(long type) {
        return jdbc.sql(
                        """
                        insert into link_mapping (link_type_id, code, revision)
                        values (:type, :code, 1) returning id
                        """)
                .param("type", type)
                .param("code", "RULE_" + suffix())
                .query(Long.class)
                .single();
    }

    private long ruleLink(long type, long source, long target, long rule, String status) {
        return jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, link_mapping_id,
                             status, inactivated_at)
                        values (:type, :source, :target, 'RULE', :rule, :status,
                                case when :status = 'INACTIVE' then now() end)
                        returning id
                        """)
                .param("type", type)
                .param("source", source)
                .param("target", target)
                .param("rule", rule)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    // 후보는 첫 개정본과 같은 트랜잭션에서 넣어야 한다(지연 FK fk_proposal_item_current_revision).
    private long proposal() {
        return tx.execute(
                status -> {
                    long id =
                            jdbc.sql(
                                            """
                                            insert into proposal_item (origin, item_kind, payload, created_by)
                                            values ('MANUAL', 'LINK_RULE', '{}'::jsonb, :actor) returning id
                                            """)
                                    .param("actor", account)
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into proposal_revision (proposal_item_id, revision, payload, created_by)
                                    values (:id, 1, '{}'::jsonb, :actor)
                                    """)
                            .param("id", id)
                            .param("actor", account)
                            .update();
                    return id;
                });
    }

    private Map<String, Object> link(long id) {
        return jdbc.sql(
                        """
                        select origin, status, inactivated_at, link_mapping_id, confirmed_by, proposal_item_id,
                               evidence::text as evidence
                        from object_link where id = :id
                        """)
                .param("id", id)
                .query()
                .singleRow();
    }

    private long count(long source) {
        return jdbc.sql("select count(*) from object_link where source_object_id = :source")
                .param("source", source)
                .query(Long.class)
                .single();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}

package com.axcore.platform.ontology.service.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplyService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DOCUMENT_LINK 후보 반영(Jira 329)을 실제 DB로 확인한다. 교대 메모(mes.handover_note, 키 note_id) →
 * 설비(mes.equipment, 키 equipment_code)를 AI 후보 형식({@code documentId}, {@code targetObjectRef})으로 잇는다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DocumentLinkApplierTests {

    @Autowired ProposalApplyService service;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    long account;
    long memoType;
    long equipmentType;
    String linkTypeCode;
    long linkTypeId;
    long memoMapping;
    long equipmentMapping;
    String noteId;
    String equipmentCode;

    @BeforeEach
    void setUp() {
        account =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '문서 연결', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "doc-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        memoType = objectType("HANDOVER_NOTE_");
        equipmentType = objectType("EQUIPMENT_");
        linkTypeCode = "MENTIONS_EQUIPMENT_" + suffix();
        linkTypeId =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '메모가 언급한 설비', :source, :target, 'MANY_TO_MANY', '테스트') returning id
                                """)
                        .param("code", linkTypeCode)
                        .param("source", memoType)
                        .param("target", equipmentType)
                        .query(Long.class)
                        .single();
        long dataSource =
                jdbc.sql(
                                "insert into data_source(code, name, source_kind) values (:code, 'MES', 'POSTGRES') returning id")
                        .param("code", "MES_" + suffix())
                        .query(Long.class)
                        .single();
        memoMapping = mapping(dataSource, "mes.handover_note", memoType, "note_id");
        equipmentMapping = mapping(dataSource, "mes.equipment", equipmentType, "equipment_code");
        noteId = "HN-" + suffix();
        equipmentCode = "P" + suffix();
    }

    @Test
    void aiCandidateLinksTheMemoToTheMentionedEquipment() {
        long memo = sourceObject(memoType, memoMapping, "note_id", noteId);
        long equipment =
                sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);
        long proposal = proposal();

        ApplyResult result = apply(proposal, payload("OBSERVED"));

        assertThat(result.targetTable()).isEqualTo("object_link");
        assertThat(result.summary())
                .containsEntry("outcome", "CREATED")
                .containsEntry("linkTypeCode", linkTypeCode)
                .containsEntry("sourceObjectId", memo)
                .containsEntry("targetObjectId", equipment)
                .containsEntry("expressionKind", "OBSERVED");
        assertThat(result.changes())
                .singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(ChangeKind.INSERT));
        Map<String, Object> link =
                jdbc.sql(
                                """
                                select link_type_id, source_object_id, target_object_id, origin, status, confirmed_by,
                                       proposal_item_id, evidence::text as evidence
                                from object_link where id = :id
                                """)
                        .param("id", result.targetId())
                        .query()
                        .singleRow();
        assertThat(link)
                .containsEntry("link_type_id", linkTypeId)
                .containsEntry("source_object_id", memo)
                .containsEntry("target_object_id", equipment)
                .containsEntry("origin", "DOCUMENT")
                .containsEntry("status", "ACTIVE")
                .containsEntry("confirmed_by", account)
                .containsEntry("proposal_item_id", proposal);
        assertThat((String) link.get("evidence"))
                .contains("\"expression_kind\": \"OBSERVED\"")
                .contains("\"document_id\": \"" + noteId + "\"")
                .contains("\"span_start\": 36")
                .contains("\"span_end\": 40")
                .contains("\"quote\": \"" + equipmentCode + "\"");
    }

    @Test
    void secondMentionOfTheSamePairSucceedsWithTheExistingLink() {
        sourceObject(memoType, memoMapping, "note_id", noteId);
        sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);
        ApplyResult first = apply(proposal(), payload("ESTIMATED"));

        ApplyResult again = apply(proposal(), payload("OBSERVED"));

        assertThat(again.targetId()).isEqualTo(first.targetId());
        assertThat(again.summary()).containsEntry("outcome", "ALREADY_LINKED");
        assertThat(again.changes()).isEmpty();
    }

    @Test
    void objectIdsCanBeGivenInsteadOfSourceKeys() {
        long memo = sourceObject(memoType, memoMapping, "note_id", noteId);
        long equipment =
                sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("linkTypeId", linkTypeId);
        payload.put("sourceObjectId", memo);
        payload.put("targetObjectId", equipment);
        payload.put("expressionKind", "ESTIMATED");

        ApplyResult result = apply(proposal(), payload);

        assertThat(result.summary()).containsEntry("targetObjectId", equipment);
    }

    @Test
    void notLoadedObjectsAreMissingReferences() {
        sourceObject(memoType, memoMapping, "note_id", noteId);

        assertApplyError(
                payload("OBSERVED"),
                ApplyErrorCode.APPLY_REFERENCE_MISSING,
                "payload.targetObjectRef");

        Map<String, Object> otherTable = payload("OBSERVED");
        sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);
        otherTable.put("targetObjectRef", "mes.work_order:" + equipmentCode);
        assertApplyError(
                otherTable, ApplyErrorCode.APPLY_REFERENCE_MISSING, "payload.targetObjectRef");
    }

    @Test
    void invalidPayloadIsRejected() {
        sourceObject(memoType, memoMapping, "note_id", noteId);
        sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);

        assertApplyError(
                payload("NEGATED"), ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.expressionKind");

        Map<String, Object> noColon = payload("OBSERVED");
        noColon.put("targetObjectRef", equipmentCode);
        assertApplyError(noColon, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.targetObjectRef");

        Map<String, Object> unknownType = payload("OBSERVED");
        unknownType.put("linkTypeCode", "NO_SUCH_" + suffix());
        assertApplyError(
                unknownType, ApplyErrorCode.APPLY_REFERENCE_MISSING, "payload.linkTypeCode");
    }

    @Test
    void deletedTargetFailsWithTheLinkReason() {
        sourceObject(memoType, memoMapping, "note_id", noteId);
        long equipment =
                sourceObject(equipmentType, equipmentMapping, "equipment_code", equipmentCode);
        jdbc.sql("update object_instance set status = 'DELETED_AT_SOURCE' where id = :id")
                .param("id", equipment)
                .update();
        long proposal = proposal();

        assertThatThrownBy(() -> apply(proposal, payload("OBSERVED")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApplyErrorCode.APPLY_PAYLOAD_INVALID);
                            assertThat(e.getDetails())
                                    .containsEntry("linkError", "LINK_END_NOT_ACTIVE")
                                    .containsEntry("end", "target");
                        });
        assertThat(
                        jdbc.sql("select count(*) from object_link where link_type_id = :type")
                                .param("type", linkTypeId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private Map<String, Object> payload(String expressionKind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("linkTypeCode", linkTypeCode);
        payload.put("documentId", noteId);
        payload.put("spanStart", 36);
        payload.put("spanEnd", 40);
        payload.put("mentionText", equipmentCode);
        payload.put("targetObjectRef", "mes.equipment:" + equipmentCode);
        payload.put("expressionKind", expressionKind);
        payload.put("name", "교대 인수인계 메모의 설비");
        return payload;
    }

    private ApplyResult apply(long proposal, Map<String, Object> payload) {
        return tx.execute(
                status ->
                        service.apply(
                                new ApplyCommand(
                                        proposal,
                                        ProposalItemKind.DOCUMENT_LINK,
                                        ProposalOrigin.AI,
                                        payload,
                                        account)));
    }

    private void assertApplyError(Map<String, Object> payload, ApplyErrorCode code, String field) {
        long proposal = proposal();
        assertThatThrownBy(() -> apply(proposal, payload))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(String.valueOf(e.getDetails().get("field")))
                                    .startsWith(field);
                        });
    }

    private long mapping(long dataSource, String resourceKey, long objectType, String keyColumn) {
        long resource =
                jdbc.sql(
                                """
                                insert into source_resource(data_source_id, resource_key, resource_kind)
                                values (:source, :key, 'TABLE') returning id
                                """)
                        .param("source", dataSource)
                        .param("key", resourceKey)
                        .query(Long.class)
                        .single();
        return jdbc.sql(
                        """
                        insert into object_source_mapping(source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, jsonb_build_array(cast(:column as text))) returning id
                        """)
                .param("resource", resource)
                .param("type", objectType)
                .param("column", keyColumn)
                .query(Long.class)
                .single();
    }

    private long sourceObject(long type, long mapping, String keyColumn, String keyValue) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, source_mapping_id, source_key, display_name)
                        values (:type, :mapping, jsonb_build_object(cast(:column as text), cast(:value as text)), :value)
                        returning id
                        """)
                .param("type", type)
                .param("mapping", mapping)
                .param("column", keyColumn)
                .param("value", keyValue)
                .query(Long.class)
                .single();
    }

    private long objectType(String prefix) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :code) returning id")
                .param("code", prefix + suffix())
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
                                            values ('MANUAL', 'DOCUMENT_LINK', '{}'::jsonb, :actor) returning id
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

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}

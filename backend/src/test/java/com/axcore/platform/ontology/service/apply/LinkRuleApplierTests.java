package com.axcore.platform.ontology.service.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.dto.PropertyUpdateRequest;
import com.axcore.platform.ontology.service.PropertyService;
import com.axcore.platform.ontology.service.ProposalApplyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LINK_RULE 후보 반영(Jira 189 2단계·186)을 실제 DB로 확인한다. 작업지시(출발).equipment_code와 설비(도착).code를 비교하는 규칙을
 * 쓴다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class LinkRuleApplierTests {

    @Autowired ProposalApplyService service;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PropertyService propertyService;

    long account;
    String workOrderType;
    String equipmentType;
    long workOrderTypeId;
    long equipmentTypeId;
    String linkTypeCode;
    long linkTypeId;
    String ruleCode;

    @BeforeEach
    void setUp() {
        account =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '규칙 반영', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "rule-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        workOrderType = "WORK_ORDER_" + suffix();
        equipmentType = "EQUIPMENT_" + suffix();
        workOrderTypeId = objectType(workOrderType);
        equipmentTypeId = objectType(equipmentType);
        property(workOrderTypeId, "equipment_code");
        property(equipmentTypeId, "code");
        property(equipmentTypeId, "kind");
        linkTypeCode = "WO_EQ_" + suffix();
        linkTypeId =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '작업지시의 설비', :source, :target, 'MANY_TO_ONE', '규칙 반영 테스트') returning id
                                """)
                        .param("code", linkTypeCode)
                        .param("source", workOrderTypeId)
                        .param("target", equipmentTypeId)
                        .query(Long.class)
                        .single();
        ruleCode = "RULE_" + suffix();
    }

    @Test
    void firstRevisionLinksMatchesAndLeavesTheRestToSync() {
        long linked = workOrder("EQ-3");
        long unlinked = workOrder("W23");
        long empty = workOrder(" ");
        long eq = equipment("EQ-3", "프레스");
        long proposal = proposal();

        ApplyResult result = apply(proposal, rulePayload(List.of()));

        assertThat(result.targetTable()).isEqualTo("link_mapping");
        assertThat(
                        row(
                                "select code, revision, status, origin, proposal_item_id, created_by from link_mapping where id = :id",
                                result.targetId()))
                .containsEntry("code", ruleCode)
                .containsEntry("revision", 1)
                .containsEntry("status", "ACTIVE")
                .containsEntry("origin", "MANUAL")
                .containsEntry("proposal_item_id", proposal)
                .containsEntry("created_by", account);
        assertThat(count("link_mapping_condition", "link_mapping_id = " + result.targetId()))
                .isEqualTo(1);
        assertThat(
                        count(
                                "object_link",
                                "link_type_id = "
                                        + linkTypeId
                                        + " and source_object_id = "
                                        + linked
                                        + " and target_object_id = "
                                        + eq
                                        + " and status = 'ACTIVE' and link_mapping_id = "
                                        + result.targetId()))
                .isEqualTo(1);

        // 이어지지 않은 출발은 승인 반영이 따로 기록하지 않는다(366). 미연결은 SYNC 검사가 문제로 남긴다.
        assertThat(result.summary())
                .containsEntry("revision", 1)
                .containsEntry("linksActivated", 1)
                .doesNotContainKey("heldCount");

        // 커밋 뒤 새 개정본을 포함한 SYNC 검사가 바로 들어간다.
        assertThat(
                        count(
                                "check_run",
                                "trigger_kind = 'SYNC' and scope->'linkMappingIds' @> '["
                                        + result.targetId()
                                        + "]'::jsonb"))
                .isEqualTo(1);
    }

    @Test
    void newRevisionRetiresOldOneAndRebindsLinks() {
        long wo = workOrder("eq-3");
        long unlinked = workOrder("W23");
        equipment("EQ-3", "프레스");
        ApplyResult first = apply(proposal(), rulePayload(List.of()));
        assertThat(activeTargets(wo)).isEmpty();

        // 두 번째 개정본은 UPPER로 wo를 잇는다.
        ApplyResult second = apply(proposal(), rulePayload(List.of(Map.of("op", "UPPER"))));

        assertThat(row("select status from link_mapping where id = :id", first.targetId()))
                .containsEntry("status", "RETIRED");
        assertThat(
                        row(
                                "select revision, status from link_mapping where id = :id",
                                second.targetId()))
                .containsEntry("revision", 2)
                .containsEntry("status", "ACTIVE");
        assertThat(activeTargets(wo)).hasSize(1);
        assertThat(activeTargets(unlinked)).isEmpty();
        assertThat(second.summary()).containsEntry("retiredRevisionId", first.targetId());
    }

    @Test
    void secondRuleCodeOnTheSameLinkTypeIsRejected() {
        long wo = workOrder("EQ-1");
        long eq = equipment("EQ-1", "프레스");
        apply(proposal(), rulePayload(List.of()));
        property(workOrderTypeId, "alt_code");
        Map<String, Object> other = rulePayload(List.of());
        other.put("code", "RULE_B_" + suffix());
        other.put(
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                workOrderType + ".alt_code",
                                "targetProperty",
                                "code")));

        // 관계 유형마다 반영 중인 규칙은 하나다(367). 둘이면 한 출발을 다른 도착으로 이으려 할 때 연결이 빠진다(규칙 충돌).
        assertApplyError(other, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.code");
        assertThat(count("link_mapping", "link_type_id = " + linkTypeId + " and status = 'ACTIVE'"))
                .isEqualTo(1);
        assertThat(activeTargets(wo)).containsExactly(eq);

        // 같은 코드의 개정본은 그대로 받는다.
        assertThat(apply(proposal(), rulePayload(List.of(Map.of("op", "UPPER")))).summary())
                .containsEntry("revision", 2);

        // 승인 반영을 거치지 않는 저장도 DB가 막는다(V17 uq_link_mapping_active_link_type).
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                                                insert into link_mapping (link_type_id, code, revision)
                                                values (:type, :code, 1)
                                                """)
                                        .param("type", linkTypeId)
                                        .param("code", "RULE_C_" + suffix())
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_link_mapping_active_link_type");
    }

    @Test
    void sourceFilterLimitsLinkedSources() {
        long matching = workOrder("EQ-3");
        long outside = workOrder("W23");
        long eq = equipment("EQ-3", "프레스");
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("sourceFilter", Map.of("property", "equipment_code", "equals", "EQ-3"));

        apply(proposal(), payload);

        assertThat(activeTargets(matching)).containsExactly(eq);
        assertThat(activeTargets(outside)).isEmpty();
    }

    @Test
    void manyToOneSelfRelationLinksTheOtherObject() {
        long first = equipment("EQ-3", "프레스");
        long second = equipment("EQ-3", "선반");
        equipment("EQ-9", "프레스");
        String selfLinkType = "EQ_EQ_" + suffix();
        jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, '같은 코드 설비', :type, :type, 'MANY_TO_ONE', '자기 관계') returning id
                        """)
                .param("code", selfLinkType)
                .param("type", equipmentTypeId)
                .query(Long.class)
                .single();
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("linkTypeCode", selfLinkType);
        payload.put(
                "conditions", List.of(Map.of("sourceProperty", "code", "targetProperty", "code")));

        ApplyResult result = apply(proposal(), payload);

        assertThat(
                        jdbc.sql(
                                        """
                                select source_object_id, target_object_id from object_link
                                where link_mapping_id = :rule and status = 'ACTIVE'
                                """)
                                .param("rule", result.targetId())
                                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2)))
                                .list())
                .containsExactlyInAnyOrder(List.of(first, second), List.of(second, first));
    }

    @Test
    void aiBundleCreatesMissingLinkTypeFirst() {
        String newLinkType = "NEW_LINK_" + suffix();
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("linkTypeCode", newLinkType);
        payload.put(
                "linkType",
                Map.of(
                        "code",
                        newLinkType,
                        "name",
                        "새 관계",
                        "sourceTypeCode",
                        workOrderType,
                        "targetTypeCode",
                        equipmentType,
                        "cardinality",
                        "MANY_TO_ONE",
                        "description",
                        ""));

        ApplyResult result = apply(proposal(), payload);

        assertThat(count("link_type", "code = '" + newLinkType + "' and description = '새 관계'"))
                .isEqualTo(1);
        assertThat(result.changes()).extracting("tableName").contains("link_type", "link_mapping");
    }

    @Test
    void targetFilterLimitsCandidateTargets() {
        long filteredOut = workOrder("W99");
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("targetFilter", Map.of("property", equipmentType + ".kind", "equals", "금형"));
        long mold = equipment("EQ-3", "금형");
        long wo = workOrder("EQ-3");
        equipment("EQ-4", "프레스");

        apply(proposal(), payload);

        assertThat(activeTargets(wo)).containsExactly(mold);
        assertThat(activeTargets(filteredOut)).isEmpty();
    }

    @Test
    void invalidPayloadsAreRejectedBeforeAnyWrite() {
        Map<String, Object> wrongPrefix = rulePayload(List.of());
        wrongPrefix.put(
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                equipmentType + ".code",
                                "targetProperty",
                                "code")));
        assertApplyError(
                wrongPrefix,
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.conditions[0].sourceProperty");

        Map<String, Object> noLinkType = rulePayload(List.of());
        noLinkType.put("linkTypeCode", "NOT_APPROVED_" + suffix());
        assertApplyError(
                noLinkType, ApplyErrorCode.APPLY_REFERENCE_MISSING, "payload.linkTypeCode");

        Map<String, Object> badFilter = rulePayload(List.of());
        badFilter.put("sourceFilter", Map.of("any", List.of(1)));
        assertApplyError(badFilter, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.sourceFilter");

        Map<String, Object> badTransform =
                rulePayload(List.of(Map.of("op", "PAD_ZEROS", "arg", "x")));
        assertApplyError(
                badTransform,
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.conditions[0].sourceTransforms");

        Map<String, Object> noConditions = rulePayload(List.of());
        noConditions.put("conditions", List.of());
        assertApplyError(noConditions, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.conditions");

        assertThat(count("link_mapping", "code = '" + ruleCode + "'")).isZero();
    }

    @Test
    void sameRuleCodeMustStayOnItsLinkType() {
        apply(proposal(), rulePayload(List.of()));
        long otherLinkType =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '다른 관계', :source, :target, 'MANY_TO_MANY', '다른 관계') returning id
                                """)
                        .param("code", "OTHER_" + suffix())
                        .param("source", workOrderTypeId)
                        .param("target", equipmentTypeId)
                        .query(Long.class)
                        .single();
        Map<String, Object> payload = rulePayload(List.of());
        payload.remove("linkTypeCode");
        payload.put("linkTypeId", otherLinkType);

        assertApplyError(payload, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.linkTypeCode");
    }

    /** 연결 조건의 키는 민감 속성이 될 수 없다(391). 민감 속성으로 규칙을 만들 수 없고, 규칙의 키가 된 속성은 민감으로 바꿀 수 없다. */
    @Test
    void linkKeysStayNormal() {
        setEquipmentCodeSensitivity("RESTRICTED");
        assertApplyError(
                rulePayload(List.of()),
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.conditions[0].targetProperty");
        assertThat(count("link_mapping", "code = '" + ruleCode + "'")).isZero();

        setEquipmentCodeSensitivity("NORMAL");
        apply(proposal(), rulePayload(List.of()));
        long key =
                jdbc.sql(
                                "select id from property_definition where object_type_id = :type and code = 'code'")
                        .param("type", equipmentTypeId)
                        .query(Long.class)
                        .single();
        PropertyUpdateRequest request = new PropertyUpdateRequest();
        request.setName("code");
        request.setDataType("STRING");
        request.setIsRequired(false);
        request.setSensitivity("RESTRICTED");
        request.setRowVersion(
                jdbc.sql("select row_version from property_definition where id = :id")
                        .param("id", key)
                        .query(Long.class)
                        .single());

        assertThatThrownBy(() -> propertyService.update(key, request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getDetails()).containsEntry("reason", "LINK_KEY"));
    }

    /**
     * 출발·도착 조건({@code {property, equals}})에도 민감 속성을 쓸 수 없다. 조건 값이 규칙 정의에 그대로 남기 때문이다. 조건에 쓰는 속성은
     * 민감으로 바꿀 수 없다(391).
     */
    @Test
    void filterPropertiesStayNormal() {
        jdbc.sql(
                        "update property_definition set sensitivity = 'RESTRICTED' where object_type_id = :type and code = 'kind'")
                .param("type", equipmentTypeId)
                .update();
        Map<String, Object> restricted = rulePayload(List.of());
        restricted.put("targetFilter", Map.of("property", "kind", "equals", "PRESS"));
        assertApplyError(
                restricted, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.targetFilter.property");
        assertThat(count("link_mapping", "code = '" + ruleCode + "'")).isZero();

        jdbc.sql(
                        "update property_definition set sensitivity = 'NORMAL' where object_type_id = :type and code = 'kind'")
                .param("type", equipmentTypeId)
                .update();
        Map<String, Object> normal = rulePayload(List.of());
        normal.put("targetFilter", Map.of("property", equipmentType + ".kind", "equals", "PRESS"));
        apply(proposal(), normal);
        long kind =
                jdbc.sql(
                                "select id from property_definition where object_type_id = :type and code = 'kind'")
                        .param("type", equipmentTypeId)
                        .query(Long.class)
                        .single();
        PropertyUpdateRequest request = new PropertyUpdateRequest();
        request.setName("kind");
        request.setDataType("STRING");
        request.setIsRequired(false);
        request.setSensitivity("RESTRICTED");
        request.setRowVersion(
                jdbc.sql("select row_version from property_definition where id = :id")
                        .param("id", kind)
                        .query(Long.class)
                        .single());

        assertThatThrownBy(() -> propertyService.update(kind, request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getDetails()).containsEntry("reason", "LINK_KEY"));
    }

    private void setEquipmentCodeSensitivity(String sensitivity) {
        jdbc.sql(
                        "update property_definition set sensitivity = :sensitivity where object_type_id = :type and code = 'code'")
                .param("sensitivity", sensitivity)
                .param("type", equipmentTypeId)
                .update();
    }

    private Map<String, Object> rulePayload(List<Map<String, Object>> sourceTransforms) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("sourceProperty", workOrderType + ".equipment_code");
        condition.put("targetProperty", "code");
        condition.put("operator", "EQ");
        condition.put("sourceTransforms", sourceTransforms);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", ruleCode);
        payload.put("linkTypeCode", linkTypeCode);
        payload.put("pattern", "EXACT");
        payload.put("conditions", List.of(condition));
        payload.put("description", "작업지시의 설비 코드로 설비 연결");
        return payload;
    }

    private ApplyResult apply(long proposal, Map<String, Object> payload) {
        return tx.execute(
                status ->
                        service.apply(
                                new ApplyCommand(
                                        proposal,
                                        ProposalItemKind.LINK_RULE,
                                        ProposalOrigin.MANUAL,
                                        payload,
                                        account)));
    }

    private void assertApplyError(
            Map<String, Object> payload, ApplyErrorCode code, String fieldPrefix) {
        long proposal = proposal();
        ThrowingCallable call = () -> apply(proposal, payload);
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(String.valueOf(e.getDetails().get("field")))
                                    .startsWith(fieldPrefix);
                        });
    }

    private List<Long> activeTargets(long source) {
        return jdbc.sql(
                        """
                        select target_object_id from object_link
                        where link_type_id = :type and source_object_id = :source and status = 'ACTIVE'
                        """)
                .param("type", linkTypeId)
                .param("source", source)
                .query(Long.class)
                .list();
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

    private long workOrder(String equipmentCode) {
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties)
                        values (:type, :mapping, jsonb_build_object('id', gen_random_uuid()::text), 'WO',
                                jsonb_build_object('equipment_code', cast(:code as text))) returning id
                        """)
                .param("type", workOrderTypeId)
                .param("mapping", sourceMapping(workOrderTypeId))
                .param("code", equipmentCode)
                .query(Long.class)
                .single();
    }

    private long equipment(String code, String kind) {
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties)
                        values (:type, :mapping, jsonb_build_object('id', gen_random_uuid()::text), 'EQ',
                                jsonb_build_object('code', cast(:code as text), 'kind', cast(:kind as text)))
                        returning id
                        """)
                .param("type", equipmentTypeId)
                .param("mapping", sourceMapping(equipmentTypeId))
                .param("code", code)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    /** 유형을 원천에서 수집하는 활성 매핑. 규칙 연결은 원천에서 수집한 객체만 잇는다(388, 검사와 같은 대상). 유형마다 하나만 만든다. */
    private long sourceMapping(long objectTypeId) {
        return jdbc.sql(
                        """
                        select id from object_source_mapping where object_type_id = :type and is_active
                        """)
                .param("type", objectTypeId)
                .query(Long.class)
                .optional()
                .orElseGet(
                        () -> {
                            long source =
                                    jdbc.sql(
                                                    """
                                                    insert into data_source (code, name, source_kind)
                                                    values ('SRC-' || gen_random_uuid(), '원천', 'POSTGRES') returning id
                                                    """)
                                            .query(Long.class)
                                            .single();
                            long resource =
                                    jdbc.sql(
                                                    """
                                                    insert into source_resource (data_source_id, resource_key, resource_kind)
                                                    values (:source, 'public.test', 'TABLE') returning id
                                                    """)
                                            .param("source", source)
                                            .query(Long.class)
                                            .single();
                            return jdbc.sql(
                                            """
                                            insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                                            values (:resource, :type, '["id"]') returning id
                                            """)
                                    .param("resource", resource)
                                    .param("type", objectTypeId)
                                    .query(Long.class)
                                    .single();
                        });
    }

    private long objectType(String code) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :code) returning id")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void property(long objectTypeId, String code) {
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:type, :code, :code, 'STRING')
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .update();
    }

    private Map<String, Object> row(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query().singleRow();
    }

    private long count(String table, String where) {
        return jdbc.sql("select count(*) from " + table + " where " + where)
                .query(Long.class)
                .single();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}

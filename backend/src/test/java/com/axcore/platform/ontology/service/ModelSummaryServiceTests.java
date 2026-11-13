package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ConditionSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.PropertySummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.RuleSummary;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 모델 요약(API 043)이 조회 범위를 지키는지 실제 DB로 확인한다. 다른 테스트가 넣은 정의도 같은 DB에 있으므로 여기서 만든 ID만 본다.
 *
 * <p>VIEWER 기준: equipment 그룹은 허용(민감 값 막음), workOrder 그룹은 허용(민감 값 허용), hidden 그룹은 허용하지 않음.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ModelSummaryServiceTests {

    @Autowired ModelSummaryService service;
    @Autowired DataAccessPolicy policy;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    long equipment;
    long workOrder;
    long hidden;
    long equipmentCode;
    long purchasePrice;
    long workOrderEquipment;
    long workOrderSecret;
    long hiddenCode;
    String purchasePriceCode;

    long woToEquipment;
    long woToHidden;

    long activeRule;
    long retiredRule;
    long restrictedConditionRule;
    long restrictedFilterRule;
    long hiddenTypeRule;

    // 규칙의 비교 항목은 규칙을 만든 트랜잭션 안에서만 넣을 수 있다(DB 트리거). 준비 데이터를 한 트랜잭션에서 넣는다.
    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> seed());
    }

    private void seed() {
        String equipmentGroup = group();
        String workOrderGroup = group();
        equipment = objectType(equipmentGroup);
        workOrder = objectType(workOrderGroup);
        hidden = objectType(group());
        allow("VIEWER", equipmentGroup, false);
        allow("VIEWER", workOrderGroup, true);

        equipmentCode = property(equipment, "code", "NORMAL");
        purchasePriceCode = "price_" + suffix();
        purchasePrice = property(equipment, purchasePriceCode, "RESTRICTED");
        workOrderEquipment = property(workOrder, "equipment_code", "NORMAL");
        workOrderSecret = property(workOrder, "secret", "RESTRICTED");
        hiddenCode = property(hidden, "code", "NORMAL");

        woToEquipment = linkType(workOrder, equipment);
        woToHidden = linkType(workOrder, hidden);

        String code = "RULE_" + suffix();
        retiredRule = rule(woToEquipment, code, 1, "RETIRED", "{}");
        condition(retiredRule, workOrderEquipment, equipmentCode);
        activeRule = rule(woToEquipment, code, 2, "ACTIVE", "{}");
        condition(activeRule, workOrderEquipment, equipmentCode);
        jdbc.sql(
                        """
                        insert into link_mapping_condition
                            (link_mapping_id, source_property_id, target_property_id, sequence_no,
                             source_transforms)
                        values (:rule, :source, :target, 2, '[{"op":"UPPER","arg":null}]'::jsonb)
                        """)
                .param("rule", activeRule)
                .param("source", workOrderSecret)
                .param("target", equipmentCode)
                .update();

        // 관계 유형마다 반영 중 규칙은 하나라(V17) 같은 양 끝의 관계 유형을 따로 둔다.
        restrictedConditionRule =
                rule(linkType(workOrder, equipment), "RULE_" + suffix(), 1, "ACTIVE", "{}");
        condition(restrictedConditionRule, workOrderEquipment, purchasePrice);

        // 도착(설비) 조건이 민감 속성을 '유형코드.속성코드' 꼴로 가리킨다.
        restrictedFilterRule =
                jdbc.sql(
                                """
                                insert into link_mapping (link_type_id, code, revision, target_filter)
                                values (:linkType, :code, 1,
                                        jsonb_build_object('property', cast(:ref as text), 'equals', 'Y'))
                                returning id
                                """)
                        .param("linkType", linkType(workOrder, equipment))
                        .param("code", "RULE_" + suffix())
                        .param("ref", "EQUIPMENT." + purchasePriceCode)
                        .query(Long.class)
                        .single();
        condition(restrictedFilterRule, workOrderEquipment, equipmentCode);

        hiddenTypeRule = rule(woToHidden, "RULE_" + suffix(), 1, "ACTIVE", "{}");
        condition(hiddenTypeRule, workOrderEquipment, hiddenCode);
    }

    @Test
    void internalServiceSeesEveryDefinitionAndOnlyActiveRevisions() {
        ModelSummaryResponse summary = service.summarize(policy.forDefinitions());

        assertThat(typeIds(summary)).contains(equipment, workOrder, hidden);
        assertThat(propertyIds(summary))
                .contains(
                        equipmentCode,
                        purchasePrice,
                        workOrderEquipment,
                        workOrderSecret,
                        hiddenCode);
        assertThat(linkTypeIds(summary)).contains(woToEquipment, woToHidden);
        assertThat(ruleIds(summary))
                .contains(activeRule, restrictedConditionRule, restrictedFilterRule, hiddenTypeRule)
                .doesNotContain(retiredRule);

        RuleSummary rule = rule(summary, activeRule);
        assertThat(rule.revision()).isEqualTo(2);
        assertThat(rule.linkTypeId()).isEqualTo(woToEquipment);
        assertThat(rule.sourceFilter()).isEmpty();
        assertThat(rule.conditions())
                .extracting(ConditionSummary::sequenceNo)
                .containsExactly(1, 2);
        assertThat(rule.conditions().get(1).sourceTransforms())
                .singleElement()
                .satisfies(
                        step -> {
                            assertThat(step).containsEntry("op", "UPPER");
                            assertThat(step).containsKey("arg");
                        });
        assertThat(rule.conditions().get(1).operator()).isEqualTo("EQ");
    }

    @Test
    void viewerSeesOnlyAllowedTypesAndPropertiesAndRulesThatDoNotExposeHiddenOnes() {
        DataAccess viewer = policy.of(new LoginAccount(1L, "viewer", "VIEWER", false));

        ModelSummaryResponse summary = service.summarize(viewer);

        assertThat(typeIds(summary)).contains(equipment, workOrder).doesNotContain(hidden);
        assertThat(propertyIds(summary))
                .contains(equipmentCode, workOrderEquipment, workOrderSecret)
                .as("민감 값을 막은 유형의 민감 속성과 볼 수 없는 유형의 속성은 뺀다")
                .doesNotContain(purchasePrice, hiddenCode);
        assertThat(linkTypeIds(summary)).contains(woToEquipment).doesNotContain(woToHidden);
        assertThat(summary.linkTypes())
                .filteredOn(link -> link.id().equals(woToEquipment))
                .singleElement()
                .satisfies(link -> assertThat(link.name()).isEqualTo("관계 " + link.code()));
        assertThat(ruleIds(summary))
                .contains(activeRule)
                .doesNotContain(
                        retiredRule, restrictedConditionRule, restrictedFilterRule, hiddenTypeRule);
    }

    @Test
    void filterMentionCheckLooksAtKeysValuesAndQualifiedCodes() {
        Set<String> hiddenCodes = Set.of("price");

        assertThat(ModelSummaryService.mentionsAny(Map.of(), hiddenCodes)).isFalse();
        assertThat(ModelSummaryService.mentionsAny(Map.of("property", "EQ.price"), hiddenCodes))
                .isTrue();
        assertThat(ModelSummaryService.mentionsAny(Map.of("price", 3), hiddenCodes)).isTrue();
        assertThat(
                        ModelSummaryService.mentionsAny(
                                Map.of("all", List.of(Map.of("property", "price"))), hiddenCodes))
                .isTrue();
        assertThat(
                        ModelSummaryService.mentionsAny(
                                Map.of("property", "EQ.unit_price"), hiddenCodes))
                .isFalse();
        assertThat(ModelSummaryService.mentionsAny(Map.of("property", "price"), Set.of()))
                .isFalse();
    }

    private static List<Long> typeIds(ModelSummaryResponse summary) {
        return summary.objectTypes().stream().map(ObjectTypeSummary::id).toList();
    }

    private static List<Long> propertyIds(ModelSummaryResponse summary) {
        return summary.properties().stream().map(PropertySummary::id).toList();
    }

    private static List<Long> linkTypeIds(ModelSummaryResponse summary) {
        return summary.linkTypes().stream().map(LinkTypeSummary::id).toList();
    }

    private static List<Long> ruleIds(ModelSummaryResponse summary) {
        return summary.activeRules().stream().map(RuleSummary::id).toList();
    }

    private static RuleSummary rule(ModelSummaryResponse summary, long id) {
        return summary.activeRules().stream()
                .filter(rule -> rule.id() == id)
                .findFirst()
                .orElseThrow();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String group() {
        return "group-" + UUID.randomUUID();
    }

    private long objectType(String accessGroup) {
        String code = "SUMMARY_" + suffix();
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", code)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private long property(long objectTypeId, String code, String sensitivity) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity) returning id
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .query(Long.class)
                .single();
    }

    private long linkType(long source, long target) {
        String code = "LINK_" + suffix();
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, '관계 ' || :code, :source, :target, 'MANY_TO_ONE', '요약 테스트')
                        returning id
                        """)
                .param("code", code)
                .param("source", source)
                .param("target", target)
                .query(Long.class)
                .single();
    }

    private long rule(long linkTypeId, String code, int revision, String status, String filter) {
        return jdbc.sql(
                        """
                        insert into link_mapping (link_type_id, code, revision, source_filter, status, retired_at)
                        values (:linkType, :code, :revision, cast(:filter as jsonb), :status,
                                case when :status = 'RETIRED' then now() end)
                        returning id
                        """)
                .param("linkType", linkTypeId)
                .param("code", code)
                .param("revision", revision)
                .param("filter", filter)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private void condition(long ruleId, long source, long target) {
        jdbc.sql(
                        """
                        insert into link_mapping_condition
                            (link_mapping_id, source_property_id, target_property_id, sequence_no)
                        values (:rule, :source, :target, 1)
                        """)
                .param("rule", ruleId)
                .param("source", source)
                .param("target", target)
                .update();
    }

    private void allow(String roleCode, String accessGroup, boolean canViewRestricted) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, :restricted from role where code = :role
                        """)
                .param("group", accessGroup)
                .param("restricted", canViewRestricted)
                .param("role", roleCode)
                .update();
    }
}

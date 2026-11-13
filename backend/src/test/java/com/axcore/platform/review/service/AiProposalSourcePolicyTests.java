package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.review.dto.AiProposalRequest;
import com.axcore.platform.source.policy.TestSourcePolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 105로 받은 AI 후보에 원천 데이터 정책을 적용한다(399). 금지·민감 컬럼의 예시 값은 지우고, 금지 컬럼을 매핑하는 후보는 표시한다. */
class AiProposalSourcePolicyTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiProposalSourcePolicy policy =
            new AiProposalSourcePolicy(TestSourcePolicy.DEFAULT);

    /** 묶음 후보의 구성 근거까지 찾아 민감 컬럼의 예시 값만 지운다. 일반 컬럼 값과 위치는 남는다. */
    @Test
    void withholdsExampleValuesOfRestrictedColumnsAtAnyDepth() {
        AiProposalRequest saved =
                policy.apply(
                        proposal(
                                "OBJECT_TYPE",
                                """
                                {"code":"VENDOR",
                                 "objectMapping":{"sourceId":"ERP","resourceKey":"erp.vendor","keyColumns":["vendor_code"]},
                                 "properties":[
                                   {"code":"contact_name","sensitivity":"RESTRICTED",
                                    "sourceColumn":{"sourceId":"ERP","resourceKey":"erp.vendor","column":"contact_name"}},
                                   {"code":"vendor_name",
                                    "sourceColumn":{"sourceId":"ERP","resourceKey":"erp.vendor","column":"vendor_name"}}]}
                                """,
                                """
                                {"reason":"표 하나","items":[{"sourceId":"ERP","locator":"erp.vendor","snippet":"12행"}],
                                 "properties":[
                                   {"code":"contact_name","items":[{"sourceId":"ERP","locator":"erp.vendor.contact_name",
                                     "snippet":"값 12/12, 예 ['김담당']","values":["김담당"],"verified":true}],
                                    "mapping":{"items":[{"locator":"erp.vendor.contact_name","snippet":"예 ['김담당']","values":["김담당"]}]}},
                                   {"code":"vendor_name","items":[{"sourceId":"ERP","locator":"erp.vendor.vendor_name",
                                     "snippet":"값 12/12, 예 ['대성정밀']","values":["대성정밀"],"verified":true}]}]}
                                """));

        String evidence = json.writeValueAsString(saved.evidence());
        assertThat(evidence).doesNotContain("김담당").contains("대성정밀");
        var contact = part(saved.evidence(), 0);
        assertThat(item(contact, 0))
                .containsEntry("locator", "erp.vendor.contact_name")
                .containsEntry("snippet", AiProposalSourcePolicy.WITHHELD_SNIPPET)
                .containsEntry("values", List.of())
                .containsEntry("verified", true);
        assertThat(item(part(saved.evidence(), 1), 0)).containsEntry("values", List.of("대성정밀"));
        assertThat(item(saved.evidence(), 0)).containsEntry("snippet", "12행");
        // 민감 컬럼 매핑은 들일 수 있으므로 보류로 표시하지 않는다.
        assertThat(saved.evidence()).doesNotContainKeys("sourcePolicy", "holdRecommended");
    }

    /** 금지 표·금지 컬럼·묶은 컬럼(a+b)을 가리키는 근거도 지운다. 컬럼 이름에 마침표가 있어도 표 뒤를 한 컬럼으로 본다. */
    @Test
    void withholdsForbiddenTablesColumnsAndCombinedLocators() {
        AiProposalRequest saved =
                policy.apply(
                        proposal(
                                "LINK_RULE",
                                "{\"linkTypeCode\":\"WORKER_LINE\"}",
                                """
                                {"items":[
                                  {"locator":"erp.attendance.work_date","snippet":"예 2026-11-01","values":["2026-11-01"]},
                                  {"locator":"erp.attendance","snippet":"30행"},
                                  {"locator":"mes.worker.worker_code+name","snippet":"출발 값 예: W01 홍길동","values":[]},
                                  {"locator":"erp.employee.emp_no","snippet":"도착 값 예: E001","values":["E001"]},
                                  {"locator":"mes.spec.size","snippet":"예 10.5","values":["10.5"]}]}
                                """));

        var items = (List<?>) saved.evidence().get("items");
        assertThat(item(saved.evidence(), 0))
                .containsEntry("snippet", AiProposalSourcePolicy.WITHHELD_SNIPPET)
                .containsEntry("values", List.of());
        assertThat(item(saved.evidence(), 1))
                .containsEntry("snippet", AiProposalSourcePolicy.WITHHELD_SNIPPET)
                .doesNotContainKey("values");
        assertThat(item(saved.evidence(), 2))
                .containsEntry("snippet", AiProposalSourcePolicy.WITHHELD_SNIPPET);
        assertThat(item(saved.evidence(), 3)).containsEntry("values", List.of("E001"));
        assertThat(item(saved.evidence(), 4)).containsEntry("values", List.of("10.5"));
        assertThat(items).hasSize(5);
        assertThat(json.writeValueAsString(saved.evidence())).doesNotContain("홍길동", "2026-11-01");
    }

    /** 금지 컬럼을 매핑하는 후보·선택지는 근거에 표시하고 보류 권고로 바꾼다. AI가 붙인 보류 이유는 뒤에 남긴다. */
    @Test
    void marksCandidatesAndOptionsThatMapForbiddenColumns() {
        AiProposalRequest saved =
                policy.apply(
                        new AiProposalRequest(
                                "PROPERTY_MAPPING",
                                map(
                                        """
                                        {"objectTypeCode":"EMPLOYEE","propertyCode":"name",
                                         "sourceColumn":{"sourceId":"ERP","resourceKey":"erp.employee","column":"emp_name"}}
                                        """),
                                map(
                                        """
                                        {"reason":"이름 컬럼","holdRecommended":true,"holdReason":"동명이인 가능",
                                         "items":[{"locator":"erp.employee.emp_name","snippet":"예 ['홍길동']","values":["홍길동"]}]}
                                        """),
                                BigDecimal.ONE,
                                List.of(
                                        new AiProposalRequest.Option(
                                                1,
                                                map(
                                                        """
                                                        {"objectTypeCode":"EMPLOYEE","propertyCode":"name",
                                                         "sourceColumn":{"sourceId":"ERP","resourceKey":"erp.employee","column":"emp_no"}}
                                                        """),
                                                Map.of(),
                                                null),
                                        new AiProposalRequest.Option(
                                                2,
                                                map(
                                                        """
                                                        {"objectTypeCode":"ATTENDANCE","propertyCode":"day",
                                                         "sourceColumn":{"sourceId":"ERP","resourceKey":"erp.attendance","column":"work_date"}}
                                                        """),
                                                Map.of(),
                                                null))));

        assertThat(saved.evidence())
                .containsEntry("holdRecommended", true)
                .containsEntry(
                        "sourcePolicy",
                        Map.of("forbiddenColumns", List.of("erp.employee.emp_name")));
        assertThat((String) saved.evidence().get("holdReason"))
                .contains("erp.employee.emp_name")
                .endsWith(" / 동명이인 가능");
        assertThat(json.writeValueAsString(saved.evidence())).doesNotContain("홍길동");
        assertThat(saved.options().get(0).evidence()).isEmpty();
        assertThat(saved.options().get(1).evidence())
                .containsEntry("holdRecommended", true)
                .containsEntry(
                        "sourcePolicy", Map.of("forbiddenColumns", List.of("erp.attendance.*")));
    }

    /** 묶음 후보는 금지 컬럼을 매핑한 구성 근거(객체 매핑·속성)에도 표시한다. */
    @Test
    void marksForbiddenPartsOfBundledObjectType() {
        AiProposalRequest saved =
                policy.apply(
                        proposal(
                                "OBJECT_TYPE",
                                """
                                {"code":"WORKER",
                                 "objectMapping":{"sourceId":"MES","resourceKey":"mes.worker","keyColumns":["worker_code"]},
                                 "properties":[
                                   {"code":"name","sourceColumn":{"sourceId":"MES","resourceKey":"mes.worker","column":"name"}},
                                   {"code":"worker_code","sourceColumn":{"sourceId":"MES","resourceKey":"mes.worker","column":"worker_code"}}]}
                                """,
                                """
                                {"objectMapping":{"items":[]},
                                 "properties":[{"code":"name","items":[]},{"code":"worker_code","items":[]}]}
                                """));

        assertThat(saved.evidence())
                .containsEntry(
                        "sourcePolicy", Map.of("forbiddenColumns", List.of("mes.worker.name")));
        assertThat(part(saved.evidence(), 0)).containsEntry("holdRecommended", true);
        assertThat(part(saved.evidence(), 1)).doesNotContainKey("holdRecommended");
        assertThat(
                        ((Map<?, ?>) saved.evidence().get("objectMapping"))
                                .containsKey("holdRecommended"))
                .isFalse();
    }

    @Test
    void leavesProposalsWithoutPolicyColumnsAsReceived() {
        AiProposalRequest received =
                proposal(
                        "OBJECT_MAPPING",
                        """
                        {"objectTypeCode":"ITEM","sourceId":"ERP","resourceKey":"erp.item","keyColumns":["item_code"]}
                        """,
                        """
                        {"reason":"기본키","items":[{"locator":"erp.item.item_code","snippet":"예 ['P101']","values":["P101"]}]}
                        """);

        AiProposalRequest saved = policy.apply(received);

        assertThat(saved.evidence()).isEqualTo(received.evidence());
        assertThat(saved.payload()).isEqualTo(received.payload());
    }

    private AiProposalRequest proposal(String kind, String payload, String evidence) {
        return new AiProposalRequest(kind, map(payload), map(evidence), null, List.of());
    }

    private Map<String, Object> map(String text) {
        return json.readValue(text, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> evidence, int index) {
        return (Map<String, Object>) ((List<?>) evidence.get("properties")).get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> evidence, int index) {
        return (Map<String, Object>) ((List<?>) evidence.get("items")).get(index);
    }
}

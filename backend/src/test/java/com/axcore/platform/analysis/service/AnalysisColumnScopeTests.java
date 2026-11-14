package com.axcore.platform.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.source.policy.TestSourcePolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 분석에 넘길 컬럼 범위(399). 금지 표는 막고, 금지·민감 컬럼은 컬럼을 지정하지 않은 요청에서도 뺀다. */
class AnalysisColumnScopeTests {
    private final AnalysisColumnScope scope = new AnalysisColumnScope(TestSourcePolicy.DEFAULT);

    @Test
    void rejectsForbiddenTableWithAndWithoutColumns() {
        for (List<String> requested : Arrays.asList(null, List.of("work_date"))) {
            assertThatThrownBy(
                            () ->
                                    scope.columnsForModel(
                                            "erp.attendance", requested, Set.of("work_date")))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> {
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
                                assertThat(error.getDetails())
                                        .containsEntry("resourceKey", "erp.attendance")
                                        .containsEntry("forbiddenColumns", List.of("*"));
                            });
        }
    }

    /** 컬럼을 지정하지 않아도 정책 항목이 있는 표는 현재 컬럼에서 금지·민감을 뺀 목록을 이름순으로 보낸다. */
    @Test
    void sendsCatalogWithoutForbiddenAndRestrictedColumnsWhenNoneSelected() {
        assertThat(
                        scope.columnsForModel(
                                "mes.worker",
                                null,
                                Set.of("worker_code", "name", "skill_level", "line_code")))
                .containsExactly("line_code", "worker_code");
        assertThat(
                        scope.columnsForModel(
                                "erp.employee",
                                null,
                                Set.of(
                                        "emp_no",
                                        "emp_name",
                                        "position",
                                        "hire_date",
                                        "employment_type",
                                        "status",
                                        "dept_code")))
                .containsExactly("dept_code", "emp_no");
    }

    @Test
    void dropsForbiddenAndRestrictedColumnsFromSelection() {
        assertThat(
                        scope.columnsForModel(
                                "mes.maintenance_order",
                                List.of("technician", "order_no"),
                                Set.of("technician", "order_no", "equipment_code")))
                .containsExactly("order_no");
    }

    /** 민감 컬럼만 값 없이 구조로 넘긴다(416). 금지 컬럼(작업자 이름)은 이름도 넘기지 않는다. */
    @Test
    void passesOnlyRestrictedColumnsAsStructure() {
        assertThat(
                        scope.structureOnlyColumns(
                                "mes.worker", null, Set.of("name", "skill_level", "code")))
                .containsExactly("skill_level");
        assertThat(
                        scope.structureOnlyColumns(
                                "mes.maintenance_order",
                                List.of("order_no"),
                                Set.of("technician", "order_no")))
                .isEmpty();
    }

    /** 정책 항목이 없는 표에서 컬럼을 지정하지 않았으면 예전처럼 목록 없이(표 전체) 보낸다. */
    @Test
    void keepsWholeTableForTablesWithoutPolicyEntries() {
        assertThat(scope.columnsForModel("mes.work_order", null, Set.of("order_no"))).isNull();
        assertThat(scope.columnsForModel("mes.work_order", List.of("order_no"), Set.of()))
                .containsExactly("order_no");
    }

    /** 남는 컬럼이 없으면 빈 목록을 보내지 않는다. AI는 빈 목록을 표 전체로 읽는다. */
    @Test
    void rejectsWhenNoAnalyzableColumnRemains() {
        assertThatThrownBy(
                        () ->
                                scope.columnsForModel(
                                        "mes.worker",
                                        List.of("name", "skill_level"),
                                        Set.of("worker_code", "name", "skill_level")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getDetails())
                                        .containsEntry("reason", "NO_ANALYZABLE_COLUMNS"));
        assertThatThrownBy(() -> scope.columnsForModel("erp.vendor", null, Set.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void removesPolicyColumnsFromRegisteredMetadataOnly() {
        Map<String, Object> metadata =
                Map.of(
                        "description",
                        "작업자",
                        "columns",
                        Map.of(
                                "name", Map.of("description", "작업자 이름"),
                                "skill_level", Map.of("description", "숙련도"),
                                "worker_code", Map.of("description", "작업자 코드")));

        Map<String, Object> sent = scope.metadataForModel("mes.worker", metadata);

        assertThat(sent).containsEntry("description", "작업자");
        assertThat(List.copyOf(((Map<?, ?>) sent.get("columns")).keySet()))
                .isEqualTo(List.of("worker_code"));
        assertThat(scope.metadataForModel("mes.worker", Map.of("memo", "메모")))
                .isEqualTo(Map.of("memo", "메모"));
    }
}

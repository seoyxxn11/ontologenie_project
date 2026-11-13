package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.global.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceColumnPolicyTests {

    private final SourceColumnPolicy policy =
            new SourceColumnPolicy(
                    new SourceDataPolicyProperties(
                            List.of(
                                    "erp.attendance.*",
                                    "erp.employee.emp_name",
                                    " MES.Worker.Name "),
                            List.of("erp.vendor.contact_name", "erp.employee.emp_name")));

    @Test
    void gradesColumnsByTheListedPolicy() {
        assertThat(policy.gradeOf("erp.employee", "emp_name"))
                .isEqualTo(SourceColumnGrade.FORBIDDEN);
        assertThat(policy.gradeOf("erp.vendor", "contact_name"))
                .isEqualTo(SourceColumnGrade.RESTRICTED);
        assertThat(policy.gradeOf("erp.employee", "emp_no")).isEqualTo(SourceColumnGrade.NORMAL);
    }

    /** 표 전체 금지는 모든 컬럼을 금지로 본다. 두 목록에 다 있으면 금지가 이긴다. 대소문자·앞뒤 공백은 보지 않는다. */
    @Test
    void wholeTableAndForbiddenWinAndNamesIgnoreCase() {
        assertThat(policy.forbidsTable("erp.attendance")).isTrue();
        assertThat(policy.gradeOf("erp.attendance", "anything"))
                .isEqualTo(SourceColumnGrade.FORBIDDEN);
        assertThat(policy.forbidsTable("erp.employee")).isFalse();
        assertThat(policy.gradeOf("mes.worker", "name")).isEqualTo(SourceColumnGrade.FORBIDDEN);
        assertThat(policy.forbiddenColumns("erp.employee", List.of("emp_no", "emp_name")))
                .containsExactly("emp_name");
    }

    @Test
    void coversTablesThatHaveAnyListedEntry() {
        assertThat(policy.coversTable("erp.attendance")).isTrue();
        assertThat(policy.coversTable("ERP.Employee")).isTrue();
        assertThat(policy.coversTable("erp.vendor")).isTrue();
        assertThat(policy.coversTable("mes.worker")).isTrue();
        assertThat(policy.coversTable("erp.emp")).isFalse();
        assertThat(policy.coversTable("erp.item")).isFalse();
    }

    @Test
    void rejectsMappingToForbiddenTablesOrColumns() {
        policy.requireCollectable("erp.employee", List.of("emp_no"), "keyColumns");

        assertThatThrownBy(
                        () ->
                                policy.requireCollectable(
                                        "erp.employee",
                                        List.of("emp_no", "emp_name"),
                                        "keyColumns"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getDetails())
                                        .containsEntry("field", "keyColumns")
                                        .containsEntry("forbiddenColumns", List.of("emp_name")));
        assertThatThrownBy(
                        () ->
                                policy.requireCollectable(
                                        "erp.attendance", List.of("emp_no"), "keyColumns"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getDetails())
                                        .containsEntry("forbiddenColumns", List.of("*")));
    }
}

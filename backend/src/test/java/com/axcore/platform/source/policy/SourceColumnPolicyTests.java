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

    /** 형식이 틀린 항목은 이름을 모두 담아 거절한다(405). 공백·대소문자는 형식 검사에서도 보지 않는다. */
    @Test
    void rejectsMalformedEntries() {
        assertThatThrownBy(
                        () ->
                                new SourceColumnPolicy(
                                        new SourceDataPolicyProperties(
                                                List.of(
                                                        "erp.attendance",
                                                        "erp.employee.emp name",
                                                        " ERP.Fixed_Asset.* "),
                                                java.util.Arrays.asList(
                                                        "erp.vendor.contact_name", "", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden[erp.attendance]")
                .hasMessageContaining("forbidden[erp.employee.emp name]")
                .hasMessageContaining("restricted[]")
                .message()
                .doesNotContain("Fixed_Asset")
                .doesNotContain("contact_name");
        assertThatThrownBy(
                        () ->
                                new SourceColumnPolicy(
                                        new SourceDataPolicyProperties(
                                                List.of("erp.*.*", "1erp.t.c", "erp.t.c.d"),
                                                List.of())))
                .hasMessageContaining("forbidden[erp.*.*]")
                .hasMessageContaining("forbidden[1erp.t.c]")
                .hasMessageContaining("forbidden[erp.t.c.d]");
    }

    /** 민감은 컬럼 단위로만 판단하므로 표 전체(.*)를 받으면 아무 컬럼도 민감이 되지 않는다. 그래서 거절한다(405 리뷰). */
    @Test
    void rejectsWholeTableInRestrictedList() {
        assertThatThrownBy(
                        () ->
                                new SourceColumnPolicy(
                                        new SourceDataPolicyProperties(
                                                List.of("erp.attendance.*"),
                                                List.of("erp.vendor.*"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restricted[erp.vendor.*]")
                .message()
                .doesNotContain("forbidden[");
    }

    @Test
    void rejectsEmptyPolicyUnlessAllowed() {
        assertThatThrownBy(
                        () ->
                                new SourceColumnPolicy(
                                        new SourceDataPolicyProperties(List.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-empty=true");

        SourceColumnPolicy open =
                new SourceColumnPolicy(new SourceDataPolicyProperties(null, null, true));
        assertThat(open.gradeOf("erp.attendance", "emp_no")).isEqualTo(SourceColumnGrade.NORMAL);
        assertThat(open.forbiddenEntries()).isEmpty();
    }

    @Test
    void exposesNormalizedEntriesInOrder() {
        assertThat(policy.forbiddenEntries())
                .containsExactly("erp.attendance.*", "erp.employee.emp_name", "mes.worker.name");
        assertThat(policy.restrictedEntries())
                .containsExactly("erp.employee.emp_name", "erp.vendor.contact_name");
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

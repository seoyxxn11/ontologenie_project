package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourcePolicyCatalogCheckTests {

    private static final Set<String> BOTH = Set.of("erp", "mes");

    private final SourceColumnPolicy policy =
            new SourceColumnPolicy(
                    new SourceDataPolicyProperties(
                            List.of(
                                    "erp.attendance.*",
                                    "erp.change_log.*",
                                    "erp.employee.emp_name",
                                    "erp.employe.position"),
                            List.of(
                                    "erp.vendor.contact_name",
                                    "mes.worker.skil_level",
                                    "erp.employee.emp_name")));

    /** 원천 허용 목록이 적용된 카탈로그: 금지 표·컬럼은 보이지 않고, 민감 컬럼은 보인다. */
    @Test
    void appliedAllowlistOnlyReportsTypos() {
        Map<String, Set<String>> catalog =
                Map.of(
                        "erp.employee", Set.of("emp_no", "dept_code"),
                        "erp.vendor", Set.of("vendor_code", "contact_name"),
                        "mes.worker", Set.of("id", "skill_level"));

        assertThat(SourcePolicyCatalogCheck.compare(policy, catalog, BOTH))
                .containsExactly(
                        "금지 항목 erp.employe.position의 표 erp.employe가 카탈로그에 없습니다. 표 이름을 확인하세요.",
                        "민감 항목 mes.worker.skil_level가 카탈로그에 없습니다. 표·컬럼 이름을 확인하세요.");
    }

    /** 허용 목록을 적용하기 전(예전 권한)이면 금지 표·컬럼이 카탈로그에 보여 알린다. 두 목록에 다 있는 항목은 금지로만 본다. */
    @Test
    void reportsForbiddenDataStillVisible() {
        Map<String, Set<String>> catalog =
                Map.of(
                        "erp.attendance", Set.of("emp_no", "work_date"),
                        "erp.change_log", Set.of("row_key"),
                        "erp.employee", Set.of("emp_no", "emp_name"),
                        "erp.employe", Set.of("emp_no"),
                        "erp.vendor", Set.of("contact_name"),
                        "mes.worker", Set.of("skil_level"));

        assertThat(SourcePolicyCatalogCheck.compare(policy, catalog, BOTH))
                .containsExactly(
                        "금지 표 erp.attendance가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.",
                        "금지 표 erp.change_log가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.",
                        "금지 컬럼 erp.employee.emp_name가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.");
    }

    /** 한쪽 원천만 등록했으면 다른 스키마의 항목은 견주지 않는다. 등록 전마다 "표가 없다"는 경고가 나오지 않게 한다. */
    @Test
    void skipsSchemasWithoutReadableTables() {
        Map<String, Set<String>> catalog =
                Map.of(
                        "erp.employee", Set.of("emp_no"),
                        "erp.vendor", Set.of("contact_name"));

        assertThat(SourcePolicyCatalogCheck.compare(policy, catalog, Set.of("erp")))
                .containsExactly(
                        "금지 항목 erp.employe.position의 표 erp.employe가 카탈로그에 없습니다. 표 이름을 확인하세요.");
    }

    @Test
    void skipsWhenCatalogIsEmpty() {
        SourceResourceRepository resources = mock(SourceResourceRepository.class);
        SourceColumnRepository columns = mock(SourceColumnRepository.class);
        when(resources.findByIsReadableTrue()).thenReturn(List.of());

        assertThat(new SourcePolicyCatalogCheck(policy, resources, columns).check()).isEmpty();
        verify(columns, never()).findBySourceResourceIdInAndIsPresentTrue(any());
    }

    /** 카탈로그를 읽다 실패해도 기동을 멈추지 않는다. */
    @Test
    void startupCheckSurvivesCatalogFailure() {
        SourceResourceRepository resources = mock(SourceResourceRepository.class);
        when(resources.findByIsReadableTrue()).thenThrow(new IllegalStateException("db down"));
        var check =
                new SourcePolicyCatalogCheck(policy, resources, mock(SourceColumnRepository.class));

        assertThatCode(check::checkOnStartup).doesNotThrowAnyException();
    }
}

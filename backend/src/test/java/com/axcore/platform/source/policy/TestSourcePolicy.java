package com.axcore.platform.source.policy;

import java.util.List;

/** 단위 테스트용 원천 데이터 정책. application.yml 의 {@code axcore.source-data-policy}와 같은 목록이다. */
public final class TestSourcePolicy {

    public static final SourceColumnPolicy DEFAULT =
            new SourceColumnPolicy(
                    new SourceDataPolicyProperties(
                            List.of(
                                    "erp.attendance.*",
                                    "erp.fixed_asset.*",
                                    "erp.employee.emp_name",
                                    "erp.employee.position",
                                    "erp.employee.hire_date",
                                    "erp.employee.employment_type",
                                    "erp.employee.status",
                                    "mes.worker.name"),
                            List.of(
                                    "mes.maintenance_order.technician",
                                    "erp.vendor.contact_name",
                                    "mes.worker.skill_level")));

    private TestSourcePolicy() {}
}

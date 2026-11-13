package com.axcore.platform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.auth.repository.DataScopeRepository.ObjectTypeScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataAccessTests {

    /** 민감 값까지 볼 수 있는 유형(1), 민감 값은 막힌 유형(2), 볼 수 없는 유형(3). */
    private static final List<ObjectTypeScope> SCOPES =
            List.of(
                    new ObjectTypeScope(1L, "WorkOrder", true, true, Set.of("technician")),
                    new ObjectTypeScope(2L, "Vendor", true, false, Set.of("contact_name")),
                    new ObjectTypeScope(3L, "FixedAsset", false, false, Set.of()));

    /** 유형 코드로도 ID로 확인할 때와 같게 판단한다(148 완료 시각 계산이 쓴다). */
    @Test
    void checksVisibilityByTypeCodeLikeById() {
        DataAccess access = new DataAccess(SCOPES, DataPurpose.VIEW);

        assertThat(access.canView("WorkOrder")).isTrue().isEqualTo(access.canView(1L));
        assertThat(access.canView("FixedAsset")).isFalse().isEqualTo(access.canView(3L));
        assertThat(access.canView("Unknown")).isFalse();
    }

    /** 사람이 보는 용도는 허용 행대로 민감 값을 본다. */
    @Test
    void viewFollowsRoleSetting() {
        DataAccess access = new DataAccess(SCOPES, DataPurpose.VIEW);

        assertThat(access.canViewRestricted(1L)).isTrue();
        assertThat(access.canViewRestricted(2L)).isFalse();
        assertThat(access.maskedObjectTypeIds()).containsExactly(2L);
        assertThat(access.mask(1L, technician()).restrictedPropertyCodes()).isEmpty();
    }

    /** 모델 용도는 보이는 유형은 같고, 민감 값을 열어 준 역할이어도 가린다(384). */
    @Test
    void modelHidesRestrictedValuesEvenWhenRoleMayView() {
        DataAccess access = new DataAccess(SCOPES, DataPurpose.MODEL);

        assertThat(access.visibleObjectTypeIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(access.canViewRestricted(1L)).isFalse();
        assertThat(access.hidesValue("WorkOrder", "technician")).isTrue();
        assertThat(access.maskedObjectTypeIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(access.mask(1L, technician()).properties()).containsEntry("technician", null);
        access.requireObjectValues();
    }

    /** 정의 조회 용도로는 객체 값을 읽지 못한다. */
    @Test
    void definitionsCannotReadObjectValues() {
        DataAccess access = new DataAccess(SCOPES, DataPurpose.DEFINITIONS);

        assertThat(access.purpose()).isEqualTo(DataPurpose.DEFINITIONS);
        assertThatThrownBy(access::requireObjectValues).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> technician() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", "MO-1");
        values.put("technician", "홍길동");
        return values;
    }
}

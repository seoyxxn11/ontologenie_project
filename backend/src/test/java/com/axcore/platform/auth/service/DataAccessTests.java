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
    /** 볼 수 있어 가리지 않은 민감 값은 열람 기록(394)에 쓰도록 코드를 알린다. 값이 비었거나 가렸으면 알리지 않는다. */
    @Test
    void reportsRestrictedValuesLeftVisible() {
        DataAccess view = new DataAccess(SCOPES, DataPurpose.VIEW);
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("technician", "T-KIM");
        properties.put("order_no", "MO-1");

        assertThat(view.mask(1L, properties).revealedPropertyCodes()).containsExactly("technician");
        properties.put("technician", null);
        assertThat(view.mask(1L, properties).revealedPropertyCodes()).isEmpty();
        assertThat(
                        new DataAccess(SCOPES, DataPurpose.MODEL)
                                .mask(1L, Map.of("technician", "T-KIM"))
                                .revealedPropertyCodes())
                .isEmpty();
        assertThat(view.mask(2L, Map.of("contact_name", "홍길동")).revealedPropertyCodes()).isEmpty();
        assertThat(view.revealsValue("WorkOrder", "technician")).isTrue();
        assertThat(view.revealsValue("Vendor", "contact_name")).isFalse();
    }

    /**
     * 검사 용도는 보이는 유형이 같고 민감 값을 가리지 않는다(404). 표시 이름은 문구에 쓰이므로 보이는 유형 모두 가린 이름을 받는다. 민감 속성인지는 보는 사람과
     * 관계없이 알 수 있다.
     */
    @Test
    void checkReadsOriginalValuesOfVisibleTypesButMasksDisplayNames() {
        DataAccess access = new DataAccess(SCOPES, DataPurpose.CHECK);

        assertThat(access.visibleObjectTypeIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(access.canViewRestricted(2L)).isTrue();
        assertThat(access.canViewRestricted(3L)).isFalse();
        assertThat(access.mask(2L, Map.of("contact_name", "홍길동")).properties())
                .containsEntry("contact_name", "홍길동");
        assertThat(access.hidesValue("Vendor", "contact_name")).isFalse();
        assertThat(access.maskedObjectTypeIds()).containsExactlyInAnyOrder(1L, 2L);
        access.requireObjectValues();

        DataAccess model = new DataAccess(SCOPES, DataPurpose.MODEL);
        assertThat(model.isRestricted("Vendor", "contact_name")).isTrue();
        assertThat(model.isRestricted("WorkOrder", "code")).isFalse();
        assertThat(model.isRestricted("Unknown", "contact_name")).isFalse();
    }

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

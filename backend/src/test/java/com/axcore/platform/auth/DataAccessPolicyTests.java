package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.MaskedProperties;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 객체·민감 속성 조회 범위(S15P31C106-205)를 실제 DB로 확인한다. 기준(2026-11-06 결정, 384에서 수정): 모든 역할(ADMIN 포함)은 허용
 * 그룹만, 그룹 없는(미분류) 유형은 예약 이름의 허용 행을 가진 역할만(V29: ADMIN) 보고 외부 모델 범위에는 넣지 않음, 민감 값은 허용 행의
 * can_view_restricted가 true일 때만, 볼 수 없는 객체는 404. 정의 조회 용도만 모든 유형의 정의를 본다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DataAccessPolicyTests {

    @Autowired DataAccessPolicy policy;

    @Autowired JdbcClient jdbcClient;

    /** VIEWER에 허용하고 민감 값도 열어 준 그룹의 유형. */
    long openType;

    /** VIEWER에 허용했지만 민감 값은 막은 그룹의 유형. */
    long closedType;

    /** 어느 역할에도 허용하지 않은 그룹의 유형. */
    long unlistedType;

    /** 접근 그룹이 없는 유형. */
    long ungroupedType;

    @BeforeEach
    void setUp() {
        String open = group();
        String closed = group();
        openType = objectType(open);
        closedType = objectType(closed);
        unlistedType = objectType(group());
        ungroupedType = objectType(null);
        allow("VIEWER", open, true);
        allow("VIEWER", closed, false);
        for (long type : new long[] {openType, closedType, unlistedType, ungroupedType}) {
            property(type, "equipment_code", "NORMAL");
            property(type, "purchase_price", "RESTRICTED");
            property(type, "owner_phone", "RESTRICTED");
        }
    }

    /** ADMIN도 허용 행으로만 본다. 정의 관리 권한과 값 열람은 다르다(384). */
    @Test
    void adminFollowsAllowedRowsLikeOtherRoles() {
        DataAccess access = policy.of(account("ADMIN"));

        assertThat(access.visibleObjectTypeIds())
                .doesNotContain(openType, closedType, unlistedType);
        assertThat(access.mask(ungroupedType, values()).restrictedPropertyCodes())
                .containsExactly("purchase_price");

        allow("ADMIN", codeGroupOf(openType), false);

        DataAccess allowed = policy.of(account("ADMIN"));
        assertThat(allowed.canView(openType)).isTrue();
        assertThat(allowed.canViewRestricted(openType)).isFalse();
    }

    /**
     * 그룹이 없는(미분류) 유형은 예약 이름의 허용 행을 가진 역할에게만 보인다(384 보완, V29: ADMIN). 그룹을 정하는 사람이 그 유형의 값과 제안 근거를 봐야
     * 검토한 뒤 공개할 수 있다. 민감 값은 열지 않는다. 아직 검토 중인 유형이라 외부 모델로 보내는 범위에는 넣지 않는다.
     */
    @Test
    void unclassifiedTypeIsVisibleOnlyThroughTheReservedRow() {
        DataAccess admin = policy.of(account("ADMIN"));

        assertThat(admin.canView(ungroupedType)).isTrue();
        assertThat(admin.canViewRestricted(ungroupedType)).isFalse();
        assertThat(admin.mask(ungroupedType, values()).properties())
                .containsEntry("equipment_code", "PR-03")
                .containsEntry("purchase_price", null);
        // 허용 행이 없는 그룹은 예약 행과 관계없이 보이지 않는다
        assertThat(admin.canView(unlistedType)).isFalse();

        assertThat(policy.forModel(account("ADMIN")).canView(ungroupedType)).isFalse();
        assertThat(policy.forCheck(account("ADMIN")).canView(ungroupedType)).isTrue();
        assertThat(policy.of(account("VIEWER")).canView(ungroupedType)).isFalse();
        assertThat(policy.of(account("PLANNER")).canView(ungroupedType)).isFalse();
    }

    /** 정의 조회 용도는 역할과 관계없이 모든 유형의 정의를 본다(내부 모델 요약). */
    @Test
    void definitionsSeeEveryTypeRegardlessOfRole() {
        DataAccess access = policy.forDefinitions();

        assertThat(access.visibleObjectTypeIds())
                .contains(openType, closedType, unlistedType, ungroupedType);
        assertThat(access.hidesValue(codeOf(closedType), "purchase_price")).isFalse();
    }

    @Test
    void otherRolesSeeOnlyAllowedGroups() {
        DataAccess access = policy.of(account("VIEWER"));

        assertThat(access.visibleObjectTypeIds())
                .contains(openType, closedType)
                .doesNotContain(unlistedType, ungroupedType);
        assertThat(access.canView(unlistedType)).isFalse();
        assertThat(access.canView(ungroupedType)).isFalse();
    }

    @Test
    void roleWithoutAnyAllowedGroupSeesNothingOfTheseTypes() {
        DataAccess access = policy.of(account("PLANNER"));

        assertThat(access.visibleObjectTypeIds())
                .doesNotContain(openType, closedType, unlistedType, ungroupedType);
    }

    @Test
    void unknownTypeIsNotVisible() {
        DataAccess access = policy.of(account("ADMIN"));

        assertThat(access.canView(Long.MAX_VALUE)).isFalse();
        assertThatThrownBy(() -> access.mask(Long.MAX_VALUE, values()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invisibleObjectIsRejectedAsNotFound() {
        DataAccess access = policy.of(account("VIEWER"));

        access.requireVisible(openType, "object_instance", 1L);
        assertThatThrownBy(() -> access.requireVisible(ungroupedType, "object_instance", 7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getErrorCode())
                                    .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                            assertThat(error.getDetails())
                                    .containsEntry("resource", "object_instance")
                                    .containsEntry("id", 7L);
                        });
    }

    @Test
    void allowedRestrictedViewKeepsValues() {
        MaskedProperties masked = policy.of(account("VIEWER")).mask(openType, values());

        assertThat(masked.properties()).isEqualTo(values());
        assertThat(masked.restrictedPropertyCodes()).isEmpty();
    }

    @Test
    void blockedRestrictedViewHidesValuesButKeepsKeys() {
        Map<String, Object> original = values();

        MaskedProperties masked = policy.of(account("VIEWER")).mask(closedType, original);

        assertThat(masked.properties())
                .containsEntry("equipment_code", "PR-03")
                .containsEntry("purchase_price", null)
                .containsEntry("note", null)
                .doesNotContainKey("owner_phone");
        assertThat(masked.restrictedPropertyCodes()).containsExactly("purchase_price");
        assertThat(original).containsEntry("purchase_price", 1200);
    }

    @Test
    void restrictedPropertyWithEmptyValueIsStillReportedAsMasked() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("purchase_price", null);
        properties.put("owner_phone", "010-0000-0000");

        MaskedProperties masked = policy.of(account("VIEWER")).mask(closedType, properties);

        assertThat(masked.properties())
                .containsEntry("purchase_price", null)
                .containsEntry("owner_phone", null);
        assertThat(masked.restrictedPropertyCodes())
                .containsExactly("owner_phone", "purchase_price");
    }

    /** 범위를 따지지 않고 부른 경우에도 민감 값은 새지 않는다. */
    @Test
    void invisibleTypeIsMaskedToo() {
        MaskedProperties masked = policy.of(account("VIEWER")).mask(ungroupedType, values());

        assertThat(masked.properties()).containsEntry("purchase_price", null);
        assertThat(masked.restrictedPropertyCodes()).containsExactly("purchase_price");
    }

    /** V13·V21 초기값: 그룹 이름만 맞게 지정하면 행을 따로 넣지 않아도 역할별로 보인다. */
    @Test
    void seededScopeFollowsDecidedGroups() {
        long production = objectType("PRODUCTION");
        long salesSupply = objectType("SALES_SUPPLY");
        long finance = objectType("FINANCE");

        DataAccess planner = policy.of(account("PLANNER"));
        assertThat(planner.canViewRestricted(production)).isTrue();
        assertThat(planner.canViewRestricted(salesSupply)).isTrue();
        assertThat(planner.canView(finance)).isFalse();

        DataAccess viewer = policy.of(account("VIEWER"));
        assertThat(viewer.canView(production)).isTrue();
        assertThat(viewer.canViewRestricted(production)).isFalse();
        assertThat(viewer.canViewRestricted(salesSupply)).isFalse();
        assertThat(viewer.canView(finance)).isFalse();

        DataAccess admin = policy.of(account("ADMIN"));
        assertThat(admin.canView(production)).isTrue();
        assertThat(admin.canView(salesSupply)).isTrue();
        assertThat(admin.canViewRestricted(production)).isFalse();
        assertThat(admin.canView(finance)).isFalse();

        DataAccess maintenance = policy.of(account("MAINTENANCE"));
        assertThat(maintenance.canView(production)).isFalse();
        assertThat(maintenance.canView(salesSupply)).isFalse();
    }

    @Test
    void hidesRestrictedValueByTypeCode() {
        DataAccess viewer = policy.of(account("VIEWER"));

        assertThat(viewer.hidesValue(codeOf(closedType), "purchase_price")).isTrue();
        assertThat(viewer.hidesValue(codeOf(closedType), "equipment_code")).isFalse();
        assertThat(viewer.hidesValue(codeOf(openType), "purchase_price")).isFalse();
        assertThat(viewer.hidesValue(codeOf(ungroupedType), "purchase_price")).isTrue();
        assertThat(viewer.hidesValue("no_such_type", "purchase_price")).isFalse();
        assertThat(policy.of(account("ADMIN")).hidesValue(codeOf(closedType), "purchase_price"))
                .isTrue();
    }

    @Test
    void scopeReflectsRoleSettingChangesOnNextRead() {
        String later = group();
        long type = objectType(later);

        assertThat(policy.of(account("MAINTENANCE")).canView(type)).isFalse();

        allow("MAINTENANCE", later, false);

        assertThat(policy.of(account("MAINTENANCE")).canView(type)).isTrue();
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("equipment_code", "PR-03");
        values.put("purchase_price", 1200);
        values.put("note", null);
        return values;
    }

    private static LoginAccount account(String roleCode) {
        return new LoginAccount(1L, "tester", roleCode, false);
    }

    private static String group() {
        return "group-" + UUID.randomUUID();
    }

    private long objectType(String accessGroup) {
        String code = "scope_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcClient
                .sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", code)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private String codeGroupOf(long objectTypeId) {
        return jdbcClient
                .sql("select access_group from object_type where id = :id")
                .param("id", objectTypeId)
                .query(String.class)
                .single();
    }

    private String codeOf(long objectTypeId) {
        return jdbcClient
                .sql("select code from object_type where id = :id")
                .param("id", objectTypeId)
                .query(String.class)
                .single();
    }

    private void property(long objectTypeId, String code, String sensitivity) {
        jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity)
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .update();
    }

    private void allow(String roleCode, String accessGroup, boolean canViewRestricted) {
        jdbcClient
                .sql(
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

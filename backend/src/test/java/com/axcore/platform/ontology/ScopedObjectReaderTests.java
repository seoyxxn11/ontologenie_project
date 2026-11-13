package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.repository.ScopedObjectReader;
import com.axcore.platform.ontology.repository.ScopedObjectReader.ScopedObject;
import java.util.List;
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
 * 공통 읽기 진입점(S15P31C106-384)을 실제 DB로 확인한다. VIEWER에게 민감 값까지 열어 준 그룹(open), 민감 값은 막은 그룹(closed), 아무
 * 역할에도 주지 않은 그룹 없는 유형(ungrouped)을 만든다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ScopedObjectReaderTests {

    private static final LoginAccount VIEWER = new LoginAccount(1L, "tester", "VIEWER", false);

    @Autowired ScopedObjectReader reader;

    @Autowired DataAccessPolicy policy;

    @Autowired JdbcClient jdbc;

    String openCode;
    String closedCode;
    long openObject;
    long closedObject;
    long ungroupedObject;

    @BeforeEach
    void setUp() {
        String open = "group-" + UUID.randomUUID();
        String closed = "group-" + UUID.randomUUID();
        allow(open, true);
        allow(closed, false);

        long openType = objectType(open);
        long closedType = objectType(closed);
        long ungroupedType = objectType(null);
        openCode = codeOf(openType);
        closedCode = codeOf(closedType);
        for (long type : new long[] {openType, closedType, ungroupedType}) {
            property(type, "mo_no", "NORMAL");
            property(type, "technician", "RESTRICTED");
        }
        openObject = object(openType, "MO-1");
        closedObject = object(closedType, "MO-2");
        ungroupedObject = object(ungroupedType, "MO-3");
    }

    @Test
    void objectsOfInvisibleTypesAreAbsentLikeMissingOnes() {
        Map<Long, ScopedObject> found =
                reader.findByIds(policy.of(VIEWER), List.of(openObject, ungroupedObject, -1L));

        assertThat(found).containsOnlyKeys(openObject);
    }

    @Test
    void restrictedValuesFollowRoleSettingForView() {
        Map<Long, ScopedObject> found =
                reader.findByIds(policy.of(VIEWER), List.of(openObject, closedObject));

        assertThat(found.get(openObject).text("technician")).isEqualTo("홍길동");
        assertThat(found.get(openObject).maskedPropertyCodes()).isEmpty();
        ScopedObject closed = found.get(closedObject);
        assertThat(closed.properties()).containsEntry("technician", null);
        assertThat(closed.text("mo_no")).isEqualTo("MO-2");
        assertThat(closed.maskedPropertyCodes()).containsExactly("technician");
    }

    @Test
    void modelPurposeHidesRestrictedValuesEvenWhenRoleMayView() {
        ScopedObject open =
                reader.findByIds(policy.forModel(VIEWER), List.of(openObject)).get(openObject);

        assertThat(open.properties()).containsEntry("technician", null);
        assertThat(open.maskedPropertyCodes()).containsExactly("technician");
    }

    /** 대표 속성이 민감 속성이 된 뒤에도 원본 표시 이름이 남아 있을 수 있다. 가려야 하면 다시 만든다(객체 조회와 같은 규칙). */
    @Test
    void displayNameIsRebuiltWhenDisplayPropertyIsHidden() {
        jdbc.sql(
                        """
                        update object_type set display_property_id =
                            (select id from property_definition
                              where object_type_id = object_type.id and code = 'technician')
                        where code = :code
                        """)
                .param("code", openCode)
                .update();

        assertThat(reader.findByIds(policy.of(VIEWER), List.of(openObject)))
                .extractingByKey(openObject)
                .extracting(ScopedObject::displayName)
                .isEqualTo("홍길동");
        assertThat(reader.findByIds(policy.forModel(VIEWER), List.of(openObject)))
                .extractingByKey(openObject)
                .extracting(ScopedObject::displayName)
                .isEqualTo("#" + openObject);
    }

    @Test
    void findingByHiddenRestrictedValueReturnsNothing() {
        DataAccess view = policy.of(VIEWER);

        assertThat(reader.findActiveByProperty(view, closedCode, "mo_no", List.of("MO-2")))
                .extracting(ScopedObject::id)
                .containsExactly(closedObject);
        assertThat(reader.findActiveByProperty(view, closedCode, "technician", List.of("홍길동")))
                .isEmpty();
        assertThat(reader.findActiveByProperty(view, openCode, "technician", List.of("홍길동")))
                .extracting(ScopedObject::id)
                .containsExactly(openObject);
        assertThat(
                        reader.findActiveByProperty(
                                policy.forModel(VIEWER), openCode, "technician", List.of("홍길동")))
                .isEmpty();
    }

    @Test
    void definitionsScopeCannotReadObjectValues() {
        DataAccess definitions = policy.forDefinitions();

        assertThatThrownBy(() -> reader.findByIds(definitions, List.of(openObject)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                reader.findActiveByProperty(
                                        definitions, openCode, "mo_no", List.of("MO-1")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void allow(String group, boolean canViewRestricted) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, :restricted from role where code = 'VIEWER'
                        """)
                .param("group", group)
                .param("restricted", canViewRestricted)
                .update();
    }

    private long objectType(String accessGroup) {
        String code = "scoped_" + UUID.randomUUID().toString().replace("-", "");
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

    private String codeOf(long objectTypeId) {
        return jdbc.sql("select code from object_type where id = :id")
                .param("id", objectTypeId)
                .query(String.class)
                .single();
    }

    private void property(long objectTypeId, String code, String sensitivity) {
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity)
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .update();
    }

    private long object(long objectTypeId, String moNo) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, '홍길동',
                                jsonb_build_object('mo_no', :moNo, 'technician', '홍길동'))
                        returning id
                        """)
                .param("type", objectTypeId)
                .param("moNo", moNo)
                .query(Long.class)
                .single();
    }
}

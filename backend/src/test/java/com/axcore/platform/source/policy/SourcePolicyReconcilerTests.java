package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** 정책을 강화한 뒤 이미 저장된 속성·값을 기동 때 정책에 맞춘다(S15P31C106-430). */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourcePolicyReconcilerTests {

    @Autowired SourcePolicyReconciler reconciler;
    @Autowired JdbcClient jdbc;

    @Test
    void raisesNormalPropertiesThatHoldRestrictedColumnsEvenWhenTheMappingIsOff() {
        // 정책상 민감인 mes.maintenance_order.technician을 일반 속성이 담고 있다(정책 전에 만든 매핑). 매핑은 꺼져 있다.
        Fixture f = fixture("mes.maintenance_order", "technician", false);
        // 대표 속성이었다(매핑 저장 038과 같이 민감 속성은 대표에서 뺀다)
        jdbc.sql("update object_type set display_property_id = :property where id = :type")
                .param("property", f.property())
                .param("type", f.type())
                .update();

        reconciler.reconcile();

        assertThat(
                        jdbc.sql("select display_property_id from object_type where id = :id")
                                .param("id", f.type())
                                .query(Long.class)
                                .optional())
                .isEmpty();

        assertThat(sensitivity(f.property)).isEqualTo("RESTRICTED");
        // 값은 남는다(민감 값을 볼 수 없는 역할과 모델에는 가려진다)
        assertThat(value(f.object)).isEqualTo("\"정비2팀\"");
        // 다시 돌려도 바꿀 것이 없다
        assertThat(reconciler.reconcile())
                .noneMatch(change -> change.contains("속성 " + f.property + "("));
    }

    @Test
    void dropsAnAlreadyRestrictedPropertyThatIsStillTheDisplayProperty() {
        // 이 정리 전에 민감으로 올렸지만 대표 속성으로 남은 경우
        Fixture f = fixture("mes.maintenance_order", "technician", true);
        jdbc.sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", f.property())
                .update();
        jdbc.sql("update object_type set display_property_id = :property where id = :type")
                .param("property", f.property())
                .param("type", f.type())
                .update();
        jdbc.sql("update object_instance set display_name = '정비2팀' where id = :id")
                .param("id", f.object())
                .update();

        reconciler.reconcile();

        assertThat(
                        jdbc.sql("select display_property_id from object_type where id = :id")
                                .param("id", f.type())
                                .query(Long.class)
                                .optional())
                .isEmpty();
        assertThat(
                        jdbc.sql("select display_name from object_instance where id = :id")
                                .param("id", f.object())
                                .query(String.class)
                                .single())
                .isEqualTo("1");
    }

    @Test
    void clearsValuesOfPropertiesThatHoldForbiddenColumnsAndMarksActiveTablesForReload() {
        // 정책상 금지인 mes.worker.name을 켜진 매핑의 일반 속성이 담고 있다
        Fixture f = fixture("mes.worker", "name", true);

        reconciler.reconcile();

        assertThat(sensitivity(f.property)).isEqualTo("RESTRICTED");
        assertThat(value(f.object)).isNull();
        assertThat(
                        jdbc.sql("select loaded_lsn from source_resource where id = :id")
                                .param("id", f.resource)
                                .query(Long.class)
                                .optional())
                .isEmpty();
    }

    @Test
    void forbiddenColumnClearsOnlyItsOwnObjectsAndDropsItAsTheDisplayProperty() {
        Fixture f = fixture("mes.worker", "name", true);
        // 같은 속성을 다른 표의 일반 컬럼에서 채운 객체(지우면 안 된다)
        long otherMapping =
                jdbc.sql(
                                """
                                insert into object_source_mapping (source_resource_id, object_type_id, key_columns, is_active)
                                values (:resource, :type, '["id"]'::jsonb, false) returning id
                                """)
                        .param("resource", f.resource)
                        .param("type", f.type)
                        .query(Long.class)
                        .single();
        long other =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, source_mapping_id, source_key, display_name, properties)
                                values (:type, :mapping, '{"id": "2"}'::jsonb, 'other', jsonb_build_object('name', '일반 값'))
                                returning id
                                """)
                        .param("type", f.type)
                        .param("mapping", otherMapping)
                        .query(Long.class)
                        .single();
        // 금지 컬럼을 담는 속성이 대표 속성이라 표시 이름에 금지 값이 있다
        jdbc.sql("update object_type set display_property_id = :property where id = :type")
                .param("property", f.property)
                .param("type", f.type)
                .update();
        jdbc.sql("update object_instance set display_name = '정비2팀' where id = :id")
                .param("id", f.object)
                .update();

        reconciler.reconcile();

        assertThat(value(f.object)).isNull();
        assertThat(value(other)).isEqualTo("\"일반 값\"");
        // 대표에서 뺐으므로 다른 매핑의 객체 표시 이름도 원천 키로
        assertThat(
                        jdbc.sql("select display_name from object_instance where id = :id")
                                .param("id", other)
                                .query(String.class)
                                .single())
                .isEqualTo("2");
        assertThat(
                        jdbc.sql("select display_name from object_instance where id = :id")
                                .param("id", f.object)
                                .query(String.class)
                                .single())
                .isEqualTo("1");
        assertThat(
                        jdbc.sql("select display_property_id from object_type where id = :id")
                                .param("id", f.type)
                                .query(Long.class)
                                .optional())
                .isEmpty();
    }

    private record Fixture(long resource, long type, long mapping, long property, long object) {}

    private Fixture fixture(String resourceKey, String column, boolean active) {
        long source =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, '원천', 'POSTGRES') returning id
                                """)
                        .param("code", "rc-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long resource =
                jdbc.sql(
                                """
                                insert into source_resource
                                    (data_source_id, resource_key, resource_kind, is_readable, sync_strategy, loaded_lsn)
                                values (:source, :key, 'TABLE', true, 'CDC', 100) returning id
                                """)
                        .param("source", source)
                        .param("key", resourceKey)
                        .query(Long.class)
                        .single();
        long sourceColumn =
                jdbc.sql(
                                """
                                insert into source_column
                                    (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                                values (:resource, :name, 'varchar', true, 1) returning id
                                """)
                        .param("resource", resource)
                        .param("name", column)
                        .query(Long.class)
                        .single();
        long type =
                jdbc.sql("insert into object_type (code, name) values (:code, '유형') returning id")
                        .param("code", "RC_" + UUID.randomUUID().toString().replace("-", ""))
                        .query(Long.class)
                        .single();
        long property =
                jdbc.sql(
                                """
                                insert into property_definition (object_type_id, code, name, data_type)
                                values (:type, :code, :code, 'STRING') returning id
                                """)
                        .param("type", type)
                        .param("code", column)
                        .query(Long.class)
                        .single();
        long mapping =
                jdbc.sql(
                                """
                                insert into object_source_mapping
                                    (source_resource_id, object_type_id, key_columns, is_active)
                                values (:resource, :type, '["id"]'::jsonb, :active) returning id
                                """)
                        .param("resource", resource)
                        .param("type", type)
                        .param("active", active)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", property)
                .param("column", sourceColumn)
                .update();
        long object =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, source_mapping_id, source_key, display_name, properties)
                                values (:type, :mapping, '{"id": "1"}'::jsonb, 'obj', jsonb_build_object(:code, '정비2팀'))
                                returning id
                                """)
                        .param("type", type)
                        .param("mapping", mapping)
                        .param("code", column)
                        .query(Long.class)
                        .single();
        return new Fixture(resource, type, mapping, property, object);
    }

    private String sensitivity(long property) {
        return jdbc.sql("select sensitivity from property_definition where id = :id")
                .param("id", property)
                .query(String.class)
                .single();
    }

    private String value(long object) {
        return jdbc.sql(
                        """
                        select o.properties -> p.code
                        from object_instance o join property_definition p on p.object_type_id = o.object_type_id
                        where o.id = :id
                        """)
                .param("id", object)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}

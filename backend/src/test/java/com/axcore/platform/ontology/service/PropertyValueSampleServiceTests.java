package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
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

/** 문자 속성 값 종류를 실제 DB에서 사용자 조회 범위(205)로 읽는지 본다(359). */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class PropertyValueSampleServiceTests {
    private static final LoginAccount VIEWER = new LoginAccount(1L, "tester", "VIEWER", false);
    private static final LoginAccount ADMIN = new LoginAccount(1L, "tester", "ADMIN", false);
    private static final int LIMIT = 3;

    @Autowired JdbcClient jdbc;
    @Autowired PropertyValueSampleService samples;

    private String tag;
    private long visible;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString();
        visible = type(tag, tag);
        allowViewer(tag);
        property(visible, "status", "STRING", "NORMAL");
        property(visible, "code", "STRING", "NORMAL");
        property(visible, "owner", "STRING", "RESTRICTED");
        property(visible, "qty", "INTEGER", "NORMAL");
    }

    @Test
    void returnsSortedValuesOfActiveObjectsWithoutEmptyOnes() {
        object(visible, "{\"status\":\"정지\",\"code\":\"A\",\"owner\":\"김\",\"qty\":1}");
        object(visible, "{\"status\":\"가동\",\"code\":\"B\",\"owner\":\"이\",\"qty\":2}");
        object(visible, "{\"status\":\"가동\",\"code\":\"C\"}");
        object(visible, "{\"status\":\"\",\"code\":null}");
        deletedAtSource(visible, "{\"status\":\"폐기\",\"code\":\"D\"}");

        Map<String, List<String>> values = samples.stringValues(VIEWER, visible, LIMIT);

        // 빈 값·null·원천 삭제 객체는 빼고, 같은 값은 한 번만 값 순서로 준다.
        assertThat(values).containsEntry("status", List.of("가동", "정지"));
        assertThat(values).containsEntry("code", List.of("A", "B", "C"));
        // 문자 속성이 아니면 넣지 않는다.
        assertThat(values).doesNotContainKey("qty");
    }

    @Test
    void dropsPropertyWithMoreValuesThanLimitAndKeepsPropertyWithoutValues() {
        for (String code : List.of("A", "B", "C", "D")) {
            object(visible, "{\"code\":\"" + code + "\"}");
        }

        Map<String, List<String>> values = samples.stringValues(VIEWER, visible, LIMIT);

        assertThat(values).doesNotContainKey("code");
        assertThat(values).containsEntry("status", List.of());
    }

    @Test
    void showsRestrictedValuesOnlyToActorsWhoMayViewThem() {
        object(visible, "{\"owner\":\"김\"}");

        assertThat(samples.stringValues(VIEWER, visible, LIMIT)).doesNotContainKey("owner");
        assertThat(samples.stringValues(ADMIN, visible, LIMIT))
                .containsEntry("owner", List.of("김"));
        jdbc.sql(
                        "update role_data_scope set can_view_restricted = true where access_group = :group")
                .param("group", tag)
                .update();
        assertThat(samples.stringValues(VIEWER, visible, LIMIT))
                .containsEntry("owner", List.of("김"));
    }

    @Test
    void hiddenOrMissingTypeHasNoValues() {
        long hidden = type(tag + "-hidden", tag + "-private");
        property(hidden, "status", "STRING", "NORMAL");
        object(hidden, "{\"status\":\"비밀\"}");

        assertThat(samples.stringValues(VIEWER, hidden, LIMIT)).isEmpty();
        assertThat(samples.stringValues(VIEWER, Long.MAX_VALUE, LIMIT)).isEmpty();
    }

    /**
     * 시험용 유형. ADMIN에게 그 그룹을 민감 값까지 열어 준다. 384부터 ADMIN도 허용 행으로만 보고 그룹 없는 유형은 아무도 못 보므로, 이 시험의 ADMIN을
     * "전부 보는 역할"로 쓰려고 행을 넣는다. accessGroup이 null이면 ADMIN만 허용한 그룹을 따로 만든다(예전 "그룹 없어 ADMIN만 봄").
     */
    private long type(String code, String accessGroup) {
        String group = accessGroup == null ? "admin-only-" + code : accessGroup;
        long id =
                jdbc.sql(
                                """
                                insert into object_type (code, name, access_group)
                                values (:code, :code, :group) returning id
                                """)
                        .param("code", code)
                        .param("group", group)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, true from role where code = 'ADMIN'
                        on conflict (role_id, access_group) do nothing
                        """)
                .param("group", group)
                .update();
        return id;
    }

    private void allowViewer(String accessGroup) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, false from role where code = 'VIEWER'
                        """)
                .param("group", accessGroup)
                .update();
    }

    private void property(long type, String code, String dataType, String sensitivity) {
        jdbc.sql(
                        """
                        insert into property_definition
                            (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, :dataType, :sensitivity)
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .update();
    }

    private void object(long type, String properties) {
        jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb))
                        """)
                .param("type", type)
                .param("name", UUID.randomUUID().toString())
                .param("properties", properties)
                .update();
    }

    /** 원천에서 삭제된 객체는 원천 매핑이 있어야 만들 수 있다(ck_object_instance_platform). */
    private void deletedAtSource(long type, String properties) {
        long mapping =
                jdbc.sql(
                                """
                        with source as (
                            insert into data_source (code, name, source_kind)
                            values (:code, :code, 'POSTGRES') returning id
                        ), resource as (
                            insert into source_resource (data_source_id, resource_key, resource_kind)
                            select id, 'public.sample', 'TABLE' from source returning id
                        )
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        select id, :type, '["k"]'::jsonb from resource returning id
                        """)
                        .param("code", UUID.randomUUID().toString())
                        .param("type", type)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties, status)
                        values (:type, :mapping, jsonb_build_object('k', 'deleted'), 'deleted',
                                cast(:properties as jsonb), 'DELETED_AT_SOURCE')
                        """)
                .param("type", type)
                .param("mapping", mapping)
                .param("properties", properties)
                .update();
    }
}

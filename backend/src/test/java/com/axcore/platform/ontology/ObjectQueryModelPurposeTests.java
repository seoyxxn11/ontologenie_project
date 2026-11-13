package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 조회 서비스를 사람(VIEW)과 Agent(MODEL)가 쓸 때 용도대로 가리는지 확인한다(S15P31C106-384). VIEWER에게 민감 값까지 열어 준 그룹을
 * 만들어, 사람은 보고 모델은 못 보는 차이만 남긴다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectQueryModelPurposeTests {

    @Autowired ObjectDetailQuery details;

    @Autowired ObjectSearchQuery search;

    @Autowired DataAccessPolicy policy;

    @Autowired JdbcClient jdbc;

    /** 민감 값을 보여 준 응답은 열람 기록(394)을 남기고 그 기록은 실제 계정을 가리켜야 해서, 테스트 사용자를 계정 표에 둔다. */
    private long testerId;

    @BeforeEach
    void createTester() {
        testerId =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                                values (:loginId, 'tester', 'not-used', (select id from role where code = 'VIEWER'), false)
                                returning id
                                """)
                        .param("loginId", "tester-" + java.util.UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    private LoginAccount viewer() {
        return new LoginAccount(testerId, "tester", "VIEWER", false);
    }

    long objectId;
    String technician;

    @BeforeEach
    void setUp() {
        String group = "model-" + UUID.randomUUID();
        technician = "기사-" + UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, true from role where code = 'VIEWER'
                        """)
                .param("group", group)
                .update();
        long type =
                jdbc.sql(
                                """
                                insert into object_type (code, name, access_group)
                                values (:code, :code, :group) returning id
                                """)
                        .param("code", "model_" + UUID.randomUUID().toString().replace("-", ""))
                        .param("group", group)
                        .query(Long.class)
                        .single();
        for (String[] property :
                new String[][] {{"mo_no", "NORMAL"}, {"technician", "RESTRICTED"}}) {
            jdbc.sql(
                            """
                            insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                            values (:type, :code, :code, 'STRING', :sensitivity)
                            """)
                    .param("type", type)
                    .param("code", property[0])
                    .param("sensitivity", property[1])
                    .update();
        }
        objectId =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                values (:type, 'MO-9', jsonb_build_object('mo_no', 'MO-9', 'technician', :name))
                                returning id
                                """)
                        .param("type", type)
                        .param("name", technician)
                        .query(Long.class)
                        .single();
    }

    @Test
    void detailShowsRestrictedValueToPeopleButNotToModel() {
        ObjectDetailResponse view = details.detail(viewer(), DataPurpose.VIEW, objectId);
        ObjectDetailResponse model = details.detail(viewer(), DataPurpose.MODEL, objectId);

        assertThat(view.properties()).containsEntry("technician", technician);
        assertThat(model.properties()).containsEntry("technician", null);
        assertThat(model.restrictedPropertyCodes()).containsExactly("technician");
        assertThat(model.properties()).containsEntry("mo_no", "MO-9");
    }

    @Test
    void modelCannotFindObjectsByRestrictedValue() {
        assertThat(search.findByPropertyValue(viewer(), DataPurpose.VIEW, technician, 10))
                .extracting(found -> found.id())
                .containsExactly(objectId);
        assertThat(search.findByPropertyValue(viewer(), DataPurpose.MODEL, technician, 10))
                .isEmpty();
    }

    @Test
    void definitionsScopeIsNotForObjectQueries() {
        assertThatThrownBy(() -> policy.of(viewer(), DataPurpose.DEFINITIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

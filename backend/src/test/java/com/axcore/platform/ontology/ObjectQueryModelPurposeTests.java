package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 조회 서비스를 사람(VIEW)과 Agent(MODEL)가 쓸 때 용도대로 가리는지 확인한다(S15P31C106-384). VIEWER에게 민감 값까지 열어 준 그룹을
 * 만들어, 사람은 보고 모델은 못 보는 차이만 남긴다. 상세·값 검색에 더해 검색 조건·건수·주변 탐색·연결 근거도 본다(405).
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectQueryModelPurposeTests {

    @Autowired ObjectDetailQuery details;

    @Autowired ObjectSearchQuery search;

    @Autowired ObjectEvidenceQuery evidence;

    @Autowired PropertyValueSampleService values;

    @Autowired DataAccessPolicy policy;

    @Autowired JdbcClient jdbc;

    /** 민감 값을 보여 준 응답은 열람 기록(394)을 남기고 그 기록은 실제 계정을 가리켜야 해서, 테스트 사용자를 계정 표에 둔다. */
    private long testerId;

    private void createTester() {
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

    long typeId;
    long objectId;
    String technician;

    /** 정비 지시가 가리키는 설비. 민감 속성 owner를 가진다(주변 탐색 조건·연결 근거 시험용, 405). */
    long equipmentTypeId;

    long equipmentId;
    String owner;
    long linkTypeId;
    long linkId;

    @BeforeEach
    void setUp() {
        createTester();
        String group = "model-" + UUID.randomUUID();
        technician = "기사-" + UUID.randomUUID();
        owner = "담당-" + UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, true from role where code = 'VIEWER'
                        """)
                .param("group", group)
                .update();
        typeId = type(group);
        // technician은 식별 속성이기도 하다. 검색어(q)가 민감 식별 값으로 객체를 찾는지 볼 수 있다.
        property(typeId, "mo_no", "NORMAL", false);
        property(typeId, "technician", "RESTRICTED", true);
        objectId =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                values (:type, 'MO-9', jsonb_build_object('mo_no', 'MO-9', 'technician', :name))
                                returning id
                                """)
                        .param("type", typeId)
                        .param("name", technician)
                        .query(Long.class)
                        .single();

        equipmentTypeId = type(group);
        property(equipmentTypeId, "code", "NORMAL", false);
        property(equipmentTypeId, "owner", "RESTRICTED", false);
        equipmentId =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                values (:type, 'P-101', jsonb_build_object('code', 'P-101', 'owner', :owner))
                                returning id
                                """)
                        .param("type", equipmentTypeId)
                        .param("owner", owner)
                        .query(Long.class)
                        .single();
        linkTypeId =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, :code, :source, :target, 'MANY_TO_MANY', 'Test') returning id
                                """)
                        .param(
                                "code",
                                "model_link_" + UUID.randomUUID().toString().replace("-", ""))
                        .param("source", typeId)
                        .param("target", equipmentTypeId)
                        .query(Long.class)
                        .single();
        // 문서 근거의 문장에는 민감 값(담당자 이름)이 들어 있다.
        linkId =
                jdbc.sql(
                                """
                                insert into object_link
                                    (link_type_id, source_object_id, target_object_id, origin, confirmed_by, evidence)
                                values (:linkType, :source, :target, 'DOCUMENT', :confirmer,
                                        jsonb_build_object('expression_kind', 'ESTIMATED',
                                                           'quote', :quote, 'event', '정비'))
                                returning id
                                """)
                        .param("linkType", linkTypeId)
                        .param("source", objectId)
                        .param("target", equipmentId)
                        .param("confirmer", testerId)
                        .param("quote", technician + " 기사가 P-101을 정비함")
                        .query(Long.class)
                        .single();
    }

    private long type(String group) {
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", "model_" + UUID.randomUUID().toString().replace("-", ""))
                .param("group", group)
                .query(Long.class)
                .single();
    }

    private void property(long type, String code, String sensitivity, boolean identifier) {
        jdbc.sql(
                        """
                        insert into property_definition
                            (object_type_id, code, name, data_type, sensitivity, is_identifier)
                        values (:type, :code, :code, 'STRING', :sensitivity, :identifier)
                        """)
                .param("type", type)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .param("identifier", identifier)
                .update();
    }

    private ObjectSearchRequest filter(long type, Map<String, String> values) {
        return new ObjectSearchRequest(null, type, null, 0, 10, values, null, null, null);
    }

    private static void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    /** 사람은 민감 속성으로 거를 수 있고, 모델은 같은 조건이 FORBIDDEN이다. 일반 속성 조건은 모델도 쓰되 민감 값은 가려진다. */
    @Test
    void modelCannotFilterSearchByRestrictedProperty() {
        assertThat(
                        search.search(
                                viewer(),
                                DataPurpose.VIEW,
                                filter(typeId, Map.of("technician", technician))))
                .extracting(ObjectSearchResponse::id)
                .containsExactly(objectId);
        assertForbidden(
                () ->
                        search.search(
                                viewer(),
                                DataPurpose.MODEL,
                                filter(typeId, Map.of("technician", technician))));

        var found =
                search.search(viewer(), DataPurpose.MODEL, filter(typeId, Map.of("mo_no", "MO-9")));
        assertThat(found.getContent())
                .singleElement()
                .satisfies(
                        object -> {
                            assertThat(object.properties()).containsEntry("technician", null);
                            assertThat(object.restrictedPropertyCodes())
                                    .containsExactly("technician");
                        });
    }

    /** 건수로도 민감 값을 알아낼 수 없다. 조건 건수는 FORBIDDEN이고, 민감 식별 값 검색어는 모델에게 0건이다. */
    @Test
    void modelCannotCountByRestrictedValue() {
        assertThat(
                        search.searchIds(
                                        viewer(),
                                        DataPurpose.VIEW,
                                        filter(typeId, Map.of("technician", technician)),
                                        10)
                                .total())
                .isEqualTo(1);
        assertForbidden(
                () ->
                        search.searchIds(
                                viewer(),
                                DataPurpose.MODEL,
                                filter(typeId, Map.of("technician", technician)),
                                10));

        var byName = new ObjectSearchRequest(technician, typeId, null, 0, 10);
        assertThat(search.search(viewer(), DataPurpose.VIEW, byName).getTotalElements())
                .isEqualTo(1);
        assertThat(search.search(viewer(), DataPurpose.MODEL, byName).getTotalElements()).isZero();
    }

    /** 주변 탐색(379)의 상대 객체 조건도 같다. 조건 없이 건너면 모델도 상대 객체를 받는다. */
    @Test
    void modelCannotFilterSearchAroundByRestrictedProperty() {
        var byOwner =
                new ObjectSearchAroundRequest(
                        List.of(objectId),
                        linkTypeId,
                        null,
                        Map.of("owner", owner),
                        null,
                        null,
                        null);
        assertThat(details.searchAround(viewer(), DataPurpose.VIEW, byOwner).objectIds())
                .containsExactly(equipmentId);
        assertForbidden(() -> details.searchAround(viewer(), DataPurpose.MODEL, byOwner));

        var plain = new ObjectSearchAroundRequest(List.of(objectId), linkTypeId, null);
        assertThat(details.searchAround(viewer(), DataPurpose.MODEL, plain).objectIds())
                .containsExactly(equipmentId);
    }

    /** 연결 근거의 문장은 민감 값을 담을 수 있어 모델에게는 가린다. 관계 목록에 딸린 근거도 같다. */
    @Test
    void modelGetsLinkEvidenceRestricted() {
        ObjectLinkEvidenceResponse view =
                evidence.linkEvidence(viewer(), DataPurpose.VIEW, objectId, linkId);
        assertThat(view.evidenceRestricted()).isFalse();
        assertThat(view.quote()).contains(technician);

        ObjectLinkEvidenceResponse model =
                evidence.linkEvidence(viewer(), DataPurpose.MODEL, objectId, linkId);
        assertThat(model.evidenceRestricted()).isTrue();
        assertThat(model.quote()).isNull();
        assertThat(model.event()).isNull();

        var links =
                details.links(
                        viewer(),
                        DataPurpose.MODEL,
                        objectId,
                        new ObjectLinkSearchRequest(null, null, 0, 10));
        assertThat(links.getContent())
                .singleElement()
                .satisfies(
                        link -> {
                            assertThat(link.evidence().evidenceRestricted()).isTrue();
                            assertThat(link.evidence().quote()).isNull();
                        });
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

    /**
     * Agent가 MODEL로 부르는 나머지 경로: ID로 읽기(Object Set)와 조건 값 종류. 값 종류는 목록 그대로 모델에 가므로 민감 속성을 넣지 않는다.
     */
    @Test
    void modelGetsRestrictedValuesHiddenInIdReadsAndValueSamples() {
        assertThat(search.findByIds(viewer(), DataPurpose.VIEW, List.of(objectId)))
                .singleElement()
                .satisfies(
                        object ->
                                assertThat(object.properties())
                                        .containsEntry("technician", technician));
        assertThat(search.findByIds(viewer(), DataPurpose.MODEL, List.of(objectId)))
                .singleElement()
                .satisfies(
                        object -> {
                            assertThat(object.properties()).containsEntry("technician", null);
                            assertThat(object.restrictedPropertyCodes())
                                    .containsExactly("technician");
                        });

        assertThat(values.stringValues(viewer(), DataPurpose.VIEW, typeId, 10))
                .containsEntry("technician", List.of(technician))
                .containsEntry("mo_no", List.of("MO-9"));
        assertThat(values.stringValues(viewer(), DataPurpose.MODEL, typeId, 10))
                .doesNotContainKey("technician")
                .containsEntry("mo_no", List.of("MO-9"));
    }
}

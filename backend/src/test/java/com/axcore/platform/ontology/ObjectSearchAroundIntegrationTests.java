package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchAroundRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundResult;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Search Around(379): 여러 기준 객체에서 한 홉을 건너 상대 객체를 076·078·205 기준으로 모은다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class ObjectSearchAroundIntegrationTests {

    private static final LoginAccount ADMIN = new LoginAccount(1L, "tester", "ADMIN", false);
    private static final LoginAccount PLANNER = new LoginAccount(1L, "tester", "PLANNER", false);
    private static final LoginAccount VIEWER = new LoginAccount(1L, "tester", "VIEWER", false);

    @Autowired ObjectDetailQuery query;
    @Autowired JdbcClient jdbc;

    @Test
    void collectsActiveRelatedObjectsOnceAcrossTwoHops() {
        Model model = model();
        long p1 = object(model.equipment, "P1", "{}");
        long p2 = object(model.equipment, "P2", "{}");
        long p3 = object(model.equipment, "P3", "{}");
        long o1 = object(model.operation, "O1", "{}");
        long o2 = object(model.operation, "O2", "{}");
        long o3 = object(model.operation, "O3", "{}");
        long o4 = object(model.operation, "O4", "{}");
        long o5 = object(model.operation, "O5", "{}");
        long deletedOperation = object(model.operation, "deleted", "{}", "DELETED_AT_SOURCE");
        for (long op : new long[] {o1, o2, o3, deletedOperation}) {
            link(model.operationEquipment, op, p1, "ACTIVE");
        }
        link(model.operationEquipment, o4, p2, "ACTIVE");
        link(model.operationEquipment, o5, p3, "ACTIVE");
        long w1 = object(model.workOrder, "W1", "{}");
        long w2 = object(model.workOrder, "W2", "{}");
        long w3 = object(model.workOrder, "W3", "{}");
        long w4 = object(model.workOrder, "W4", "{}");
        long w5 = object(model.workOrder, "W5", "{}");
        long deletedOrder = object(model.workOrder, "deleted", "{}", "DELETED_AT_SOURCE");
        link(model.operationOrder, o1, w1, "ACTIVE");
        link(model.operationOrder, o2, w1, "ACTIVE");
        link(model.operationOrder, o1, deletedOrder, "ACTIVE");
        link(model.operationOrder, o3, w2, "ACTIVE");
        link(model.operationOrder, o3, w5, "INACTIVE");
        link(model.operationOrder, o4, w3, "ACTIVE");
        link(model.operationOrder, o5, w4, "ACTIVE");

        ObjectSearchAroundResult operations =
                query.searchAround(
                        ADMIN,
                        new ObjectSearchAroundRequest(
                                List.of(p2, p1, p1), model.operationEquipment, Direction.IN));
        assertThat(operations.direction()).isEqualTo(Direction.IN);
        assertThat(operations.objectTypeId()).isEqualTo(model.operation);
        assertThat(operations.objectIds()).containsExactly(o1, o2, o3, o4);
        assertThat(operations.count()).isEqualTo(4);

        ObjectSearchAroundResult orders =
                query.searchAround(
                        ADMIN,
                        new ObjectSearchAroundRequest(
                                operations.objectIds(), model.operationOrder, Direction.OUT));
        assertThat(orders.direction()).isEqualTo(Direction.OUT);
        assertThat(orders.objectTypeId()).isEqualTo(model.workOrder);
        assertThat(orders.objectIds()).as("중복·비활성 관계·삭제 객체 제외").containsExactly(w1, w2, w3);
        assertThat(orders.count()).isEqualTo(3);

        assertThat(
                        query.searchAround(
                                        ADMIN,
                                        new ObjectSearchAroundRequest(
                                                List.of(w2), model.operationOrder, Direction.IN))
                                .objectIds())
                .containsExactly(o3);
    }

    @Test
    void bothDirectionsWorkOnlyForLinkTypesWithTheSameEndType() {
        Model model = model();
        long a = object(model.equipment, "A", "{}");
        long b = object(model.equipment, "B", "{}");
        long c = object(model.equipment, "C", "{}");
        long peer = linkType(model.tag + "-peer", model.equipment, model.equipment);
        link(peer, a, b, "ACTIVE");
        link(peer, c, a, "ACTIVE");

        ObjectSearchAroundResult both =
                query.searchAround(ADMIN, new ObjectSearchAroundRequest(List.of(a), peer, null));
        assertThat(both.direction()).isEqualTo(Direction.BOTH);
        assertThat(both.objectIds()).containsExactly(b, c);
        assertThat(
                        query.searchAround(
                                        ADMIN,
                                        new ObjectSearchAroundRequest(
                                                List.of(a, b), peer, Direction.BOTH))
                                .objectIds())
                .as("기준 객체끼리 이어져 있으면 서로를 결과에 넣는다")
                .containsExactly(a, b, c);

        long p1 = object(model.equipment, "P1", "{}");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(p1), model.operationEquipment, Direction.BOTH)),
                "direction");
    }

    @Test
    void infersTheDirectionFromTheBaseObjectTypeWhenItIsOmitted() {
        Model model = model();
        long p1 = object(model.equipment, "P1", "{}");
        long o1 = object(model.operation, "O1", "{}");
        long o2 = object(model.operation, "O2", "{}");
        long w1 = object(model.workOrder, "W1", "{}");
        link(model.operationEquipment, o1, p1, "ACTIVE");
        link(model.operationEquipment, o2, p1, "ACTIVE");

        ObjectSearchAroundResult fromEquipment =
                query.searchAround(
                        ADMIN,
                        new ObjectSearchAroundRequest(List.of(p1), model.operationEquipment, null));
        assertThat(fromEquipment.direction()).as("설비는 도착 쪽이라 IN").isEqualTo(Direction.IN);
        assertThat(fromEquipment.objectTypeId()).isEqualTo(model.operation);
        assertThat(fromEquipment.objectIds()).containsExactly(o1, o2);

        ObjectSearchAroundResult fromOperation =
                query.searchAround(
                        ADMIN,
                        new ObjectSearchAroundRequest(List.of(o1), model.operationEquipment, null));
        assertThat(fromOperation.direction()).as("작업지시 공정은 출발 쪽이라 OUT").isEqualTo(Direction.OUT);
        assertThat(fromOperation.objectIds()).containsExactly(p1);

        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(p1, o1), model.operationEquipment, null)),
                "objectIds",
                "섞여",
                "#" + p1,
                "#" + o1);
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(w1), model.operationEquipment, null)),
                "objectIds",
                "#" + w1,
                "어느 끝과도",
                "출발 유형 #" + model.operation,
                "도착 유형 #" + model.equipment);

        // 유형이 섞였으면 객체 순서나 방향 지정과 관계없이 섞였다고 알린다(379 3차 리뷰).
        long p2 = object(model.equipment, "P2", "{}");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(w1, p1, p2), model.operationEquipment, null)),
                "objectIds",
                "섞여",
                "유형 #" + model.workOrder + ": 객체 #" + w1,
                "유형 #" + model.equipment + ": 객체 #" + p1);
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(o1, p1), model.operationEquipment, Direction.OUT)),
                "objectIds",
                "섞여");
    }

    @Test
    void filtersRelatedObjectsWithTheSameRulesAsObjectSearch() {
        Model model = model();
        property(model.workOrder, "state", "STRING", "NORMAL");
        property(model.workOrder, "planned_start", "DATE", "NORMAL");
        property(model.operation, "operation_only", "STRING", "NORMAL");
        long op = object(model.operation, "O1", "{}");
        long running =
                object(
                        model.workOrder,
                        "running",
                        "{\"state\":\"진행\",\"planned_start\":\"2026-10-10\"}");
        long lateRunning =
                object(
                        model.workOrder,
                        "late",
                        "{\"state\":\"진행\",\"planned_start\":\"2026-11-10\"}");
        long done =
                object(
                        model.workOrder,
                        "done",
                        "{\"state\":\"완료\",\"planned_start\":\"2026-10-10\"}");
        long repairing = object(model.workOrder, "repairing", "{\"state\":\"정비중\"}");
        long unrelated = object(model.workOrder, "unrelated", "{\"state\":\"진행\"}");
        for (long order : new long[] {running, lateRunning, done, repairing}) {
            link(model.operationOrder, op, order, "ACTIVE");
        }

        assertThat(around(ADMIN, model, op, Map.of("state", "진행"), null, null, null))
                .containsExactly(running, lateRunning)
                .doesNotContain(unrelated);
        assertThat(
                        around(
                                ADMIN,
                                model,
                                op,
                                Map.of("state", "진행"),
                                "planned_start",
                                "2026-10-01",
                                "2026-10-31"))
                .containsExactly(running);
        assertThat(around(ADMIN, model, op, Map.of("state", "정비 중"), null, null, null))
                .as("공백만 다른 저장 값 하나를 찾는다(378)")
                .containsExactly(repairing);
        assertThat(around(ADMIN, model, op, Map.of("state", "취소"), null, null, null)).isEmpty();

        expectInvalid(
                () -> around(ADMIN, model, op, Map.of("operation_only", "x"), null, null, null),
                "propertyValues[operation_only]");
        expectInvalid(
                () -> around(ADMIN, model, op, Map.of(), "state", "2026-10-01", null),
                "dateProperty");
        expectInvalid(
                () -> around(ADMIN, model, op, Map.of(), "planned_start", null, null), "from");
        expectInvalid(
                () ->
                        around(
                                ADMIN,
                                model,
                                op,
                                Map.of(),
                                "planned_start",
                                "2026-10-31",
                                "2026-10-01"),
                "to");
    }

    @Test
    void appliesTheObjectSearchScopeToBaseObjectsLinkTypesAndRestrictedFilters() {
        Model model = model();
        allowViewer(model.tag);
        property(model.workOrder, "serial", "STRING", "RESTRICTED");
        long op = object(model.operation, "O1", "{}");
        long order = object(model.workOrder, "W1", "{\"serial\":\"S-1\"}");
        link(model.operationOrder, op, order, "ACTIVE");
        long hiddenType = type(model.tag + "-hidden", null);
        long hiddenObject = object(hiddenType, "hidden", "{}");
        long toHidden = linkType(model.tag + "-to-hidden", model.operation, hiddenType);
        link(toHidden, op, hiddenObject, "ACTIVE");
        long fromHidden = linkType(model.tag + "-from-hidden", hiddenType, model.workOrder);
        link(fromHidden, hiddenObject, order, "ACTIVE");
        long deleted = object(model.operation, "deleted", "{}", "DELETED_AT_SOURCE");

        assertThat(around(VIEWER, model, op, Map.of(), null, null, null)).containsExactly(order);
        expectError(
                () -> around(VIEWER, model, op, Map.of("serial", "S-1"), null, null, null),
                CommonErrorCode.FORBIDDEN);
        assertThat(around(PLANNER, model, op, Map.of("serial", "S-1"), null, null, null))
                .containsExactly(order);

        for (long linkType : new long[] {toHidden, Long.MAX_VALUE}) {
            expectError(
                    () ->
                            query.searchAround(
                                    VIEWER,
                                    new ObjectSearchAroundRequest(
                                            List.of(op), linkType, Direction.OUT)),
                    CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        expectError(
                () ->
                        query.searchAround(
                                VIEWER,
                                new ObjectSearchAroundRequest(
                                        List.of(order), fromHidden, Direction.IN)),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(
                        query.searchAround(
                                        PLANNER,
                                        new ObjectSearchAroundRequest(
                                                List.of(op), toHidden, Direction.OUT))
                                .objectIds())
                .containsExactly(hiddenObject);

        for (long base : new long[] {deleted, Long.MAX_VALUE}) {
            expectError(
                    () ->
                            query.searchAround(
                                    PLANNER,
                                    new ObjectSearchAroundRequest(
                                            List.of(op, base),
                                            model.operationOrder,
                                            Direction.OUT)),
                    CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        expectError(
                () ->
                        query.searchAround(
                                VIEWER,
                                new ObjectSearchAroundRequest(
                                        List.of(hiddenObject),
                                        model.operationOrder,
                                        Direction.OUT)),
                CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void rejectsMissingOrInvalidInputAndBaseObjectsOfTheOtherEnd() {
        Model model = model();
        long op = object(model.operation, "O1", "{}");
        long order = object(model.workOrder, "W1", "{}");
        link(model.operationOrder, op, order, "ACTIVE");

        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        null, model.operationOrder, Direction.OUT)),
                "objectIds");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        Arrays.asList(op, null),
                                        model.operationOrder,
                                        Direction.OUT)),
                "objectIds");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(0L), model.operationOrder, Direction.OUT)),
                "objectIds");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        LongStream.rangeClosed(1, 5001).boxed().toList(),
                                        model.operationOrder,
                                        Direction.OUT)),
                "objectIds");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN, new ObjectSearchAroundRequest(List.of(op), null, null)),
                "linkTypeId");
        expectInvalid(
                () ->
                        query.searchAround(
                                ADMIN,
                                new ObjectSearchAroundRequest(
                                        List.of(order), model.operationOrder, Direction.OUT)),
                "objectIds",
                "#" + order,
                "출발 유형과 다릅니다",
                "방향을 생략");
    }

    @Test
    void acceptsUpToTheBaseObjectLimitCountingDistinctIdsOnly() {
        Model model = model();
        List<Long> operations =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                select :type, 'O' || n, '{}'::jsonb from generate_series(1, 5000) n
                                returning id
                                """)
                        .param("type", model.operation)
                        .query(Long.class)
                        .list();
        long order = object(model.workOrder, "W1", "{}");
        link(model.operationOrder, operations.getLast(), order, "ACTIVE");
        List<Long> withDuplicate = new ArrayList<>(operations);
        withDuplicate.add(operations.getFirst());

        ObjectSearchAroundResult result =
                query.searchAround(
                        ADMIN,
                        new ObjectSearchAroundRequest(withDuplicate, model.operationOrder, null));
        assertThat(result.direction()).isEqualTo(Direction.OUT);
        assertThat(result.objectIds()).containsExactly(order);
    }

    @Test
    void rejectsMoreRelatedObjectsThanTheLimitUntilAFilterNarrowsThem() {
        Model model = model();
        property(model.workOrder, "batch", "STRING", "NORMAL");
        long op = object(model.operation, "O1", "{}");
        List<Long> orders =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                select :type, 'W' || n,
                                       jsonb_build_object('batch', case when n = 1 then 'other' else 'main' end)
                                from generate_series(1, 5001) n
                                returning id
                                """)
                        .param("type", model.workOrder)
                        .query(Long.class)
                        .list();
        long mapping = mapping(model.operationOrder);
        jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, link_mapping_id, status)
                        select :type, :source, target, 'RULE', :mapping, 'ACTIVE'
                        from unnest(cast(:targets as bigint[])) target
                        """)
                .param("type", model.operationOrder)
                .param("source", op)
                .param("mapping", mapping)
                .param("targets", orders.toArray(Long[]::new))
                .update();

        assertThatThrownBy(() -> around(ADMIN, model, op, Map.of(), null, null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getErrorCode())
                                    .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
                            assertThat(error.getMessage()).contains("5000", "조건");
                            assertThat(error.getDetails()).containsEntry("limit", 5000);
                        });
        List<Long> narrowed = around(ADMIN, model, op, Map.of("batch", "main"), null, null, null);
        assertThat(narrowed).hasSize(5000).doesNotContain(orders.getFirst());
    }

    private List<Long> around(
            LoginAccount actor,
            Model model,
            long base,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to) {
        return query.searchAround(
                        actor,
                        new ObjectSearchAroundRequest(
                                List.of(base),
                                model.operationOrder,
                                Direction.OUT,
                                propertyValues,
                                dateProperty,
                                from,
                                to))
                .objectIds();
    }

    private static void expectInvalid(ThrowingCallable call, String field, String... reasonParts) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getErrorCode())
                                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                            assertThat(error.getDetails()).containsEntry("field", field);
                            if (reasonParts.length > 0) {
                                assertThat((String) error.getDetails().get("reason"))
                                        .contains(reasonParts);
                            }
                        });
    }

    private static void expectError(ThrowingCallable call, CommonErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    /** 설비 ← 작업지시 공정 → 작업지시. 세 유형 모두 tag 접근 그룹이다. */
    private Model model() {
        String tag = UUID.randomUUID().toString();
        long equipment = type(tag + "-equipment", tag);
        long operation = type(tag + "-operation", tag);
        long workOrder = type(tag + "-order", tag);
        return new Model(
                tag,
                equipment,
                operation,
                workOrder,
                linkType(tag + "-op-equipment", operation, equipment),
                linkType(tag + "-op-order", operation, workOrder));
    }

    private record Model(
            String tag,
            long equipment,
            long operation,
            long workOrder,
            long operationEquipment,
            long operationOrder) {}

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
                        select id, :group, code = 'PLANNER' from role where code in ('ADMIN', 'PLANNER')
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

    private long property(long type, String code, String dataType, String sensitivity) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, :dataType, :sensitivity) returning id
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .query(Long.class)
                .single();
    }

    private long object(long type, String name, String properties) {
        return object(type, name, properties, "ACTIVE");
    }

    /** 원천에서 삭제된 객체는 원천 매핑이 있어야 해서(ck_object_instance_platform) 그때만 매핑을 만든다. */
    private long object(long type, String name, String properties, String status) {
        Long mapping = "ACTIVE".equals(status) ? null : sourceMapping(type);
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties, status)
                        values (:type, :mapping,
                                case when cast(:mapping as bigint) is not null
                                     then jsonb_build_object('key', cast(:name as text)) end,
                                :name, cast(:properties as jsonb), :status)
                        returning id
                        """)
                .param("type", type)
                .param("mapping", mapping, Types.BIGINT)
                .param("name", name)
                .param("properties", properties)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long sourceMapping(long type) {
        String code = UUID.randomUUID().toString();
        return jdbc.sql(
                        """
                        with source as (
                            insert into data_source (code, name, source_kind)
                            values (:code, :code, 'POSTGRES') returning id
                        ), resource as (
                            insert into source_resource (data_source_id, resource_key, resource_kind)
                            select id, 'public.item', 'TABLE' from source returning id
                        )
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        select id, :type, '["key"]'::jsonb from resource returning id
                        """)
                .param("code", code)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private long linkType(String code, long source, long target) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, :code, :source, :target, 'MANY_TO_MANY', 'Test') returning id
                        """)
                .param("code", code)
                .param("source", source)
                .param("target", target)
                .query(Long.class)
                .single();
    }

    /** 관계 유형마다 반영 중 규칙은 하나라(V17) 있으면 그 규칙을 쓴다. */
    private long mapping(long linkType) {
        return jdbc.sql(
                        """
                        with existing as (
                            select id from link_mapping where link_type_id = :type and status = 'ACTIVE'
                        ), created as (
                            insert into link_mapping (link_type_id, code, revision)
                            select :type, :code, 1 where not exists (select 1 from existing)
                            returning id
                        )
                        select id from created union all select id from existing
                        """)
                .param("type", linkType)
                .param("code", UUID.randomUUID().toString())
                .query(Long.class)
                .single();
    }

    private void link(long linkType, long source, long target, String status) {
        jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin,
                             link_mapping_id, status, inactivated_at)
                        values (:type, :source, :target, 'RULE', :mapping, :status,
                                case when :status = 'INACTIVE' then now() end)
                        """)
                .param("type", linkType)
                .param("source", source)
                .param("target", target)
                .param("mapping", mapping(linkType))
                .param("status", status)
                .update();
    }
}

package com.axcore.platform.action.repository;

import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 재배정 계획 조회 (API 093~095). 계획을 고르고 항목은 한 번에 붙인다. */
@Repository
@RequiredArgsConstructor
public class ReassignmentPlanQueryRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** 최근에 만든 계획부터. 조건이 null이면 거르지 않는다. */
    public Page<ReassignmentPlanResponse> search(
            Long fromEquipmentId, Long createdBy, Pageable pageable) {
        String where = " where true";
        Map<String, Object> parameters = new HashMap<>();
        if (fromEquipmentId != null) {
            where += " and from_equipment_id = :fromEquipmentId";
            parameters.put("fromEquipmentId", fromEquipmentId);
        }
        if (createdBy != null) {
            where += " and created_by = :createdBy";
            parameters.put("createdBy", createdBy);
        }
        long total =
                jdbc.sql("select count(*) from reassignment_plan" + where)
                        .params(parameters)
                        .query(Long.class)
                        .single();
        parameters.put("limit", pageable.getPageSize());
        parameters.put("offset", pageable.getOffset());
        List<ReassignmentPlanResponse> plans =
                jdbc.sql(
                                "select * from reassignment_plan"
                                        + where
                                        + " order by created_at desc, id desc"
                                        + " limit :limit offset :offset")
                        .params(parameters)
                        .query((rs, row) -> plan(rs))
                        .list();
        return new PageImpl<>(withItems(plans), pageable, total);
    }

    public Optional<ReassignmentPlanResponse> findById(long id) {
        return withItems(
                        jdbc.sql("select * from reassignment_plan where id = :id")
                                .param("id", id)
                                .query((rs, row) -> plan(rs))
                                .list())
                .stream()
                .findFirst();
    }

    private List<ReassignmentPlanResponse> withItems(List<ReassignmentPlanResponse> plans) {
        if (plans.isEmpty()) {
            return plans;
        }
        Map<Long, List<ReassignmentPlanItemResponse>> items =
                itemsOf(plans.stream().map(ReassignmentPlanResponse::id).toList());
        return plans.stream()
                .map(plan -> plan.withItems(items.getOrDefault(plan.id(), List.of())))
                .toList();
    }

    /** 계획별 항목. 항목은 저장한 순서(id)대로다. */
    private Map<Long, List<ReassignmentPlanItemResponse>> itemsOf(Collection<Long> planIds) {
        Map<Long, List<ReassignmentPlanItemResponse>> items = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select id, plan_id, work_order_id, operation_code, from_equipment_id,
                               to_equipment_id, source_snapshot::text as source_snapshot
                        from reassignment_plan_item where plan_id in (:planIds) order by id
                        """)
                .param("planIds", planIds)
                .query(
                        rs -> {
                            items.computeIfAbsent(
                                            rs.getLong("plan_id"), planId -> new ArrayList<>())
                                    .add(item(rs));
                        });
        return items;
    }

    private ReassignmentPlanResponse plan(ResultSet rs) throws SQLException {
        long conversationId = rs.getLong("agent_conversation_id");
        Long agentConversationId = rs.wasNull() ? null : conversationId;
        return new ReassignmentPlanResponse(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getLong("from_equipment_id"),
                object(rs.getString("selection_basis")),
                rs.getTimestamp("data_as_of").toInstant(),
                rs.getLong("action_execution_id"),
                agentConversationId,
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                List.of());
    }

    private ReassignmentPlanItemResponse item(ResultSet rs) throws SQLException {
        return new ReassignmentPlanItemResponse(
                rs.getLong("id"),
                rs.getLong("work_order_id"),
                rs.getString("operation_code"),
                rs.getLong("from_equipment_id"),
                rs.getLong("to_equipment_id"),
                object(rs.getString("source_snapshot")));
    }

    private Map<String, Object> object(String value) {
        return value == null ? Map.of() : json.readValue(value, JSON_OBJECT);
    }
}

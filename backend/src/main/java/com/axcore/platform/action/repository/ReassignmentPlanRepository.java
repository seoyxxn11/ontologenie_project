package com.axcore.platform.action.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재배정 계획 저장(reassignment_plan·reassignment_plan_item). 실행 기록은 한 번 남기면 고치지 않으므로, 결과에 넣을 계획 ID를 시퀀스에서
 * 먼저 받은 뒤 실행 기록을 남기고 그 ID로 행을 넣는다.
 */
@Repository
@RequiredArgsConstructor
public class ReassignmentPlanRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public long nextPlanId() {
        return jdbc.sql("select nextval(pg_get_serial_sequence('reassignment_plan', 'id'))")
                .query(Long.class)
                .single();
    }

    public List<Long> nextItemIds(int count) {
        return jdbc.sql(
                        """
                        select nextval(pg_get_serial_sequence('reassignment_plan_item', 'id'))
                        from generate_series(1, :count)
                        """)
                .param("count", count)
                .query(Long.class)
                .list();
    }

    /** Agent 질문 메시지가 속한 대화. 계획을 대화에 연결한다. */
    public Optional<Long> conversationOf(long agentMessageId) {
        return jdbc.sql("select conversation_id from agent_message where id = :id")
                .param("id", agentMessageId)
                .query(Long.class)
                .optional();
    }

    public void insertPlan(
            long id,
            long fromEquipmentId,
            Map<String, Object> selectionBasis,
            Instant dataAsOf,
            long actionExecutionId,
            Long agentConversationId,
            long createdBy) {
        jdbc.sql(
                        """
                        insert into reassignment_plan (id, from_equipment_id, selection_basis, data_as_of,
                                                       action_execution_id, agent_conversation_id, created_by)
                        values (:id, :from, cast(:basis as jsonb), :dataAsOf, :executionId,
                                :conversationId, :createdBy)
                        """)
                .param("id", id)
                .param("from", fromEquipmentId)
                .param("basis", json.writeValueAsString(selectionBasis))
                .param("dataAsOf", Timestamp.from(dataAsOf))
                .param("executionId", actionExecutionId)
                .param("conversationId", agentConversationId, Types.BIGINT)
                .param("createdBy", createdBy)
                .update();
    }

    public void insertItem(
            long id,
            long planId,
            long workOrderId,
            String operationCode,
            long fromEquipmentId,
            long toEquipmentId,
            Map<String, Object> sourceSnapshot) {
        jdbc.sql(
                        """
                        insert into reassignment_plan_item (id, plan_id, work_order_id, operation_code,
                                                            from_equipment_id, to_equipment_id, source_snapshot)
                        values (:id, :planId, :workOrderId, :operationCode, :from, :to,
                                cast(:snapshot as jsonb))
                        """)
                .param("id", id)
                .param("planId", planId)
                .param("workOrderId", workOrderId)
                .param("operationCode", operationCode, Types.VARCHAR)
                .param("from", fromEquipmentId)
                .param("to", toEquipmentId)
                .param("snapshot", json.writeValueAsString(sourceSnapshot))
                .update();
    }
}

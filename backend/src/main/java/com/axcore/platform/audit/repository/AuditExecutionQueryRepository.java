package com.axcore.platform.audit.repository;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntityChange;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행·감사 이력 목록 (API 096·080). 실행 기록에 정의·실행자·대상 표시 이름을 붙이고, 본인 실행 범위는 여러 작업 표의 요청자를 함께 본다. 여러 도메인 표를
 * 같은 규칙으로 훑는 조회라 JdbcClient로 쓴다(조회 쿼리 기준 초안 2-2, S15P31C106-294). SQL에 이어 붙이는 것은 이 클래스의 상수뿐이고 값은 모두
 * 바인딩한다. Agent 조회 기록(AGENT_QUERY)은 늘 뺀다.
 */
@Repository
@RequiredArgsConstructor
public class AuditExecutionQueryRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** 종류 탭. action_mode가 없는 ACTION(정의가 지워진 경우 등)은 관리 Action으로 본다. */
    private static final String CATEGORY =
            """
            case e.event_kind
                when 'ANALYSIS_RUN' then 'ANALYSIS'
                when 'ACTION' then case when d.action_mode = 'EXECUTE' then 'OPERATION' else 'CHANGE' end
                else 'DATA_JOB'
            end""";

    /** 대상 표시 이름. 이름 칸이 있는 표만 채우고 나머지는 null이다(화면은 targetType·targetId로 표시). */
    private static final String TARGET_LABEL =
            """
            case e.target_type
                when 'object_instance' then (select t.display_name from object_instance t where t.id = e.target_id)
                when 'account' then (select t.display_name from account t where t.id = e.target_id)
                when 'action_definition' then (select t.name from action_definition t where t.id = e.target_id)
                when 'object_type' then (select t.name from object_type t where t.id = e.target_id)
                when 'link_type' then (select t.name from link_type t where t.id = e.target_id)
                when 'property_definition' then (select t.name from property_definition t where t.id = e.target_id)
                when 'data_source' then (select t.name from data_source t where t.id = e.target_id)
            end""";

    /**
     * 본인 실행. 실행자가 본인인 기록(직접 실행, 본인을 대신한 Agent)과, 본인이 요청한 작업의 결과 줄(SYSTEM)이다. 요청자가 없는 작업(주기 수집 등)의
     * 결과는 ADMIN만 본다.
     */
    private static final String MINE =
            """
             and (e.actor_id = :viewerId or (e.actor_type = 'SYSTEM' and (
                (e.target_type = 'analysis_run' and exists (
                    select 1 from analysis_run t where t.id = e.target_id and t.requested_by = :viewerId))
                or (e.target_type = 'sync_run' and exists (
                    select 1 from sync_run t where t.id = e.target_id and t.requested_by = :viewerId))
                or (e.target_type = 'check_run' and exists (
                    select 1 from check_run t where t.id = e.target_id and t.requested_by = :viewerId))
                or (e.target_type = 'source_connection_check' and exists (
                    select 1 from source_connection_check t
                    where t.id = e.target_id and t.requested_by = :viewerId))
                or (e.target_type = 'source_preview' and exists (
                    select 1 from source_preview t where t.id = e.target_id and t.requested_by = :viewerId)))))
            """;

    private static final String FROM =
            """
             from action_execution e
             left join action_definition d on d.id = e.action_definition_id
             left join account a on a.id = e.actor_id
             where e.event_kind <> 'AGENT_QUERY'
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EntityChangeRepository changes;

    /** 조건에 맞는 기록을 최근 요청순으로. viewerId가 있으면 그 사람의 본인 실행만 본다. */
    public Page<AuditExecutionResponse> search(Filter filter, Long viewerId, Pageable pageable) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        if (viewerId != null) {
            where.append(MINE);
            params.put("viewerId", viewerId);
        }
        add(where, params, " and e.actor_id = :actorId", "actorId", filter.actorId());
        add(where, params, " and d.code = :actionCode", "actionCode", filter.actionCode());
        add(where, params, " and e.status = :status", "status", name(filter.status()));
        add(
                where,
                params,
                " and e.entry_point = :entryPoint",
                "entryPoint",
                name(filter.entryPoint()));
        add(where, params, " and e.event_kind = :eventKind", "eventKind", name(filter.eventKind()));
        add(
                where,
                params,
                " and (" + CATEGORY + ") = :category",
                "category",
                name(filter.category()));
        add(where, params, " and e.target_type = :targetType", "targetType", filter.targetType());
        add(where, params, " and e.target_id = :targetId", "targetId", filter.targetId());
        add(where, params, " and e.requested_at >= :from", "from", timestamp(filter.from()));
        add(where, params, " and e.requested_at < :to", "to", timestamp(filter.to()));

        long total =
                jdbc.sql("select count(*)" + FROM + where)
                        .params(params)
                        .query(Long.class)
                        .single();
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());
        List<AuditExecutionResponse> rows =
                jdbc.sql(
                                "select e.*, d.code as action_code, d.name as action_name,"
                                        + " a.display_name as actor_name,"
                                        + " e.input::text as input_json, e.result::text as result_json,"
                                        + " ("
                                        + CATEGORY
                                        + ") as category, ("
                                        + TARGET_LABEL
                                        + ") as target_label"
                                        + FROM
                                        + where
                                        + " order by e.requested_at desc, e.id desc"
                                        + " limit :limit offset :offset")
                        .params(params)
                        .query((rs, row) -> row(rs))
                        .list();
        return new PageImpl<>(withChanges(rows), pageable, total);
    }

    /** 080 경로의 객체가 있는가. 원천에서 지워진 객체(DELETED_AT_SOURCE)도 이력은 볼 수 있다. */
    public boolean objectExists(long objectId) {
        return jdbc.sql("select exists (select 1 from object_instance where id = :id)")
                .param("id", objectId)
                .query(Boolean.class)
                .single();
    }

    private List<AuditExecutionResponse> withChanges(List<AuditExecutionResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, List<EntityChangeResponse>> byExecution = new LinkedHashMap<>();
        for (EntityChange change :
                changes.findByActionExecutionIdInOrderByIdAsc(
                        rows.stream().map(AuditExecutionResponse::id).toList())) {
            byExecution
                    .computeIfAbsent(change.getActionExecutionId(), id -> new ArrayList<>())
                    .add(EntityChangeResponse.from(change));
        }
        return rows.stream()
                .map(row -> row.withEntityChanges(byExecution.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private AuditExecutionResponse row(ResultSet rs) throws SQLException {
        long definitionId = rs.getLong("action_definition_id");
        Long actionDefinitionId = rs.wasNull() ? null : definitionId;
        long actor = rs.getLong("actor_id");
        Long actorId = rs.wasNull() ? null : actor;
        long target = rs.getLong("target_id");
        Long targetId = rs.wasNull() ? null : target;
        return new AuditExecutionResponse(
                rs.getLong("id"),
                rs.getObject("request_id", UUID.class),
                EventKind.valueOf(rs.getString("event_kind")),
                ExecutionCategory.valueOf(rs.getString("category")),
                actionDefinitionId,
                rs.getString("action_code"),
                rs.getString("action_name"),
                ActorType.valueOf(rs.getString("actor_type")),
                actorId,
                rs.getString("actor_name"),
                rs.getString("actor_role_code"),
                rs.getString("checked_permission_code"),
                EntryPoint.valueOf(rs.getString("entry_point")),
                rs.getString("target_type"),
                targetId,
                rs.getString("target_label"),
                object(rs.getString("input_json")),
                ExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("blocked_reason"),
                object(rs.getString("result_json")),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("finished_at")),
                List.of(),
                List.of());
    }

    private static void add(
            StringBuilder where,
            Map<String, Object> params,
            String clause,
            String key,
            Object value) {
        if (value != null) {
            where.append(clause);
            params.put(key, value);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Map<String, Object> object(String value) {
        return value == null ? Map.of() : json.readValue(value, JSON_OBJECT);
    }

    /** 096·080의 공통 조건. 080은 대상(object_instance, 객체 ID)과 기간·상태만 쓴다. */
    public record Filter(
            Long actorId,
            String actionCode,
            ExecutionStatus status,
            EntryPoint entryPoint,
            EventKind eventKind,
            ExecutionCategory category,
            String targetType,
            Long targetId,
            Instant from,
            Instant to) {}
}

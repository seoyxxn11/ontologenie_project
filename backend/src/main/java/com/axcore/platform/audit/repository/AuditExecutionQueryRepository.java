package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.ontology.repository.ObjectDisplaySql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/**
 * 실행·감사 이력 목록 (API 096·080)과 097·098의 열람 범위. 실행 기록에 정의·실행자·대상 표시 이름을 붙이고, 본인 실행 범위는 여러 작업 표의 요청자를
 * 함께 본다. 여러 도메인 표를 같은 규칙으로 훑는 조회라 JdbcClient로 쓴다(조회 쿼리 기준 초안 2-2, S15P31C106-294). SQL에 이어 붙이는 것은 이
 * 클래스의 상수뿐이고 값은 모두 바인딩한다. Agent 조회 기록(AGENT_QUERY)은 늘 뺀다. 목록은 요약만 읽는다. 입력·결과 JSON과 변경 내역 본문은 읽지 않고
 * 변경 건수만 센다(S15P31C106-374).
 */
@Repository
@RequiredArgsConstructor
public class AuditExecutionQueryRepository {

    /** 목록 한 줄에 쓰는 실행 기록 칸. input·result·blocked_reason은 097에서만 읽는다. */
    private static final String COLUMNS =
            """
            e.id, e.request_id, e.event_kind, e.action_definition_id, e.actor_type, e.actor_id,
             e.actor_role_code, e.checked_permission_code, e.entry_point, e.target_type, e.target_id,
             e.status, e.requested_at, e.finished_at""";

    /** 실행이 바꾼 행 수. ix_entity_change_execution 인덱스로 센다. */
    private static final String CHANGE_COUNT =
            "(select count(*) from entity_change c where c.action_execution_id = e.id)";

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
                when 'object_instance' then (
                    select shown.display_name
                    from object_instance oi
                    """
                    + ObjectDisplaySql.DISPLAY_JOINS
                    + """
                    where oi.id = e.target_id and oi.object_type_id = any(:visibleTypeIds))
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

    /**
     * 조건에 맞는 기록을 최근 요청순으로. viewerId가 있으면 그 사람의 본인 실행만 본다. 대상이 객체이면 표시 이름은 보는 사람의 범위(access)로 정한다: 볼
     * 수 없는 유형이면 null이고, 민감 값을 가려야 하면 객체 조회와 같은 규칙으로 다시 만든다(384·390).
     */
    public Page<AuditExecutionResponse> search(
            Filter filter, Long viewerId, DataAccess access, Pageable pageable) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put(
                "visibleTypeIds",
                new SqlArrayValue("bigint", access.visibleObjectTypeIds().toArray()));
        params.put(
                "maskedTypeIds",
                new SqlArrayValue("bigint", access.maskedObjectTypeIds().toArray()));
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
                                "select "
                                        + COLUMNS
                                        + ", d.code as action_code, d.name as action_name,"
                                        + " a.display_name as actor_name, "
                                        + CHANGE_COUNT
                                        + " as change_count, ("
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
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * 097·098에서 열 수 있는 기록인지. Agent 조회 기록은 늘 아니다. viewerId가 있으면 096과 같은 본인 실행 범위만 본다. 없는 기록도 false다.
     */
    public boolean isVisible(long executionId, Long viewerId) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", executionId);
        String mine = "";
        if (viewerId != null) {
            mine = MINE;
            params.put("viewerId", viewerId);
        }
        return jdbc.sql(
                        "select exists (select 1 from action_execution e"
                                + " where e.id = :id and e.event_kind <> 'AGENT_QUERY'"
                                + mine
                                + ")")
                .params(params)
                .query(Boolean.class)
                .single();
    }

    /** 080 경로 객체의 유형. 없는 객체면 비어 있다. 원천에서 지워진 객체(DELETED_AT_SOURCE)도 이력은 볼 수 있다. */
    public Optional<Long> findObjectTypeId(long objectId) {
        return jdbc.sql("select object_type_id from object_instance where id = :id")
                .param("id", objectId)
                .query(Long.class)
                .optional();
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
                ExecutionStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getInt("change_count"));
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

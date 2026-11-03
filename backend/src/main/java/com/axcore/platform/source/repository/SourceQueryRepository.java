package com.axcore.platform.source.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 화면이 다른 표에서 함께 읽는 값. 접속 계정은 용도·계정명만 읽고 암호문·nonce는 읽지 않는다(암·복호화는 S15P31C106-275). 연결 검사 작업 표는
 * 배치가 상태를 바꾸므로 Entity 없이 SQL로 다룬다.
 */
@Repository
@RequiredArgsConstructor
public class SourceQueryRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** 원천별 접속 계정(SYNC, AI 순). 계정이 없는 원천은 결과에 없다. */
    public List<Credential> findCredentials(Collection<Long> dataSourceIds) {
        if (dataSourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select data_source_id, purpose, username from data_source_credential
                        where data_source_id in (:ids)
                        order by data_source_id, case purpose when 'SYNC' then 0 else 1 end
                        """)
                .param("ids", dataSourceIds)
                .query((rs, row) -> new Credential(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    /** 가장 최근에 요청한 연결 검사 한 건 (API 011). */
    public Optional<ConnectionCheck> findLatestCheck(long dataSourceId) {
        return jdbc.sql(
                        """
                        select * from source_connection_check where data_source_id = :id
                        order by requested_at desc, id desc limit 1
                        """)
                .param("id", dataSourceId)
                .query((rs, row) -> check(rs))
                .optional();
    }

    public Optional<ConnectionCheck> findCheck(long dataSourceId, long checkId) {
        return jdbc.sql(
                        "select * from source_connection_check where id = :checkId and data_source_id = :id")
                .param("checkId", checkId)
                .param("id", dataSourceId)
                .query((rs, row) -> check(rs))
                .optional();
    }

    /** 연결 검사를 QUEUED로 넣는다. 같은 원천의 대기·실행 중 검사가 있으면(부분 유일 인덱스) 넣지 않고 빈 값을 돌려준다. */
    public Optional<Long> insertCheckIfAbsent(long dataSourceId, long requestedBy, Instant now) {
        return jdbc.sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by, requested_at)
                        values (:id, :requestedBy, :now)
                        on conflict (data_source_id) where status in ('QUEUED', 'RUNNING') do nothing
                        returning id
                        """)
                .param("id", dataSourceId)
                .param("requestedBy", requestedBy)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    /** 대기·실행 중인 검사. 잠가 두어 배치가 끝내는 중이면 끝날 때까지 기다린다. */
    public Optional<Long> findActiveCheckForUpdate(long dataSourceId) {
        return jdbc.sql(
                        """
                        select id from source_connection_check
                        where data_source_id = :id and status in ('QUEUED', 'RUNNING') for update
                        """)
                .param("id", dataSourceId)
                .query(Long.class)
                .optional();
    }

    /** 자원별 마지막 성공 수집(sync_run_resource SUCCEEDED 중 가장 늦게 끝난 것) (API 014). */
    public Map<Long, LastSync> findLastSyncs(Collection<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(
                        """
                        select distinct on (source_resource_id) source_resource_id, sync_run_id, finished_at
                        from sync_run_resource
                        where source_resource_id in (:ids) and status = 'SUCCEEDED'
                        order by source_resource_id, finished_at desc, sync_run_id desc
                        """)
                .param("ids", resourceIds)
                .query(
                        (rs, row) ->
                                new LastSync(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getTimestamp(3).toInstant()))
                .list()
                .stream()
                .collect(Collectors.toMap(LastSync::sourceResourceId, s -> s));
    }

    private ConnectionCheck check(ResultSet rs) throws SQLException {
        return new ConnectionCheck(
                rs.getLong("id"),
                rs.getLong("data_source_id"),
                rs.getString("status"),
                rs.getLong("requested_by"),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                json.readValue(rs.getString("result"), new TypeReference<Map<String, Object>>() {}),
                rs.getString("error_message"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Credential(long dataSourceId, String purpose, String username) {}

    /** source_connection_check 한 행. result는 배치가 쓴 {accounts: [...]}이고 비밀값은 없다. */
    public record ConnectionCheck(
            long id,
            long dataSourceId,
            String status,
            long requestedBy,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> result,
            String errorMessage) {}

    public record LastSync(long sourceResourceId, long syncRunId, Instant finishedAt) {}
}

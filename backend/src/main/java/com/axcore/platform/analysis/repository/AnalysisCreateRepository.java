package com.axcore.platform.analysis.repository;

import com.axcore.platform.analysis.dto.AnalysisRunResponse;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class AnalysisCreateRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Set<Long> existingResources(List<Long> ids) {
        return new HashSet<>(
                jdbc.sql("select id from source_resource where id in (:ids)")
                        .param("ids", ids)
                        .query(Long.class)
                        .list());
    }

    public List<Column> currentColumns(List<Long> ids) {
        return jdbc.sql(
                        "select source_resource_id, column_name from source_column where source_resource_id in (:ids) and is_present")
                .param("ids", ids)
                .query((rs, row) -> new Column(rs.getLong(1), rs.getString(2)))
                .list();
    }

    public boolean syncExists(long id) {
        return jdbc.sql("select exists(select 1 from sync_run where id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public Optional<Long> insertIfAbsent(
            String kind,
            String key,
            Map<String, Object> range,
            Long syncId,
            long accountId,
            Instant now) {
        return jdbc.sql(
                        """
                insert into analysis_run(run_kind, work_key, analysis_range, based_on_sync_run_id, requested_by, requested_at)
                values (:kind, :key, cast(:range as jsonb), :sync, :account, :now)
                on conflict (work_key) where status in ('QUEUED','RUNNING') do nothing returning id
                """)
                .param("kind", kind)
                .param("key", key)
                .param("range", json.writeValueAsString(range))
                .param("sync", syncId, Types.BIGINT)
                .param("account", accountId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findActiveForUpdate(String key) {
        return jdbc.sql(
                        "select id from analysis_run where work_key=:key and status in ('QUEUED','RUNNING') for update")
                .param("key", key)
                .query(Long.class)
                .optional();
    }

    public void insertTargets(long runId, List<Long> ids) {
        for (long id : ids) {
            jdbc.sql(
                            "insert into analysis_run_target(analysis_run_id, source_resource_id) values (:run, :resource)")
                    .param("run", runId)
                    .param("resource", id)
                    .update();
        }
    }

    public AnalysisRunResponse read(long id, boolean joined) {
        var targets =
                jdbc.sql(
                                "select source_resource_id from analysis_run_target where analysis_run_id=:id order by source_resource_id")
                        .param("id", id)
                        .query((rs, row) -> new AnalysisRunResponse.Target(rs.getLong(1)))
                        .list();
        return jdbc.sql("select * from analysis_run where id=:id")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new AnalysisRunResponse(
                                        rs.getLong("id"),
                                        rs.getString("run_kind"),
                                        rs.getString("status"),
                                        targets,
                                        instant(rs.getTimestamp("requested_at")),
                                        instant(rs.getTimestamp("started_at")),
                                        instant(rs.getTimestamp("finished_at")),
                                        object(rs.getString("result_summary")),
                                        rs.getString("error_message"),
                                        rs.getLong("requested_by"),
                                        object(rs.getString("analysis_range")),
                                        rs.getObject("based_on_sync_run_id", Long.class),
                                        joined))
                .optional()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private Map<String, Object> object(String value) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Column(long sourceResourceId, String name) {}
}

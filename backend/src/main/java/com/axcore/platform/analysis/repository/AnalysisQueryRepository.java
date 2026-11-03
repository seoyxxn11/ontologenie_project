package com.axcore.platform.analysis.repository;

import com.axcore.platform.analysis.dto.AnalysisResponse;
import com.axcore.platform.analysis.dto.AnalysisRunResponse.Target;
import com.axcore.platform.analysis.dto.AnalysisSearchRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

@Repository
@RequiredArgsConstructor
public class AnalysisQueryRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<AnalysisResponse> search(AnalysisSearchRequest request, Pageable pageable) {
        String where = " where true";
        Map<String, Object> parameters = new HashMap<>();
        if (request.runKind() != null) {
            where += " and run_kind=:kind";
            parameters.put("kind", request.runKind());
        }
        if (request.status() != null) {
            where += " and status=:status";
            parameters.put("status", request.status());
        }
        long total =
                jdbc.sql("select count(*) from analysis_run" + where)
                        .params(parameters)
                        .query(Long.class)
                        .single();
        parameters.put("limit", pageable.getPageSize());
        parameters.put("offset", pageable.getOffset());
        List<AnalysisResponse> runs =
                jdbc.sql(
                                "select * from analysis_run"
                                        + where
                                        + " order by id desc limit :limit offset :offset")
                        .params(parameters)
                        .query(this::mapRun)
                        .list();
        return new PageImpl<>(withTargets(runs), pageable, total);
    }

    public Optional<AnalysisResponse> findById(long id) {
        return withTargets(
                        jdbc.sql("select * from analysis_run where id=:id")
                                .param("id", id)
                                .query(this::mapRun)
                                .list())
                .stream()
                .findFirst();
    }

    public boolean exists(long id) {
        return jdbc.sql("select exists(select 1 from analysis_run where id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private List<AnalysisResponse> withTargets(List<AnalysisResponse> runs) {
        if (runs.isEmpty()) {
            return runs;
        }
        Map<Long, List<Target>> targets = new HashMap<>();
        jdbc.sql(
                        """
                select analysis_run_id, source_resource_id from analysis_run_target
                where analysis_run_id in (:ids) order by analysis_run_id, source_resource_id
                """)
                .param("ids", runs.stream().map(AnalysisResponse::id).toList())
                .query(
                        (rs, row) -> {
                            targets.computeIfAbsent(
                                            rs.getLong("analysis_run_id"),
                                            ignored -> new ArrayList<>())
                                    .add(new Target(rs.getLong("source_resource_id")));
                            return rs.getLong("analysis_run_id");
                        })
                .list();
        return runs.stream()
                .map(run -> run.withTargets(targets.getOrDefault(run.id(), List.of())))
                .toList();
    }

    private AnalysisResponse mapRun(ResultSet rs, int row) throws SQLException {
        return new AnalysisResponse(
                rs.getLong("id"),
                rs.getString("run_kind"),
                rs.getString("status"),
                List.of(),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                object(rs.getString("result_summary")),
                rs.getString("error_message"),
                rs.getLong("requested_by"),
                object(rs.getString("analysis_range")),
                rs.getObject("based_on_sync_run_id", Long.class));
    }

    private Map<String, Object> object(String value) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

package com.axcore.platform.analysis.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** AI에 분석 시작을 알릴 때 필요한 실행·대상·원천 정보를 읽는다. 계정은 source 도메인의 SourceCredentialService로 읽는다. */
@Repository
@RequiredArgsConstructor
public class AnalysisStartRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Optional<Run> findRun(long id) {
        return jdbc.sql("select run_kind, status, analysis_range from analysis_run where id=:id")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Run(
                                        rs.getString("run_kind"),
                                        rs.getString("status"),
                                        object(rs.getString("analysis_range"))))
                .optional();
    }

    public List<Target> findTargets(long runId) {
        return jdbc.sql(
                        """
                select r.id, r.resource_key, r.source_metadata,
                       d.id as data_source_id, d.code, d.source_kind, d.connection_config
                from analysis_run_target t
                join source_resource r on r.id = t.source_resource_id
                join data_source d on d.id = r.data_source_id
                where t.analysis_run_id = :runId
                order by r.id
                """)
                .param("runId", runId)
                .query(
                        (rs, row) ->
                                new Target(
                                        rs.getLong("id"),
                                        rs.getString("resource_key"),
                                        object(rs.getString("source_metadata")),
                                        rs.getLong("data_source_id"),
                                        rs.getString("code"),
                                        rs.getString("source_kind"),
                                        object(rs.getString("connection_config"))))
                .list();
    }

    private Map<String, Object> object(String value) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    public record Run(String runKind, String status, Map<String, Object> analysisRange) {}

    public record Target(
            long sourceResourceId,
            String resourceKey,
            Map<String, Object> sourceMetadata,
            long dataSourceId,
            String dataSourceCode,
            String sourceKind,
            Map<String, Object> connectionConfig) {}
}

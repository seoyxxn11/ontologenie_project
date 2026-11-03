package com.axcore.platform.analysis.repository;

import com.axcore.platform.analysis.domain.AnalysisRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class AnalysisRunRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Optional<AnalysisRun> findForUpdate(long id) {
        return jdbc.sql(
                        "select id, status, started_at, finished_at from analysis_run where id=:id for update")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new AnalysisRun(
                                        rs.getLong("id"),
                                        rs.getString("status"),
                                        instant(rs.getTimestamp("started_at")),
                                        instant(rs.getTimestamp("finished_at"))))
                .optional();
    }

    public void start(long id, Instant now) {
        jdbc.sql("update analysis_run set status='RUNNING', started_at=:now where id=:id")
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void succeed(long id, Map<String, Object> summary, Instant now) {
        jdbc.sql(
                        """
                update analysis_run set status='SUCCEEDED', finished_at=:now,
                    result_summary=cast(:summary as jsonb), error_message=null where id=:id
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .param("summary", json.writeValueAsString(summary))
                .update();
    }

    public void fail(long id, String error, Instant now) {
        jdbc.sql(
                        """
                update analysis_run set status='FAILED', finished_at=:now, error_message=:error where id=:id
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .param("error", error)
                .update();
    }

    /** 아직 QUEUED인 실행만 FAILED로 바꾼다. 바꿨으면 true. */
    public boolean failIfQueued(long id, String error, Instant now) {
        return jdbc.sql(
                                """
                update analysis_run set status='FAILED', finished_at=:now, error_message=:error
                where id=:id and status='QUEUED'
                """)
                        .param("id", id)
                        .param("now", Timestamp.from(now))
                        .param("error", error)
                        .update()
                == 1;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

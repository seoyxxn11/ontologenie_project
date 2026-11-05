package com.axcore.platform.analysis.repository;

import com.axcore.platform.analysis.domain.AnalysisRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    /**
     * 기준 시각보다 오래된 QUEUED(요청 시각 기준)를 FAILED로 바꾸고 바꾼 ID를 돌려준다. 조건과 갱신을 한 문장으로 해서 105 완료 알림과 겹쳐도 이미 끝난
     * 실행은 건드리지 않는다.
     */
    public List<Long> failQueuedBefore(Instant cutoff, String error, Instant now) {
        return failStale("QUEUED", "requested_at", cutoff, error, now);
    }

    /** 기준 시각보다 오래된 RUNNING(시작 시각 기준)을 FAILED로 바꾸고 바꾼 ID를 돌려준다. */
    public List<Long> failRunningBefore(Instant cutoff, String error, Instant now) {
        return failStale("RUNNING", "started_at", cutoff, error, now);
    }

    /** status와 timeColumn은 위 두 메서드의 상수만 받는다. */
    private List<Long> failStale(
            String status, String timeColumn, Instant cutoff, String error, Instant now) {
        return jdbc.sql(
                        """
                update analysis_run set status='FAILED', finished_at=:now, error_message=:error
                where status=:status and %s < :cutoff
                returning id
                """
                                .formatted(timeColumn))
                .param("status", status)
                .param("cutoff", Timestamp.from(cutoff))
                .param("now", Timestamp.from(now))
                .param("error", error)
                .query(Long.class)
                .list();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

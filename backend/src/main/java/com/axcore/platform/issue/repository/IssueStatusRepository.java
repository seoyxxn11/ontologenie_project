package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.domain.IssueStatus;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 문제 상태 변경에 필요한 잠금·재검증 확인·갱신. */
@Repository
@RequiredArgsConstructor
public class IssueStatusRepository {

    private final JdbcClient jdbc;

    /** 상태를 바꾸는 동안 다른 변경이 끼어들지 않도록 문제 행을 잠근다. */
    public Optional<LockedIssue> lock(long issueId) {
        return jdbc.sql("select status, row_version from issue where id = :id for update")
                .param("id", issueId)
                .query(
                        (rs, rowNum) ->
                                new LockedIssue(
                                        IssueStatus.valueOf(rs.getString("status")),
                                        rs.getLong("row_version")))
                .optional();
    }

    /**
     * 해결 근거로 쓸 수 있는 재검증인지 확인한다.
     *
     * <ul>
     *   <li>REVERIFY이고 SUCCEEDED인 검사에서 이 문제가 PASSED였다.
     *   <li>그 검사가 이 문제를 조치완료·재검증대기로 바꾼 뒤에 시작했다. 고친 자료를 읽은 검사여야 한다.
     *   <li>그 뒤에 시작한 검사에서 이 문제가 다시 FAILED가 나오지 않았다.
     * </ul>
     */
    public boolean isUsableReverification(long issueId, long checkRunId) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1
                            from issue_verification v
                            join check_run r on r.id = v.check_run_id
                            where v.issue_id = :issueId
                              and v.check_run_id = :runId
                              and v.outcome = 'PASSED'
                              and r.trigger_kind = 'REVERIFY'
                              and r.status = 'SUCCEEDED'
                              and r.started_at >= (
                                  select max(h.changed_at)
                                  from issue_history h
                                  where h.issue_id = :issueId
                                    and h.to_status = 'FIXED_PENDING_REVERIFY'
                              )
                              and not exists (
                                  select 1
                                  from issue_verification later
                                  join check_run later_run on later_run.id = later.check_run_id
                                  where later.issue_id = :issueId
                                    and later.outcome = 'FAILED'
                                    and later_run.started_at > r.started_at
                              )
                        )
                        """)
                .param("issueId", issueId)
                .param("runId", checkRunId)
                .query(Boolean.class)
                .single();
    }

    public void updateStatus(
            long issueId, IssueStatus status, Long resolvedCheckRunId, Instant now) {
        jdbc.sql(
                        """
                        update issue
                        set status = :status,
                            resolved_check_run_id = :runId,
                            resolved_outcome = case when :runId is null then null else 'PASSED' end,
                            row_version = row_version + 1,
                            updated_at = :now
                        where id = :id
                        """)
                .param("status", status.name())
                .param("runId", resolvedCheckRunId, Types.BIGINT)
                .param("now", Timestamp.from(now))
                .param("id", issueId)
                .update();
    }

    public void insertHistory(
            long issueId,
            IssueStatus from,
            IssueStatus to,
            String note,
            Long checkRunId,
            long changedBy,
            Instant now) {
        jdbc.sql(
                        """
                        insert into issue_history (
                            issue_id, from_status, to_status, note, check_run_id, changed_by, changed_at
                        )
                        values (:issueId, :from, :to, :note, :runId, :changedBy, :now)
                        """)
                .param("issueId", issueId)
                .param("from", from.name())
                .param("to", to.name())
                .param("note", note)
                .param("runId", checkRunId, Types.BIGINT)
                .param("changedBy", changedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public record LockedIssue(IssueStatus status, long rowVersion) {}
}

package com.axcore.platform.issue;

import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 문제 테스트가 쓰는 검사 실행·규칙·문제 행을 DB에 직접 만든다. */
public record IssueTestFixtures(JdbcClient jdbc) {

    /**
     * 화면처럼 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 같은 값으로 보낸다. spring-security-test의 csrf()는 공유 컨텍스트의
     * CsrfFilter 저장소를 바꿔 같은 컨텍스트를 쓰는 다른 테스트의 실제 로그인 요청을 깨뜨리므로 쓰지 않는다.
     */
    public static RequestPostProcessor csrfToken() {
        String token = UUID.randomUUID().toString();
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    public long insertAdmin() {
        return jdbc.sql(
                        """
                        insert into account (
                            login_id, display_name, password_hash, role_id, must_change_password
                        )
                        values (
                            :loginId, '문제 테스트 관리자', 'test-hash',
                            (select id from role where code = 'ADMIN'), false
                        )
                        returning id
                        """)
                .param("loginId", "issue-test-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    public long insertCandidateRun() {
        return jdbc.sql(
                        """
                        insert into check_run (trigger_kind, work_key)
                        values ('CANDIDATE', :workKey)
                        returning id
                        """)
                .param("workKey", "issue-test-run-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    /** 재검증 실행. 문제를 해결로 바꿀 때 근거로 쓴다. */
    public long insertReverifyRun(long comparedToRunId, long accountId) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key, compared_to_run_id,
                            requested_by, started_at, finished_at
                        )
                        values ('REVERIFY', 'SUCCEEDED', :workKey, :comparedTo, :accountId, now(), now())
                        returning id
                        """)
                .param("workKey", "issue-test-reverify-" + UUID.randomUUID())
                .param("comparedTo", comparedToRunId)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    /** 시작 시각과 상태를 정한 검사 실행. 재검증 시점 규칙을 시험할 때 쓴다. */
    public long insertRun(
            String triggerKind,
            String status,
            Instant startedAt,
            Long comparedToRunId,
            long accountId) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key, compared_to_run_id,
                            requested_by, started_at, finished_at, error_message
                        )
                        values (
                            :kind, :status, :workKey, :comparedTo, :accountId, :startedAt,
                            case when cast(:status as varchar) in ('SUCCEEDED', 'FAILED')
                                then cast(:startedAt as timestamptz) end,
                            case when cast(:status as varchar) = 'FAILED' then '테스트 실패' end
                        )
                        returning id
                        """)
                .param("kind", triggerKind)
                .param("status", status)
                .param("workKey", "issue-test-run-" + UUID.randomUUID())
                .param("comparedTo", comparedToRunId, Types.BIGINT)
                .param("accountId", accountId)
                .param("startedAt", Timestamp.from(startedAt), Types.TIMESTAMP)
                .query(Long.class)
                .single();
    }

    public void insertVerification(long issueId, long checkRunId, String outcome) {
        jdbc.sql(
                        """
                        insert into issue_verification (issue_id, check_run_id, outcome)
                        values (:issueId, :runId, :outcome)
                        """)
                .param("issueId", issueId)
                .param("runId", checkRunId)
                .param("outcome", outcome)
                .update();
    }

    /** 관리자가 at에 문제를 조치완료·재검증대기로 바꾼 상태를 만든다. */
    public void markFixedAt(long issueId, long accountId, Instant at) {
        jdbc.sql(
                        """
                        update issue
                        set status = 'FIXED_PENDING_REVERIFY', row_version = row_version + 1
                        where id = :id
                        """)
                .param("id", issueId)
                .update();
        jdbc.sql(
                        """
                        insert into issue_history (
                            issue_id, from_status, to_status, note, changed_by, changed_at
                        )
                        values (:id, 'CHECKING', 'FIXED_PENDING_REVERIFY', '원천 수정 완료', :accountId, :at)
                        """)
                .param("id", issueId)
                .param("accountId", accountId)
                .param("at", Timestamp.from(at), Types.TIMESTAMP)
                .update();
    }

    public void setStatus(long issueId, String status) {
        jdbc.sql("update issue set status = :status where id = :id")
                .param("status", status)
                .param("id", issueId)
                .update();
    }

    /** 승인된 연결 규칙 개정본 하나와 그 규칙이 쓰는 유형들을 만든다. */
    public long insertLinkMapping() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.sql(
                        """
                        with source_type as (
                            insert into object_type (code, name, access_group)
                            values (:sourceCode, '문제 테스트 출발 유형', 'PRODUCTION')
                            returning id
                        ),
                        target_type as (
                            insert into object_type (code, name, access_group)
                            values (:targetCode, '문제 테스트 도착 유형', 'PRODUCTION')
                            returning id
                        ),
                        relation_type as (
                            insert into link_type (
                                code, name, source_type_id, target_type_id, cardinality, description
                            )
                            select :linkCode, '문제 테스트 관계', s.id, t.id, 'MANY_TO_ONE', '문제 테스트'
                            from source_type s
                            cross join target_type t
                            returning id
                        )
                        insert into link_mapping (link_type_id, code, revision)
                        select id, :mappingCode, 1
                        from relation_type
                        returning id
                        """)
                .param("sourceCode", "SRC_" + suffix)
                .param("targetCode", "DST_" + suffix)
                .param("linkCode", "LINK_" + suffix)
                .param("mappingCode", "MAP_" + suffix)
                .query(Long.class)
                .single();
    }

    public long insertIssue(
            long checkRunId, long linkMappingId, String kind, String status, String evidence) {
        return jdbc.sql(
                        """
                        insert into issue (
                            issue_kind, status, dedupe_key, link_mapping_id, raw_value,
                            transformed_value, failure_reason, evidence, detected_check_run_id
                        )
                        values (
                            :kind, :status, :dedupeKey, :linkMappingId, 'W23', 'E023',
                            '변환 값 E023과 같은 직원이 없습니다.', cast(:evidence as jsonb), :runId
                        )
                        returning id
                        """)
                .param("kind", kind)
                .param("status", status)
                .param("dedupeKey", kind + ":test:" + UUID.randomUUID())
                .param("linkMappingId", linkMappingId)
                .param("evidence", evidence)
                .param("runId", checkRunId)
                .query(Long.class)
                .single();
    }

    public void setCreatedAt(long issueId, Instant createdAt) {
        jdbc.sql("update issue set created_at = :createdAt where id = :id")
                .param("createdAt", Timestamp.from(createdAt), Types.TIMESTAMP)
                .param("id", issueId)
                .update();
    }

    /** 통과한 재검증 기록을 남기고 문제를 해결로 바꾼다. */
    public void resolve(long issueId, long reverifyRunId) {
        jdbc.sql(
                        """
                        insert into issue_verification (issue_id, check_run_id, outcome)
                        values (:issueId, :runId, 'PASSED')
                        """)
                .param("issueId", issueId)
                .param("runId", reverifyRunId)
                .update();
        jdbc.sql(
                        """
                        update issue
                        set status = 'RESOLVED', resolved_check_run_id = :runId,
                            resolved_outcome = 'PASSED'
                        where id = :issueId
                        """)
                .param("issueId", issueId)
                .param("runId", reverifyRunId)
                .update();
    }
}

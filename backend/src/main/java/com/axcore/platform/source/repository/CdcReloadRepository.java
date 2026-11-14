package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.AutoReloadReason;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CDC 테이블 자동 다시 읽기 감시 작업(S15P31C106-330)이 대상을 찾고 전체 읽기 실행을 넣는다. JdbcClient: Entity가 없는 작업
 * 표(sync_run, sync_run_resource)를 쓰고, 대상 판단에 매핑(object_source_mapping)과 CDC 상태(cdc_offset)를 한 SQL로
 * join·검사한다.
 */
@Repository
@RequiredArgsConstructor
public class CdcReloadRepository {

    private final JdbcClient jdbc;

    /**
     * 다시 읽기를 넣을 CDC 테이블. 켜진 매핑이 있고 loaded_lsn이 비었으며, 대기·실행 중인 전체 읽기가 없고, 원천 접속 정보가 바뀌어 커넥터를 맞추는
     * 중(SOURCE_CHANGED)이 아닌 테이블이다. 마지막 성공 이후의 FULL 실패(제외 문구에 맞는 실패는 빼고)가 maxFailures번 이상이거나 마지막 실패가
     * retryAfter보다 뒤면 뺀다. 멈춤 횟수는 감시 작업이 넣은 실행(RECOVERY)의 실패만 센다. 관리자 수동 실행(073)은 여러 테이블을 한 실행에 묶어
     * 다른 테이블 때문에 함께 실패할 수 있어서다. 수동 실행의 실패도 다시 넣기를 미루는 데는 쓴다.
     *
     * @param excludedFailurePattern 실패로 세지 않을 error_message의 LIKE 패턴(escape 문자는 백슬래시)
     * @param policyBlockedPattern 멈춤 횟수에만 세지 않을 패턴(원천 데이터 정책 때문에 계획을 만들지 못한 실패, 402). 고칠 때까지 같은
     *     사유로는 다시 넣지 않으므로 횟수를 올릴 까닭이 없고, 세면 다른 실패와 합쳐 3번이 되어 고친 뒤에도 자동으로 넣지 않는다
     */
    public List<DueResource> findDue(
            Instant retryAfter,
            int maxFailures,
            String excludedFailurePattern,
            String policyBlockedPattern,
            int limit) {
        return jdbc.sql(
                        """
                        select r.id, r.data_source_id,
                               case
                                   when exists (select 1 from cdc_offset o
                                                where o.data_source_id = r.data_source_id
                                                  and o.recovery_reason is not null) then 'RECOVERY'
                                   when r.full_load_done_gen is null then 'FIRST_LOAD'
                                   else 'RELOAD_REQUESTED'
                               end as reason
                        from source_resource r
                        cross join lateral (
                            select count(*) filter (where fr.trigger_kind = 'RECOVERY'
                                                      and f.error_message not like :policyBlocked escape '\\')
                                       as failures,
                                   max(f.finished_at) as last_failed_at
                            from sync_run_resource f
                            join sync_run fr on fr.id = f.sync_run_id
                            where f.source_resource_id = r.id and f.mode = 'FULL' and f.status = 'FAILED'
                              and f.error_message not like :excluded escape '\\'
                              and f.finished_at > coalesce(
                                      (select max(s.finished_at) from sync_run_resource s
                                       where s.source_resource_id = r.id and s.mode = 'FULL'
                                         and s.status = 'SUCCEEDED'),
                                      '-infinity'::timestamptz)
                        ) failed
                        where r.sync_strategy = 'CDC' and r.loaded_lsn is null
                          and exists (select 1 from object_source_mapping m
                                      where m.source_resource_id = r.id and m.is_active)
                          and not exists (select 1 from sync_run_resource a
                                          where a.source_resource_id = r.id
                                            and a.status in ('QUEUED', 'RUNNING'))
                          and not exists (select 1 from cdc_offset o
                                          where o.data_source_id = r.data_source_id
                                            and o.recovery_reason = 'SOURCE_CHANGED')
                          and failed.failures < :maxFailures
                          and (failed.last_failed_at is null or failed.last_failed_at <= :retryAfter)
                        order by r.id
                        limit :limit
                        """)
                .param("excluded", excludedFailurePattern)
                .param("policyBlocked", policyBlockedPattern)
                .param("maxFailures", maxFailures)
                .param("retryAfter", toTimestamp(retryAfter))
                .param("limit", limit)
                .query(
                        (rs, row) ->
                                new DueResource(
                                        rs.getLong("id"),
                                        rs.getLong("data_source_id"),
                                        AutoReloadReason.valueOf(rs.getString("reason"))))
                .list();
    }

    /**
     * 이 테이블의 마지막 전체 읽기 시도가 실패였으면 그 사유. 마지막 시도가 성공이거나 시도가 없으면 빈 값이다. 적재 계획을 만들 수 없는 테이블을 같은 사유로 거듭
     * 넣지 않는 데 쓴다(402).
     */
    public Optional<String> lastFullFailure(long resourceId) {
        return jdbc.sql(
                        """
                        select status, coalesce(error_message, '') as error_message
                        from sync_run_resource
                        where source_resource_id = :resource and mode = 'FULL'
                          and status in ('SUCCEEDED', 'FAILED')
                        order by finished_at desc nulls last, sync_run_id desc
                        limit 1
                        """)
                .param("resource", resourceId)
                .query(
                        (rs, row) ->
                                new FullAttempt(
                                        rs.getString("status"), rs.getString("error_message")))
                .optional()
                .filter(attempt -> "FAILED".equals(attempt.status()))
                .map(FullAttempt::errorMessage);
    }

    /** trigger_kind RECOVERY의 빈 실행을 QUEUED로 넣는다. 요청자는 없다. */
    public long insertRecoveryRun(long dataSourceId) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind)
                        values (:source, 'RECOVERY')
                        returning id
                        """)
                .param("source", dataSourceId)
                .query(Long.class)
                .single();
    }

    /**
     * 테이블 하나를 FULL·QUEUED로 실행에 넣는다. 그 사이 다시 읽기 표시가 풀렸거나, SOURCE_CHANGED가 되었거나, 다른 실행이 먼저 이 테이블을
     * 넣었으면(uq_sync_run_resource_active) 넣지 않고 false를 돌려준다.
     */
    public boolean insertFullResource(long syncRunId, long resourceId) {
        return jdbc.sql(
                                """
                                insert into sync_run_resource
                                    (sync_run_id, data_source_id, source_resource_id, mode)
                                select :run, r.data_source_id, r.id, 'FULL'
                                from source_resource r
                                where r.id = :resource and r.sync_strategy = 'CDC'
                                  and r.loaded_lsn is null
                                  and not exists (select 1 from cdc_offset o
                                                  where o.data_source_id = r.data_source_id
                                                    and o.recovery_reason = 'SOURCE_CHANGED')
                                on conflict (source_resource_id) where status in ('QUEUED', 'RUNNING')
                                do nothing
                                """)
                        .param("run", syncRunId)
                        .param("resource", resourceId)
                        .update()
                == 1;
    }

    private static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record FullAttempt(String status, String errorMessage) {}

    public record DueResource(long resourceId, long dataSourceId, AutoReloadReason reason) {}
}

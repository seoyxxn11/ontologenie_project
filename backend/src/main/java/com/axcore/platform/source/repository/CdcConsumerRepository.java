package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.RecoveryReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CDC 소비자(198-5)의 원천별 상태({@code cdc_offset})와 CDC 수집 기록({@code sync_run}·{@code
 * sync_run_resource}).
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"). 표마다 경우가 달라 메서드 주석에도 적는다.
 *
 * <ul>
 *   <li>{@code sync_run}·{@code sync_run_resource}: Entity를 두지 않는 작업 테이블이다({@link
 *       SyncRunRepository}와 같다).
 *   <li>{@code cdc_offset}: 조건부 insert와 잠금이다. 원천마다 한 행을 처음 반영할 때 {@code INSERT ... ON CONFLICT DO
 *       NOTHING}으로 만들고 곧바로 {@code SELECT ... FOR UPDATE}로 잡아야, 처음 반영이 동시에 와도 키 충돌 없이 한 소비자만 지나간다.
 *       아직 Entity가 없는 표라 기존 방식(JdbcClient)을 따르고, {@code xid8} 칸은 JPA 기본 형식이 없다.
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class CdcConsumerRepository {

    /** 원천 토픽은 파티션 1개다(316, 결정 문서 9.6). */
    static final int PARTITION = 0;

    private final JdbcClient jdbc;

    /**
     * 원천의 소비 상태 행을 잠근다. 없으면 만든다(다음 위치 0). 같은 원천을 두 소비자가 동시에 반영하지 못한다. JdbcClient: 조건부 insert({@code
     * ON CONFLICT DO NOTHING})와 잠금.
     *
     * @throws IllegalStateException 그 토픽이 다른 원천의 것으로 저장되어 있다
     */
    /**
     * 그 원천의 소비 상태 행을 모두 잠근다(전체 읽기 저장, S15P31C106-364). CDC 반영·복구 표시·재연결이 cdc_offset →
     * source_resource 순서로 잠그므로 전체 읽기 저장도 원천 테이블보다 먼저 이 행을 잡는다. 아직 소비 전이라 행이 없으면 아무것도 잠그지 않는다.
     */
    public void lockSourceOffsets(long dataSourceId) {
        jdbc.sql(
                        """
                        select 1 from cdc_offset where data_source_id = :source
                        order by topic, partition_no for update
                        """)
                .param("source", dataSourceId)
                .query()
                .listOfRows();
    }

    public OffsetState lockOffset(long dataSourceId, String topic) {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset)
                        values (:topic, :partition, :source, 0)
                        on conflict (topic, partition_no) do nothing
                        """)
                .param("topic", topic)
                .param("partition", PARTITION)
                .param("source", dataSourceId)
                .update();
        OffsetState state =
                jdbc.sql(
                                """
                                select data_source_id, next_offset, last_txid::text as last_txid,
                                       recovery_reason, resume_after
                                from cdc_offset where topic = :topic and partition_no = :partition
                                for update
                                """)
                        .param("topic", topic)
                        .param("partition", PARTITION)
                        .query(CdcConsumerRepository::offsetState)
                        .single();
        if (state.dataSourceId() != dataSourceId) {
            throw new IllegalStateException(
                    "토픽 %s는 다른 원천(%d)의 소비 위치입니다.".formatted(topic, state.dataSourceId()));
        }
        return state;
    }

    /**
     * 원천 트랜잭션 하나를 반영한 뒤의 상태. 복구가 필요한 원천에는 부르지 않는다(healthy_since와 함께 쓰는 CHECK). JdbcClient: Entity가
     * 없는 {@code cdc_offset}(기존 방식)과 {@code xid8} 칸.
     *
     * @param lastCommitAt 그 트랜잭션의 원천 커밋 시각. 모르면 null(기존 값 유지)
     * @param processingLagMs 처리 지연. 모르면 null(기존 값 유지)
     */
    public void saveApplied(
            String topic,
            long nextOffset,
            long lastTxid,
            Instant lastCommitAt,
            Instant lastMessageAt,
            Integer processingLagMs) {
        jdbc.sql(
                        """
                        update cdc_offset
                        set next_offset = :next,
                            last_txid = cast(cast(:txid as text) as xid8),
                            last_commit_at = coalesce(cast(:commitAt as timestamptz), last_commit_at),
                            last_message_at = cast(:messageAt as timestamptz),
                            processing_lag_ms = coalesce(cast(:lag as integer), processing_lag_ms),
                            healthy_since = coalesce(healthy_since, now()),
                            updated_at = now()
                        where topic = :topic and partition_no = :partition
                        """)
                .param("next", nextOffset)
                .param("txid", lastTxid)
                .param("commitAt", lastCommitAt == null ? null : Timestamp.from(lastCommitAt))
                .param("messageAt", Timestamp.from(lastMessageAt))
                .param("lag", processingLagMs)
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /**
     * 복구가 필요하다고 표시한다. 이미 표시되어 있으면 처음 이유와 시각을 둔다. 위치는 옮기지 않는다. 아직 쓰지 않은 이어 받을 기준 시각은 버린다(다음 해제 때 새로
     * 정한다). JdbcClient: Entity가 없는 {@code cdc_offset}(기존 방식).
     */
    public void markRecovery(String topic, RecoveryReason reason) {
        jdbc.sql(
                        """
                        update cdc_offset
                        set recovery_reason = coalesce(recovery_reason, :reason),
                            recovery_at = coalesce(recovery_at, now()),
                            healthy_since = null,
                            resume_after = null,
                            updated_at = now()
                        where topic = :topic and partition_no = :partition
                        """)
                .param("reason", reason.name())
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /**
     * 원천의 소비 상태(잠그지 않음). 소비 루프가 시작 위치를 잡을 때 쓴다(198-6). JdbcClient: Entity가 없는 {@code cdc_offset}(기존
     * 방식).
     */
    public Optional<OffsetState> findState(String topic) {
        return jdbc.sql(
                        """
                        select data_source_id, next_offset, last_txid::text as last_txid, recovery_reason,
                               resume_after
                        from cdc_offset where topic = :topic and partition_no = :partition
                        """)
                .param("topic", topic)
                .param("partition", PARTITION)
                .query(CdcConsumerRepository::offsetState)
                .optional();
    }

    /**
     * 복구를 푼 뒤 이어 받을 첫 원천 트랜잭션(BEGIN)을 찾았다(199). 그 위치를 다음 위치로 저장하고 기준 시각을 비운다. 그 사이 다시 복구가 필요해졌거나 이미
     * 위치를 잡았으면 아무것도 하지 않는다. JdbcClient: Entity가 없는 {@code cdc_offset}(기존 방식).
     *
     * @return 위치를 저장했으면 true
     */
    public boolean resumeAt(String topic, long offset) {
        return jdbc.sql(
                                """
                                update cdc_offset
                                set next_offset = :offset, resume_after = null, updated_at = now()
                                where topic = :topic and partition_no = :partition
                                  and recovery_reason is null and resume_after is not null
                                """)
                        .param("offset", offset)
                        .param("topic", topic)
                        .param("partition", PARTITION)
                        .update()
                > 0;
    }

    /**
     * 전체 읽기를 저장한 트랜잭션 끝에서 그 원천의 복구를 푼다(199, 결정 문서 2026-11-10 3.1). 켜진 매핑이 있는 CDC 테이블이 모두 복구 표시 뒤에
     * 시작한 전체 읽기로 적재되었을 때만 푼다. 매핑이 없는 테이블은 소비자도 반영하지 않으므로 기다리지 않는다. 원천 교체({@code SOURCE_CHANGED})는
     * 관리자가 커넥터를 맞췄다고 알리기 전이라 풀지 않는다.
     *
     * <p>풀 때 epoch 기준값({@code last_txid})을 비워 다음 반영 때 원천 {@code xmax}를 새로 읽게 하고, 이어 받을 기준 시각을 그
     * 테이블들의 전체 읽기 중 가장 이른 시작 시각으로 남긴다. 그 시각 뒤에 커밋된 원천 트랜잭션은 그 뒤에 토픽에 쓰이므로 놓치지 않는다. JdbcClient: 여러
     * 표(source_resource·object_source_mapping·sync_run)를 한 조건으로 보고 Entity가 없는 {@code cdc_offset}을
     * 바꾼다.
     *
     * @return 풀었으면 이어 받을 기준 시각
     */
    public Optional<Instant> releaseRecoveryIfReloaded(long dataSourceId) {
        return releaseRecoveryIfReloaded(dataSourceId, List.of());
    }

    /**
     * {@link #releaseRecoveryIfReloaded(long)}와 같되 적재 계획을 만들 수 없는 테이블(402)은 기다리지 않는다. 기다리면 그 테이블의
     * 다시 읽기가 끝나지 않아 원천 전체의 CDC가 멈춘다. 그 테이블은 다시 읽기 표시가 남아 매핑을 고친 뒤 따로 다시 읽고, 그때까지 CDC는 그 테이블의 변경을
     * 건너뛴다.
     *
     * @param blockedResourceIds 기다리지 않을 테이블
     */
    public Optional<Instant> releaseRecoveryIfReloaded(
            long dataSourceId, Collection<Long> blockedResourceIds) {
        // 빈 목록이 IN ()이 되지 않게 없는 번호 하나로 둔다.
        List<Long> blocked =
                blockedResourceIds.isEmpty() ? List.of(-1L) : List.copyOf(blockedResourceIds);
        return jdbc.sql(
                        """
                        with loaded as (
                            select r.loaded_lsn, s.started_at
                            from source_resource r
                            left join sync_run s on s.id = r.full_load_done_gen
                            where r.data_source_id = :source and r.sync_strategy = 'CDC'
                              and r.id not in (:blocked)
                              and exists (select 1 from object_source_mapping m
                                          where m.source_resource_id = r.id and m.is_active)
                        )
                        update cdc_offset o
                        set recovery_reason = null, recovery_at = null, last_txid = null,
                            resume_after = (select min(l.started_at) from loaded l),
                            updated_at = now()
                        where o.data_source_id = :source
                          and o.recovery_reason is not null and o.recovery_reason <> 'SOURCE_CHANGED'
                          and exists (select 1 from loaded)
                          and not exists (select 1 from loaded l
                                          where l.loaded_lsn is null or l.started_at is null
                                             or l.started_at < o.recovery_at)
                        returning o.resume_after
                        """)
                .param("source", dataSourceId)
                .param("blocked", blocked)
                .query((rs, row) -> rs.getTimestamp("resume_after").toInstant())
                .optional();
    }

    /** 복구 중인 원천. 접속 정보 변경(SOURCE_CHANGED)은 커넥터를 맞추기 전이라 풀 수 없으므로 뺀다. */
    public List<Long> findRecoveringSourceIds() {
        return jdbc.sql(
                        """
                        select distinct data_source_id from cdc_offset
                        where recovery_reason is not null and recovery_reason <> 'SOURCE_CHANGED'
                        order by data_source_id
                        """)
                .query(Long.class)
                .list();
    }

    /**
     * 원천 접속 정보(host·port·database)가 바뀌었다(API 012, 결정 문서 11.1-3). 커넥터를 새 DB에 맞출 때까지 그 원천의 CDC 반영을
     * 멈춘다. {@code loaded_lsn}은 비우지 않는다(지금 읽으면 커넥터와 다른 DB를 읽는다).
     *
     * <p>소비 상태 행이 없어도(아직 소비 전) 그 원천에 CDC 테이블이 있으면 행을 SOURCE_CHANGED로 만든다(385). 토픽에는 이미 옛 DB 이벤트가 쌓여
     * 있을 수 있어, 표시하지 않으면 첫 적재는 새 DB를, 소비자는 토픽 처음부터 옛 DB 이벤트를 반영해 두 DB가 섞인다. 만든 행의 위치(0)는 쓰지 않는다.
     * 소비자는 복구 중이면 기다리고, 재연결 뒤 다시 읽기가 끝나 풀리면 이어 받을 기준 시각({@code resume_after}) 뒤부터 읽는다. 소비자가 그 사이 행을
     * 만들면 그 행을 표시한다. CDC 테이블이 없는 원천은 소비하지 않으므로 만들지 않는다. JdbcClient: Entity가 없는 {@code
     * cdc_offset}(기존 방식).
     *
     * @return 표시했으면 true
     */
    public boolean markSourceChanged(long dataSourceId) {
        int marked =
                jdbc.sql(
                                """
                                update cdc_offset
                                set recovery_reason = 'SOURCE_CHANGED', recovery_at = now(),
                                    healthy_since = null, resume_after = null, updated_at = now()
                                where data_source_id = :source
                                """)
                        .param("source", dataSourceId)
                        .update();
        if (marked > 0) {
            return true;
        }
        return jdbc.sql(
                                """
                                insert into cdc_offset
                                    (topic, partition_no, data_source_id, next_offset, recovery_reason, recovery_at)
                                select d.code || '.all', :partition, d.id, 0, 'SOURCE_CHANGED', now()
                                from data_source d
                                where d.id = :source
                                  and exists (select 1 from source_resource r
                                              where r.data_source_id = d.id and r.sync_strategy = 'CDC')
                                on conflict (topic, partition_no) do update
                                set recovery_reason = 'SOURCE_CHANGED', recovery_at = now(),
                                    healthy_since = null, resume_after = null, updated_at = now()
                                """)
                        .param("source", dataSourceId)
                        .param("partition", PARTITION)
                        .update()
                > 0;
    }

    /**
     * 관리자가 커넥터를 새 DB에 맞췄다고 알렸다(199). 원천 교체 표시를 슬롯 재생성({@code SLOT_LOST})으로 바꾸고 시각을 지금으로 다시 찍는다. 이제
     * 다시 읽어도 되므로 배치 감시 작업이 다시 읽기를 넣는다. 테이블의 {@code loaded_lsn}은 호출하는 쪽이 같은 트랜잭션에서 비운다. 행을 잠가 소비자의
     * 반영과 겹치지 않게 한다. JdbcClient: Entity가 없는 {@code cdc_offset}(기존 방식).
     *
     * @return 원천 교체 중이라 바꿨으면 true
     */
    public boolean markReconnected(long dataSourceId) {
        return jdbc.sql(
                                """
                                update cdc_offset
                                set recovery_reason = 'SLOT_LOST', recovery_at = now(), updated_at = now()
                                where data_source_id = :source and recovery_reason = 'SOURCE_CHANGED'
                                """)
                        .param("source", dataSourceId)
                        .update()
                > 0;
    }

    private static OffsetState offsetState(ResultSet rs, int row) throws SQLException {
        String txid = rs.getString("last_txid");
        String reason = rs.getString("recovery_reason");
        Timestamp resumeAfter = rs.getTimestamp("resume_after");
        return new OffsetState(
                rs.getLong("data_source_id"),
                rs.getLong("next_offset"),
                txid == null ? null : Long.parseLong(txid),
                reason == null ? null : RecoveryReason.valueOf(reason),
                resumeAfter == null ? null : resumeAfter.toInstant());
    }

    /**
     * 처음 소비하는 원천의 상태 행을 만든다(다음 위치 = 토픽의 첫 위치). 이미 있으면 그대로 둔다. JdbcClient: 조건부 insert({@code ON
     * CONFLICT DO NOTHING}).
     */
    public void ensureOffset(long dataSourceId, String topic, long startOffset) {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset)
                        values (:topic, :partition, :source, :start)
                        on conflict (topic, partition_no) do nothing
                        """)
                .param("topic", topic)
                .param("partition", PARTITION)
                .param("source", dataSourceId)
                .param("start", startOffset)
                .update();
    }

    /**
     * 메시지를 받았다(198-6). 마지막 메시지 시각을 남기고 수집 정상 여부를 바꾼다. 복구가 필요한 원천은 정상으로 바꾸지 않는다(CHECK). JdbcClient:
     * Entity가 없는 {@code cdc_offset}(기존 방식).
     *
     * @param advanceTo 트랜잭션 밖 heartbeat면 그 다음 위치. 위치는 앞으로만 간다. 옮기지 않으면 null
     * @param healthy 처리 지연이 기준 안이면 true(정상이 된 시각을 처음 한 번만 남김), 아니면 false(비정상)
     */
    public void recordMessage(String topic, Instant receivedAt, Long advanceTo, boolean healthy) {
        jdbc.sql(
                        """
                        update cdc_offset
                        set last_message_at = cast(:at as timestamptz),
                            next_offset = case when recovery_reason is null and cast(:advance as bigint) > next_offset
                                               then cast(:advance as bigint) else next_offset end,
                            healthy_since = case when recovery_reason is not null or not :healthy then null
                                                 else coalesce(healthy_since, now()) end,
                            updated_at = now()
                        where topic = :topic and partition_no = :partition
                        """)
                .param("at", Timestamp.from(receivedAt))
                .param("advance", advanceTo)
                .param("healthy", healthy)
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /** 메시지가 오래 오지 않아 수집 비정상으로 본다(198-6). JdbcClient: Entity가 없는 {@code cdc_offset}(기존 방식). */
    public void markUnhealthy(String topic) {
        jdbc.sql(
                        """
                        update cdc_offset set healthy_since = null, updated_at = now()
                        where topic = :topic and partition_no = :partition and healthy_since is not null
                        """)
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /**
     * 원천 트랜잭션 하나의 CDC 수집 실행. 반영과 같은 트랜잭션에서 SUCCEEDED로만 넣는다(결정 문서 11장 4·5). JdbcClient: Entity를 두지
     * 않는 작업 테이블.
     */
    public long insertCdcRun(long dataSourceId) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, status, started_at, finished_at)
                        values (:source, 'CDC', 'SUCCEEDED', now(), now())
                        returning id
                        """)
                .param("source", dataSourceId)
                .query(Long.class)
                .single();
    }

    /** 그 실행에서 반영한 테이블 하나(INCREMENTAL). JdbcClient: Entity를 두지 않는 작업 테이블. */
    public void insertRunResource(long syncRunId, long dataSourceId, ResourceCounts counts) {
        jdbc.sql(
                        """
                        insert into sync_run_resource
                            (sync_run_id, data_source_id, source_resource_id, mode, status,
                             from_lsn, to_lsn, rows_read, rows_upserted, rows_deleted, started_at, finished_at)
                        values (:run, :source, :resource, 'INCREMENTAL', 'SUCCEEDED',
                                :fromLsn, :toLsn, :read, :upserted, :deleted, now(), now())
                        """)
                .param("run", syncRunId)
                .param("source", dataSourceId)
                .param("resource", counts.resourceId())
                .param("fromLsn", counts.fromLsn())
                .param("toLsn", counts.toLsn())
                .param("read", counts.events())
                .param("upserted", counts.upserted())
                .param("deleted", counts.deleted())
                .update();
    }

    /**
     * @param lastTxid 마지막으로 반영한 원천 트랜잭션(xid8). 처음이면 null
     * @param recoveryReason 복구가 필요하면 그 이유
     * @param resumeAfter 복구를 푼 뒤 아직 위치를 다시 잡지 않았으면 이어 받을 기준 시각(V14)
     */
    public record OffsetState(
            long dataSourceId,
            long nextOffset,
            Long lastTxid,
            RecoveryReason recoveryReason,
            Instant resumeAfter) {}

    /**
     * @param events 이 테이블의 변경 수(rows_read)
     * @param upserted 쓴 객체 수(매핑마다)
     * @param deleted 삭제 표시한 객체 수
     */
    public record ResourceCounts(
            long resourceId, long fromLsn, long toLsn, int events, int upserted, int deleted) {}
}

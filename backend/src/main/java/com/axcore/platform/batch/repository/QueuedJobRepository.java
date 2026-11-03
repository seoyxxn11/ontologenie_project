package com.axcore.platform.batch.repository;

import com.axcore.platform.batch.domain.ClaimedJob;
import com.axcore.platform.batch.domain.QueuedJobType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 작업 표의 상태 칸을 다룬다. 표 이름은 {@link QueuedJobType}에 정한 값만 쓴다. */
@Repository
@RequiredArgsConstructor
public class QueuedJobRepository {

    private final JdbcClient jdbcClient;

    /** 가장 먼저 들어온 QUEUED 행 하나를 RUNNING으로 바꿔 가져간다. 다른 배치 인스턴스가 잡고 있는 행은 기다리지 않고 건너뛴다(SKIP LOCKED). */
    public Optional<ClaimedJob> claimNext(QueuedJobType type, Instant startedAt) {
        return jdbcClient
                .sql(
                        """
                        update %1$s set status = 'RUNNING', started_at = :startedAt
                        where id = (select id from %1$s where status = 'QUEUED'
                                    order by requested_at, id
                                    limit 1 for update skip locked)
                        returning id, requested_at
                        """
                                .formatted(type.table()))
                .param("startedAt", toTimestamp(startedAt))
                .query(
                        (rs, rowNum) ->
                                new ClaimedJob(
                                        type,
                                        rs.getLong("id"),
                                        rs.getObject("requested_at", OffsetDateTime.class)
                                                .toInstant(),
                                        startedAt))
                .optional();
    }

    public boolean isSucceeded(QueuedJobType type, long id) {
        return jdbcClient
                .sql("select status = 'SUCCEEDED' from %s where id = :id".formatted(type.table()))
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** RUNNING인 행만 FAILED로 바꾼다. 바꿨으면 true. */
    public boolean markFailed(
            QueuedJobType type, long id, String errorMessage, Instant finishedAt) {
        return jdbcClient
                        .sql(
                                """
                                update %s set status = 'FAILED', finished_at = :finishedAt,
                                              error_message = :errorMessage
                                where id = :id and status = 'RUNNING'
                                """
                                        .formatted(type.table()))
                        .param("finishedAt", toTimestamp(finishedAt))
                        .param("errorMessage", errorMessage)
                        .param("id", id)
                        .update()
                == 1;
    }

    private static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

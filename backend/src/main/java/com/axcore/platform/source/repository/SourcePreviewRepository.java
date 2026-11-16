package com.axcore.platform.source.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 원천 표본(016)·매핑 미리보기(040) 작업 표 {@code source_preview}. JdbcClient: Entity를 두지 않는 작업 표다({@code
 * sync_run}·{@code source_connection_check}와 같다). 배치 실행 틀이 표 이름으로 상태를 바꾸고, 같은 요청 합류는 jsonb 비교와 잠금이
 * 필요하다.
 */
@Repository
@RequiredArgsConstructor
public class SourcePreviewRepository {

    /** 결과에 원천 값이 들어 있어 요청 1시간 뒤 지운다(V1 기본값과 같다). */
    public static final java.time.Duration RETENTION = java.time.Duration.ofHours(1);

    /** 제한 컬럼의 값 확인(447) 결과는 제한 값이라 짧게 둔다. 요청한 관리자가 읽으면 그때 지우고, 읽지 않아도 이 시간 뒤 정리 작업이 지운다. */
    public static final java.time.Duration RESTRICTED_VALUES_RETENTION =
            java.time.Duration.ofMinutes(10);

    public static final String RESTRICTED_VALUES = "RESTRICTED_VALUES";

    private final JdbcClient jdbc;

    /** 같은 관리자·같은 테이블의 미리보기 요청을 한 줄로 세운다(트랜잭션 끝까지). 같은 초안을 동시에 두 번 넣지 않게 한다. */
    public void lockRequester(long accountId, long resourceId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "source_preview:" + accountId + ":" + resourceId)
                .query()
                .singleRow();
    }

    public Optional<Accepted> findActiveSample(
            long resourceId, long accountId, int rowLimit, Instant now) {
        return findActive("SAMPLE", resourceId, accountId, "{}", rowLimit, now);
    }

    public Accepted insertSample(long resourceId, long accountId, int rowLimit, Instant now) {
        return insert("SAMPLE", resourceId, accountId, "{}", rowLimit, now);
    }

    /** 같은 관리자가 같은 테이블에 같은 초안·같은 행 수로 넣은, 대기·실행 중인 매핑 미리보기. */
    public Optional<Accepted> findActiveMappingPreview(
            long resourceId, long accountId, String requestJson, int rowLimit, Instant now) {
        return findActive("MAPPING_PREVIEW", resourceId, accountId, requestJson, rowLimit, now);
    }

    private Optional<Accepted> findActive(
            String kind,
            long resourceId,
            long accountId,
            String requestJson,
            int rowLimit,
            Instant now) {
        return jdbc.sql(
                        """
                        select id, status, requested_at, expires_at
                        from source_preview
                        where preview_kind = :kind and source_resource_id = :resource
                          and requested_by = :account and request = cast(:request as jsonb)
                          and row_limit = :rowLimit and status in ('QUEUED', 'RUNNING')
                          and expires_at > :now
                        order by id
                        limit 1
                        """)
                .param("kind", kind)
                .param("resource", resourceId)
                .param("account", accountId)
                .param("request", requestJson)
                .param("rowLimit", rowLimit)
                .param("now", Timestamp.from(now))
                .query(SourcePreviewRepository::accepted)
                .optional();
    }

    public Accepted insertMappingPreview(
            long resourceId, long accountId, String requestJson, int rowLimit, Instant now) {
        return insert("MAPPING_PREVIEW", resourceId, accountId, requestJson, rowLimit, now);
    }

    /** 같은 관리자가 같은 제안·컬럼으로 넣은, 대기·실행 중인 제한 값 확인(447). */
    public Optional<Accepted> findActiveRestrictedValues(
            long resourceId, long accountId, String requestJson, int rowLimit, Instant now) {
        return findActive(RESTRICTED_VALUES, resourceId, accountId, requestJson, rowLimit, now);
    }

    public Accepted insertRestrictedValues(
            long resourceId, long accountId, String requestJson, int rowLimit, Instant now) {
        return insert(
                RESTRICTED_VALUES,
                resourceId,
                accountId,
                requestJson,
                rowLimit,
                now,
                RESTRICTED_VALUES_RETENTION);
    }

    /** 결과를 읽어 간 제한 값 확인을 지운다(447). 이미 지워졌으면 false. */
    public boolean delete(long id) {
        return jdbc.sql("delete from source_preview where id = :id").param("id", id).update() > 0;
    }

    private Accepted insert(
            String kind,
            long resourceId,
            long accountId,
            String requestJson,
            int rowLimit,
            Instant now) {
        return insert(kind, resourceId, accountId, requestJson, rowLimit, now, RETENTION);
    }

    private Accepted insert(
            String kind,
            long resourceId,
            long accountId,
            String requestJson,
            int rowLimit,
            Instant now,
            java.time.Duration retention) {
        return jdbc.sql(
                        """
                        insert into source_preview (source_resource_id, preview_kind, request, row_limit,
                                                    requested_by, requested_at, expires_at)
                        values (:resource, :kind, cast(:request as jsonb), :rowLimit,
                                :account, :now, :expiresAt)
                        returning id, status, requested_at, expires_at
                        """)
                .param("kind", kind)
                .param("resource", resourceId)
                .param("request", requestJson)
                .param("rowLimit", rowLimit)
                .param("account", accountId)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(retention)))
                .query(SourcePreviewRepository::accepted)
                .single();
    }

    public Optional<Preview> findById(long id) {
        return jdbc.sql(
                        """
                        select id, source_resource_id, preview_kind, request::text as request, row_limit,
                               status, requested_by, requested_at, started_at, finished_at,
                               result::text as result, error_message, expires_at
                        from source_preview where id = :id
                        """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Preview(
                                        rs.getLong("id"),
                                        rs.getLong("source_resource_id"),
                                        rs.getString("preview_kind"),
                                        rs.getString("request"),
                                        rs.getInt("row_limit"),
                                        rs.getString("status"),
                                        rs.getLong("requested_by"),
                                        instant(rs, "requested_at"),
                                        instant(rs, "started_at"),
                                        instant(rs, "finished_at"),
                                        rs.getString("result"),
                                        rs.getString("error_message"),
                                        instant(rs, "expires_at")))
                .optional();
    }

    /** 실행 중인 미리보기를 결과와 함께 끝낸다. 이미 끝났거나 정리되었으면 아무것도 바꾸지 않는다. */
    public boolean finish(long id, String resultJson, Instant now) {
        return jdbc.sql(
                                """
                                update source_preview
                                set status = 'SUCCEEDED', result = cast(:result as jsonb), finished_at = :now
                                where id = :id and status = 'RUNNING'
                                """)
                        .param("result", resultJson)
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .update()
                > 0;
    }

    /** 결과를 지울 시각이 지난 미리보기를 지운다(원천 값이 들어 있다). */
    public int deleteExpired(Instant now) {
        return jdbc.sql("delete from source_preview where expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    private static Accepted accepted(ResultSet rs, int row) throws SQLException {
        return new Accepted(
                rs.getLong("id"),
                rs.getString("status"),
                instant(rs, "requested_at"),
                instant(rs, "expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Accepted(long id, String status, Instant requestedAt, Instant expiresAt) {}

    /**
     * @param request 요청 jsonb 텍스트. MAPPING_PREVIEW면 {@code {"mapping": 초안}}
     * @param result 결과 jsonb 텍스트. SUCCEEDED일 때만 있다
     */
    public record Preview(
            long id,
            long sourceResourceId,
            String previewKind,
            String request,
            int rowLimit,
            String status,
            long requestedBy,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            String result,
            String errorMessage,
            Instant expiresAt) {}
}

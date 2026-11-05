package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.ResourceKind;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.SourceCatalogReader.Catalog;
import com.axcore.platform.source.repository.SourceCatalogReader.Column;
import com.axcore.platform.source.repository.SourceCatalogReader.Resource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배치의 연결 검사가 플랫폼 DB에 쓰는 값: 원천 카탈로그(source_resource·source_column), 원천 연결 상태, 검사 작업 결과. 카탈로그는 SYNC
 * 계정 기준이다. 원천에서 사라진 표·컬럼은 지우지 않고(매핑이 가리킬 수 있다) 읽을 수 없음·없음으로 표시한다.
 */
@Repository
@RequiredArgsConstructor
public class SourceCatalogRepository {

    // 접속 설정·계정이 바뀌었는지 비교하는 값. data_source.row_version과 계정별 교체 시각·키 버전을 묶는다.
    private static final String CONNECTION_STAMP =
            """
            d.row_version || '|' || coalesce((
                select string_agg(cr.purpose || '@' || cr.rotated_at || '@' || cr.key_version, ','
                                  order by cr.purpose)
                from data_source_credential cr where cr.data_source_id = d.id), '')
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Optional<CheckTarget> findCheckTarget(long checkId) {
        return jdbc.sql(
                        """
                        select d.id, d.source_kind, d.connection_config, %s as stamp
                        from source_connection_check c join data_source d on d.id = c.data_source_id
                        where c.id = :checkId
                        """
                                .formatted(CONNECTION_STAMP))
                .param("checkId", checkId)
                .query(
                        (rs, row) ->
                                new CheckTarget(
                                        rs.getLong("id"),
                                        SourceKind.valueOf(rs.getString("source_kind")),
                                        json.readValue(
                                                rs.getString("connection_config"),
                                                new TypeReference<Map<String, Object>>() {}),
                                        rs.getString("stamp")))
                .optional();
    }

    /** 원천 행을 잠그고 현재 접속 설정·계정 값을 돌려준다. 검사 결과를 저장하는 동안 API가 접속 정보를 바꾸지 못한다. */
    public String lockConnectionStamp(long dataSourceId) {
        return jdbc.sql(
                        "select %s from data_source d where d.id = :id for update of d"
                                .formatted(CONNECTION_STAMP))
                .param("id", dataSourceId)
                .query(String.class)
                .single();
    }

    /**
     * SYNC 계정이 읽은 카탈로그를 반영한다. 수집 방식은 검사할 때마다 원천 publication에 맞춘다: publication에 든 표는 CDC, 나머지 표와 뷰는
     * FULL_COMPARE(V5). 이미 있는 행은 ID를 그대로 두고, 관리자가 등록한 source_metadata는 건드리지 않는다. 이번에 읽지 못한 표·뷰는
     * is_readable=false로, 원천 표에서 없어진 컬럼은 is_present=false로 바꾼다.
     */
    public void saveCatalog(long dataSourceId, Catalog catalog, Instant now) {
        Timestamp seenAt = Timestamp.from(now);
        List<Long> seenResourceIds = new ArrayList<>();
        for (Resource resource : catalog.resources()) {
            long resourceId = upsertResource(dataSourceId, resource, seenAt);
            seenResourceIds.add(resourceId);
            saveColumns(resourceId, resource, seenAt);
        }
        jdbc.sql(
                        """
                        update source_resource set is_readable = false
                        where data_source_id = :dataSourceId and resource_kind in ('TABLE', 'VIEW')
                          and is_readable and not (id = any(:seen))
                        """)
                .param("dataSourceId", dataSourceId)
                .param("seen", seenResourceIds.toArray(Long[]::new))
                .update();
    }

    /** 연결 상태와 마지막 검사 시각을 바꾼다. row_version도 올려, 같은 때 API가 고치던 원천은 낙관적 잠금으로 충돌하게 한다. */
    public void markConnection(long dataSourceId, ConnectionStatus status, Instant checkedAt) {
        jdbc.sql(
                        """
                        update data_source
                        set connection_status = :status, last_checked_at = :checkedAt,
                            row_version = row_version + 1
                        where id = :id
                        """)
                .param("status", status.name())
                .param("checkedAt", Timestamp.from(checkedAt))
                .param("id", dataSourceId)
                .update();
    }

    /** RUNNING인 검사만 SUCCEEDED로 바꾼다. result에는 비밀값을 넣지 않는다. */
    public void finishCheck(long checkId, Map<String, Object> result, Instant finishedAt) {
        jdbc.sql(
                        """
                        update source_connection_check
                        set status = 'SUCCEEDED', finished_at = :finishedAt, result = cast(:result as jsonb)
                        where id = :id and status = 'RUNNING'
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("result", json.writeValueAsString(result))
                .param("id", checkId)
                .update();
    }

    private long upsertResource(long dataSourceId, Resource resource, Timestamp seenAt) {
        // CDC를 계속 쓰는 표만 기준 LSN을 남긴다. CDC에서 빠지면 비우고(ck_source_resource_lsn), CDC로 새로 들어오면
        // 기준 LSN이 없어 다음 전체 읽기 전까지 이벤트를 버린다(V5).
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind, is_readable,
                                                     sync_strategy, last_seen_at)
                        values (:dataSourceId, :resourceKey, :kind, true, :strategy, :seenAt)
                        on conflict (data_source_id, resource_key) do update
                        set resource_kind = excluded.resource_kind,
                            is_readable = true,
                            last_seen_at = excluded.last_seen_at,
                            sync_strategy = excluded.sync_strategy,
                            loaded_lsn = case when excluded.sync_strategy = 'CDC'
                                               and source_resource.sync_strategy = 'CDC'
                                              then source_resource.loaded_lsn end
                        returning id
                        """)
                .param("dataSourceId", dataSourceId)
                .param("resourceKey", resource.resourceKey())
                .param("kind", resource.kind().name())
                .param("strategy", strategy(resource).name())
                .param("seenAt", seenAt)
                .query(Long.class)
                .single();
    }

    /**
     * 읽을 수 있는 컬럼을 넣거나 갱신한다. is_present=false는 원천 표에서 컬럼이 실제로 없어졌을 때만 쓴다. 권한만 빠진 컬럼은 삭제로 보지 않고 그대로
     * 둔다(source_column.is_present 설명).
     */
    private void saveColumns(long resourceId, Resource resource, Timestamp seenAt) {
        for (Column column : resource.columns()) {
            jdbc.sql(
                            """
                            insert into source_column (source_resource_id, column_name, source_data_type,
                                                       is_nullable, is_key_part, ordinal_position,
                                                       source_comment, is_present, last_seen_at)
                            values (:resourceId, :name, :dataType, :nullable, :keyPart, :position,
                                    :comment, true, :seenAt)
                            on conflict (source_resource_id, column_name) do update
                            set source_data_type = excluded.source_data_type,
                                is_nullable = excluded.is_nullable,
                                is_key_part = excluded.is_key_part,
                                ordinal_position = excluded.ordinal_position,
                                source_comment = excluded.source_comment,
                                is_present = true,
                                last_seen_at = excluded.last_seen_at
                            """)
                    .param("resourceId", resourceId)
                    .param("name", column.name())
                    .param("dataType", column.dataType())
                    .param("nullable", column.nullable())
                    .param("keyPart", column.keyPart())
                    .param("position", column.position())
                    .param("comment", column.comment(), Types.VARCHAR)
                    .param("seenAt", seenAt)
                    .update();
        }
        jdbc.sql(
                        """
                        update source_column set is_present = false
                        where source_resource_id = :resourceId and is_present
                          and not (column_name = any(:names))
                        """)
                .param("resourceId", resourceId)
                .param("names", resource.columnNames().toArray(String[]::new))
                .update();
    }

    /**
     * CDC publication에 든 표만 CDC다. 뷰와 publication 밖의 표(예: change_log)는 변경 이벤트가 오지 않으므로
     * FULL_COMPARE다.
     */
    private static SyncStrategy strategy(Resource resource) {
        return resource.kind() == ResourceKind.TABLE && resource.published()
                ? SyncStrategy.CDC
                : SyncStrategy.FULL_COMPARE;
    }

    /** 검사할 원천. stamp는 검사를 시작할 때의 접속 설정·계정 값이다. */
    public record CheckTarget(
            long dataSourceId,
            SourceKind sourceKind,
            Map<String, Object> connectionConfig,
            String stamp) {}
}

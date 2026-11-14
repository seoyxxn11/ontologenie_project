package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SyncStrategy;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceResourceRepository extends JpaRepository<SourceResource, Long> {

    Optional<SourceResource> findByIdAndDataSourceId(Long id, Long dataSourceId);

    /** 자원 ID만. 엔티티를 미리 읽어 두지 않아, 뒤에 잠그고 읽는 쪽(매핑 등록의 lockResource)이 최신 값을 본다. */
    @Query(
            "select r.id from SourceResource r where r.dataSourceId = :dataSourceId and r.resourceKey = :resourceKey")
    Optional<Long> findIdByDataSourceIdAndResourceKey(
            @Param("dataSourceId") Long dataSourceId, @Param("resourceKey") String resourceKey);

    /** 이 수집 방식의 표가 하나라도 있는 원천. CDC 소비 루프가 소비할 원천을 고른다(198-6). */
    @Query("select distinct r.dataSourceId from SourceResource r where r.syncStrategy = :strategy")
    List<Long> findDataSourceIdsBySyncStrategy(@Param("strategy") SyncStrategy strategy);

    /**
     * 마지막 연결 검사에서 SYNC 계정이 읽을 수 있던 원천 자원. 권한이 빠진 표는 지우지 않고 is_readable=false로 남으므로 거른다. 기동 때 원천 데이터
     * 정책 항목을 카탈로그와 견준다(405).
     */
    List<SourceResource> findByIsReadableTrue();

    /** CDC 이벤트의 표({스키마}.{이름})를 찾는다(198-5). */
    Optional<SourceResource> findByDataSourceIdAndResourceKey(
            Long dataSourceId, String resourceKey);

    /** 원천 하나의 CDC 테이블. 복구가 필요하면 모두 다시 읽기 대상으로 표시한다(198-5·199). */
    List<SourceResource> findByDataSourceIdAndSyncStrategy(
            Long dataSourceId, SyncStrategy syncStrategy);

    /**
     * 그 원천의 CDC 테이블을 모두 다시 읽기 대상으로 표시한다(복구 199·재연결, S15P31C106-364). 엔티티를 바꾸는 방식은 이미 비어 있는 행에
     * UPDATE를 보내지 않아 행을 잠그지 않는다. 그러면 그 사이 끝나는 전체 읽기 저장이 복구 표시를 못 보고 {@code loaded_lsn}을 채울 수 있다.
     * 그래서 값과 관계없이 모든 행을 갱신해 잠근다. 부르는 쪽은 먼저 {@code cdc_offset}을 잠근다(잠금 순서 cdc_offset →
     * source_resource).
     *
     * @return 표시한 테이블 수
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "update SourceResource r set r.loadedLsn = null"
                    + " where r.dataSourceId = :dataSourceId and r.syncStrategy = :strategy")
    int requireFullReloadOfTables(
            @Param("dataSourceId") Long dataSourceId, @Param("strategy") SyncStrategy strategy);

    /**
     * 한 테이블의 다시 읽기를 표시한다(loaded_lsn만 비움). CDC 반영이 적재할 수 없는 표를 건너뛸 때 쓴다(402). 엔티티 전체를 다시 쓰지 않아, 잠금
     * 전에 읽은 엔티티의 옛 값으로 다른 칸(읽기 가능 여부 등)을 되돌리지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "update SourceResource r set r.loadedLsn = null where r.id = :id and r.loadedLsn is not null")
    int requireFullReload(@Param("id") Long id);

    /** 매핑 변경과 수집이 같은 테이블을 동시에 다루지 않도록 행을 잠근다. 수집도 같은 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SourceResource r where r.id = :id")
    Optional<SourceResource> findForUpdate(@Param("id") Long id);

    /** 잠그기 전에 자원 키만 읽는다. 엔티티를 불러오지 않아 뒤의 잠금 조회가 최신 행을 읽는다(429). */
    @Query("select r.resourceKey from SourceResource r where r.id = :id")
    Optional<String> findResourceKey(@Param("id") Long id);

    /**
     * CDC 반영(198-5)이 쓸 원천 테이블 행을 ID 순서로 공유 잠금한다(PostgreSQL {@code FOR SHARE}). 전체 읽기 저장(197 {@code
     * complete})과 매핑 변경은 같은 행을 {@code FOR UPDATE}로 잡으므로, 원천 테이블 행 → 객체 행 순서가 같아져 교착이 생기지 않고 반영하는 동안
     * 매핑이 바뀌지 않는다. CDC 소비자끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from SourceResource r where r.id in :ids order by r.id")
    List<SourceResource> lockAllShared(@Param("ids") Collection<Long> ids);

    /** 한 원천의 자원 목록. 조건이 null이면 거르지 않고, pattern(ILIKE)은 resource_key에 건다. 이름순 (API 014). */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select * from source_resource r
                    where r.data_source_id = :dataSourceId
                      and (cast(:resourceKind as varchar) is null or r.resource_kind = :resourceKind)
                      and (cast(:isReadable as boolean) is null or r.is_readable = :isReadable)
                      and r.resource_key ilike :pattern
                    order by r.resource_key, r.id
                    """,
            countQuery =
                    """
                    select count(*) from source_resource r
                    where r.data_source_id = :dataSourceId
                      and (cast(:resourceKind as varchar) is null or r.resource_kind = :resourceKind)
                      and (cast(:isReadable as boolean) is null or r.is_readable = :isReadable)
                      and r.resource_key ilike :pattern
                    """)
    Page<SourceResource> search(
            @Param("dataSourceId") Long dataSourceId,
            @Param("resourceKind") String resourceKind,
            @Param("isReadable") Boolean isReadable,
            @Param("pattern") String pattern,
            Pageable pageable);
}

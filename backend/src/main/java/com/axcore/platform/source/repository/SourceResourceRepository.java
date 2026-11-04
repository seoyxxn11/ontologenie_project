package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceResource;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceResourceRepository extends JpaRepository<SourceResource, Long> {

    Optional<SourceResource> findByIdAndDataSourceId(Long id, Long dataSourceId);

    /** 매핑 변경과 수집이 같은 테이블을 동시에 다루지 않도록 행을 잠근다. 수집도 같은 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SourceResource r where r.id = :id")
    Optional<SourceResource> findForUpdate(@Param("id") Long id);

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

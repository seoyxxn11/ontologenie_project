package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkTypeRepository extends JpaRepository<LinkType, Long> {

    boolean existsByCode(String code);

    /** 코드·이름이 pattern(ILIKE)과 맞고 양 끝 유형 조건(null이면 무시)에 맞는 관계 유형. 등록 순서로 돌려준다 (API 030). */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select * from link_type l
                    where (l.code ilike :pattern or l.name ilike :pattern)
                      and (cast(:sourceTypeId as bigint) is null or l.source_type_id = :sourceTypeId)
                      and (cast(:targetTypeId as bigint) is null or l.target_type_id = :targetTypeId)
                    order by l.id
                    """,
            countQuery =
                    """
                    select count(*) from link_type l
                    where (l.code ilike :pattern or l.name ilike :pattern)
                      and (cast(:sourceTypeId as bigint) is null or l.source_type_id = :sourceTypeId)
                      and (cast(:targetTypeId as bigint) is null or l.target_type_id = :targetTypeId)
                    """)
    Page<LinkType> search(
            @Param("pattern") String pattern,
            @Param("sourceTypeId") Long sourceTypeId,
            @Param("targetTypeId") Long targetTypeId,
            Pageable pageable);

    /**
     * 수정·삭제 전 참조 확인용. FOR UPDATE는 이 행을 새로 참조하는 행의 FK 검사(FOR KEY SHARE)와 충돌하므로, 확인한 뒤 끝날 때까지 새
     * 규칙·연결이 끼어들지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LinkType l where l.id = :id")
    Optional<LinkType> findForUpdate(@Param("id") Long id);
}

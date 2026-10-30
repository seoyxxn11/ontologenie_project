package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.ObjectType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObjectTypeRepository extends JpaRepository<ObjectType, Long> {

    boolean existsByCode(String code);

    /** 코드·이름·동의어 중 하나가 pattern(ILIKE)과 맞는 유형. 등록 순서로 돌려준다 (API 021). */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select * from object_type t
                    where t.code ilike :pattern
                       or t.name ilike :pattern
                       or exists (select 1 from jsonb_array_elements_text(t.synonyms) s(synonym)
                                  where s.synonym ilike :pattern)
                    order by t.id
                    """,
            countQuery =
                    """
                    select count(*) from object_type t
                    where t.code ilike :pattern
                       or t.name ilike :pattern
                       or exists (select 1 from jsonb_array_elements_text(t.synonyms) s(synonym)
                                  where s.synonym ilike :pattern)
                    """)
    Page<ObjectType> search(@Param("pattern") String pattern, Pageable pageable);

    /** 삭제 전 참조 확인용. 행을 잠가 두면 이 유형을 새로 참조하는 행(FK 검사)이 삭제가 끝날 때까지 기다리므로, 확인과 삭제 사이에 참조가 끼어들지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ObjectType t where t.id = :id")
    Optional<ObjectType> findForUpdate(@Param("id") Long id);
}

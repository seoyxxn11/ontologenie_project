package com.axcore.platform.mapping.repository;

import com.axcore.platform.mapping.domain.PropertySourceMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertySourceMappingRepository
        extends JpaRepository<PropertySourceMapping, Long> {

    /** 객체 매핑으로 거른다. null이면 거르지 않는다. 등록 순서로 돌려준다 (API 037). */
    @Query(
            """
            select m from PropertySourceMapping m
            where (:sourceMappingId is null or m.sourceMappingId = :sourceMappingId)
            order by m.id
            """)
    Page<PropertySourceMapping> search(
            @Param("sourceMappingId") Long sourceMappingId, Pageable pageable);

    /** 같은 객체 매핑에 같은 속성의 매핑이 이미 있는지. excludeId가 null이면 제외하지 않는다. */
    @Query(
            """
            select count(m) > 0 from PropertySourceMapping m
            where m.sourceMappingId = :sourceMappingId
              and m.propertyDefinitionId = :propertyDefinitionId
              and (:excludeId is null or m.id <> :excludeId)
            """)
    boolean existsForProperty(
            @Param("sourceMappingId") Long sourceMappingId,
            @Param("propertyDefinitionId") Long propertyDefinitionId,
            @Param("excludeId") Long excludeId);
}

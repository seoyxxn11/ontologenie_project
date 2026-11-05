package com.axcore.platform.action.repository;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionKind;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionDefinitionRepository extends JpaRepository<ActionDefinition, Long> {

    Optional<ActionDefinition> findByCode(String code);

    boolean existsByCode(String code);

    /** API 087. 조건이 null이면 거르지 않는다. 코드 순. */
    @Query(
            """
            select d from ActionDefinition d
            where (:active is null or d.isActive = :active)
              and (:actionKind is null or d.actionKind = :actionKind)
            order by d.code, d.id
            """)
    Page<ActionDefinition> search(
            @Param("active") Boolean active,
            @Param("actionKind") ActionKind actionKind,
            Pageable pageable);
}

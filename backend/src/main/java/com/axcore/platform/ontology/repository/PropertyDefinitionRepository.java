package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.PropertyDefinition;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyDefinitionRepository extends JpaRepository<PropertyDefinition, Long> {

    List<PropertyDefinition> findByObjectTypeIdOrderById(Long objectTypeId);

    boolean existsByObjectTypeIdAndCode(Long objectTypeId, String code);

    boolean existsByIdAndObjectTypeId(Long id, Long objectTypeId);

    /** 삭제 전 참조 확인용. 객체 유형 삭제와 같은 이유로 행을 잠근다({@link ObjectTypeRepository#findForUpdate}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PropertyDefinition p where p.id = :id")
    Optional<PropertyDefinition> findForUpdate(@Param("id") Long id);
}

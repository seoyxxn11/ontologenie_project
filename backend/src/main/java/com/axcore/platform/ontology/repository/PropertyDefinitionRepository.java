package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
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

    Optional<PropertyDefinition> findByObjectTypeIdAndCode(Long objectTypeId, String code);

    boolean existsByIdAndObjectTypeId(Long id, Long objectTypeId);

    /**
     * 삭제 전 참조 확인용. 객체 유형 삭제와 같은 이유로 행을 잠근다({@link ObjectTypeRepository#findForUpdate}). 대표 속성
     * 지정(024)과 속성 수정(028)도 이 잠금으로 순서를 맞춘다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PropertyDefinition p where p.id = :id")
    Optional<PropertyDefinition> findForUpdate(@Param("id") Long id);

    /** 잠그기 전에 민감도만 읽는다. 엔티티를 불러오지 않아 뒤의 잠금 조회가 최신 행을 읽는다(401). */
    @Query("select p.sensitivity from PropertyDefinition p where p.id = :id")
    Optional<PropertySensitivity> findSensitivity(@Param("id") Long id);
}

package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMappingCondition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkMappingConditionRepository extends JpaRepository<LinkMappingCondition, Long> {

    List<LinkMappingCondition> findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
            Collection<Long> linkMappingIds);

    /** 속성이 현재 쓰는(ACTIVE) 연결 규칙의 조건 키인지. 연결 키는 민감 속성이 될 수 없다(384·391). */
    @Query(
            """
            select count(c) > 0 from LinkMappingCondition c, LinkMapping m
            where m.id = c.linkMappingId and m.status = com.axcore.platform.ontology.domain.LinkMappingStatus.ACTIVE
              and (c.sourcePropertyId = :propertyId or c.targetPropertyId = :propertyId)
            """)
    boolean existsActiveForProperty(@Param("propertyId") Long propertyId);
}

package com.axcore.platform.mapping.repository;

import com.axcore.platform.mapping.domain.PropertySourceMapping;
import java.util.Collection;
import java.util.List;
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

    /** 한 속성을 채우는 매핑. 속성 민감도의 하한(384·391)을 계산할 때 쓴다. */
    List<PropertySourceMapping> findByPropertyDefinitionId(Long propertyDefinitionId);

    /** 여러 객체 매핑의 속성 매핑. 전체 읽기(Jira 197)가 쓴다. */
    List<PropertySourceMapping> findBySourceMappingIdInOrderById(Collection<Long> sourceMappingIds);

    /**
     * 원천 표의 컬럼이 담기는 속성(403). 저장된 제안 근거의 예시 값이 지금 어느 유형·속성의 값인지 알아 조회 범위로 가릴 때 쓴다. 꺼진 매핑도 넣는다(가리는
     * 쪽으로). 표 키는 소문자로 비교한다.
     */
    @Query(
            """
            select distinct new com.axcore.platform.mapping.repository.MappedSourceColumn(
                lower(r.resourceKey), c.columnName, t.code, p.code)
            from PropertySourceMapping m
            join SourceColumn c on c.id = m.sourceColumnId
            join SourceResource r on r.id = m.sourceResourceId
            join PropertyDefinition p on p.id = m.propertyDefinitionId
            join ObjectType t on t.id = p.objectTypeId
            where lower(r.resourceKey) in :resourceKeys
            """)
    List<MappedSourceColumn> findMappedColumns(
            @Param("resourceKeys") Collection<String> resourceKeys);

    /**
     * 유형의 속성을 담는 원천 컬럼(403). 저장된 검사 표본의 원천 키({@code 컬럼 → 값})에서 민감 속성을 담는 키 컬럼을 찾을 때 쓴다. 꺼진 매핑도 넣는다.
     */
    @Query(
            """
            select distinct new com.axcore.platform.mapping.repository.MappedSourceColumn(
                lower(r.resourceKey), c.columnName, t.code, p.code)
            from PropertySourceMapping m
            join SourceColumn c on c.id = m.sourceColumnId
            join SourceResource r on r.id = m.sourceResourceId
            join PropertyDefinition p on p.id = m.propertyDefinitionId
            join ObjectType t on t.id = p.objectTypeId
            where t.code in :typeCodes
            """)
    List<MappedSourceColumn> findMappedColumnsOfTypes(
            @Param("typeCodes") Collection<String> typeCodes);
}

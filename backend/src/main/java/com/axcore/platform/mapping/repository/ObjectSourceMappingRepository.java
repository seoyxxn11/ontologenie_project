package com.axcore.platform.mapping.repository;

import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObjectSourceMappingRepository extends JpaRepository<ObjectSourceMapping, Long> {

    /** 조건이 null이면 거르지 않는다. 등록 순서로 돌려준다 (API 034). */
    @Query(
            """
            select m from ObjectSourceMapping m
            where (:sourceResourceId is null or m.sourceResourceId = :sourceResourceId)
              and (:objectTypeId is null or m.objectTypeId = :objectTypeId)
            order by m.id
            """)
    Page<ObjectSourceMapping> search(
            @Param("sourceResourceId") Long sourceResourceId,
            @Param("objectTypeId") Long objectTypeId,
            Pageable pageable);

    /** 같은 원천 자원·객체 유형의 다른 활성 매핑이 있는지. excludeId가 null이면 제외하지 않는다. */
    @Query(
            """
            select count(m) > 0 from ObjectSourceMapping m
            where m.sourceResourceId = :sourceResourceId and m.objectTypeId = :objectTypeId
              and m.active = true and (:excludeId is null or m.id <> :excludeId)
            """)
    boolean existsActive(
            @Param("sourceResourceId") Long sourceResourceId,
            @Param("objectTypeId") Long objectTypeId,
            @Param("excludeId") Long excludeId);

    /** 이 매핑으로 만든 객체가 있는지. 있으면 원천 식별을 바꿀 수 없다(DB 트리거와 같은 기준). */
    @Query(
            nativeQuery = true,
            value = "select exists(select 1 from object_instance where source_mapping_id = :id)")
    boolean hasObjects(@Param("id") Long id);

    /** 객체 유형에 켜진 매핑으로 연결된 원천 자원. 같은 자원은 한 번만, 번호 순으로 돌려준다. 정합성 검사가 규칙이 읽는 자원을 정할 때 쓴다(191). */
    @Query(
            """
            select distinct m.sourceResourceId from ObjectSourceMapping m
            where m.objectTypeId = :objectTypeId and m.active = true
            order by m.sourceResourceId
            """)
    List<Long> findActiveSourceResourceIds(@Param("objectTypeId") Long objectTypeId);

    /** 한 원천 자원의 켜진 매핑. 전체 읽기(Jira 197)가 이 매핑으로 객체를 만든다. */
    List<ObjectSourceMapping> findBySourceResourceIdAndActiveTrueOrderById(Long sourceResourceId);

    /** 원천 표를 담는 객체 유형(403). 제안 근거의 예시 값이 볼 수 없는 유형의 표에서 왔는지 볼 때 쓴다. 꺼진 매핑도 넣는다. 표 키는 소문자로 비교한다. */
    @Query(
            """
            select distinct new com.axcore.platform.mapping.repository.MappedSourceTable(
                lower(r.resourceKey), t.code)
            from ObjectSourceMapping m
            join SourceResource r on r.id = m.sourceResourceId
            join ObjectType t on t.id = m.objectTypeId
            where lower(r.resourceKey) in :resourceKeys
            """)
    List<MappedSourceTable> findMappedTables(
            @Param("resourceKeys") Collection<String> resourceKeys);
}

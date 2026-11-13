package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkMappingRepository extends JpaRepository<LinkMapping, Long> {

    List<LinkMapping> findByStatusOrderById(LinkMappingStatus status);

    /** 주어진 개정본 가운데 이 상태인 것(191 검사 접수: 반영 중인 규칙만 검사한다). */
    List<LinkMapping> findByIdInAndStatus(Collection<Long> ids, LinkMappingStatus status);

    Optional<LinkMapping> findByCodeAndStatus(String code, LinkMappingStatus status);

    Optional<LinkMapping> findTopByCodeOrderByRevisionDesc(String code);

    /** 규칙 코드의 개정본을 개정 번호 순으로(API 046). */
    Page<LinkMapping> findByCodeOrderByRevisionAsc(String code, Pageable pageable);

    /**
     * 규칙 코드마다 최신 개정본(규칙 목록). 조건이 null이면 거르지 않는다. status는 최신 개정본의 상태라, RETIRED는 대체 없이 폐기된 규칙만 나온다.
     */
    @Query(
            """
            select m from LinkMapping m
            where m.revision = (select max(x.revision) from LinkMapping x where x.code = m.code)
              and (:linkTypeId is null or m.linkTypeId = :linkTypeId)
              and (:status is null or m.status = :status)
            order by m.code
            """)
    List<LinkMapping> findLatestRevisions(
            @Param("linkTypeId") Long linkTypeId, @Param("status") LinkMappingStatus status);

    /**
     * 현재 쓰는 규칙의 출발·도착 조건({@code {property, equals}})이 이 유형의 속성을 가리키는지(391). 조건은 jsonb라 native로 읽는다.
     * 속성은 코드나 "유형코드.속성코드"로 저장된다.
     */
    @Query(
            value =
                    """
                    select exists (
                        select 1 from link_mapping m join link_type lt on lt.id = m.link_type_id
                        where m.status = 'ACTIVE'
                          and ((lt.source_type_id = :typeId
                                and m.source_filter ->> 'property' in (:code, :qualifiedCode))
                            or (lt.target_type_id = :typeId
                                and m.target_filter ->> 'property' in (:code, :qualifiedCode))))
                    """,
            nativeQuery = true)
    boolean existsActiveFilterOn(
            @Param("typeId") Long typeId,
            @Param("code") String code,
            @Param("qualifiedCode") String qualifiedCode);

    /** 규칙 코드의 특정 개정본(API 047). */
    Optional<LinkMapping> findByCodeAndRevision(String code, int revision);

    List<LinkMapping> findByStatusAndLinkTypeIdInOrderById(
            LinkMappingStatus status, Collection<Long> linkTypeIds);
}

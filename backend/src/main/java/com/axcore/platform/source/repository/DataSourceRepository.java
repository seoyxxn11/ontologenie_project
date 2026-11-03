package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    boolean existsByCode(String code);

    /** 조건이 null이면 거르지 않는다. 등록 순서로 돌려준다 (API 009). */
    @Query(
            """
            select s from DataSource s
            where (:sourceKind is null or s.sourceKind = :sourceKind)
              and (:connectionStatus is null or s.connectionStatus = :connectionStatus)
            order by s.id
            """)
    Page<DataSource> search(
            @Param("sourceKind") SourceKind sourceKind,
            @Param("connectionStatus") ConnectionStatus connectionStatus,
            Pageable pageable);
}

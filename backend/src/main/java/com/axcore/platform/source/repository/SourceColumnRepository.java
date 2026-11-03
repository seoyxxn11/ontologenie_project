package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceColumn;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceColumnRepository extends JpaRepository<SourceColumn, Long> {

    /** 원천에서의 컬럼 순서대로 (API 015). 사라진 컬럼(isPresent=false)도 함께 돌려준다. */
    List<SourceColumn> findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(Long sourceResourceId);
}

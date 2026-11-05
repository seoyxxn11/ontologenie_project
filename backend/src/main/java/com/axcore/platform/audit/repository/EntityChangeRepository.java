package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.EntityChange;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityChangeRepository extends JpaRepository<EntityChange, Long> {

    /** 실행 기록 하나가 바꾼 행. 남긴 순서대로다. */
    List<EntityChange> findByActionExecutionIdOrderByIdAsc(Long actionExecutionId);

    /** 여러 실행 기록이 바꾼 행을 한 번에 읽는다(목록 조회). 남긴 순서대로다. */
    List<EntityChange> findByActionExecutionIdInOrderByIdAsc(Collection<Long> actionExecutionIds);
}

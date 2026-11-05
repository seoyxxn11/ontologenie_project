package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.ActionExecution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {

    /** 091 중복 실행 방지. request_id는 UNIQUE다. */
    Optional<ActionExecution> findByRequestId(UUID requestId);
}

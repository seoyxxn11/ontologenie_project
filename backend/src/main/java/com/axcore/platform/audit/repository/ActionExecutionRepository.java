package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {}

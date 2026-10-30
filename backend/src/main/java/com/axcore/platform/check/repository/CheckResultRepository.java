package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CheckResultRepository
        extends JpaRepository<CheckResult, Long>, JpaSpecificationExecutor<CheckResult> {}

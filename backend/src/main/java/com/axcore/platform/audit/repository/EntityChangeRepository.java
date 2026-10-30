package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.EntityChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityChangeRepository extends JpaRepository<EntityChange, Long> {}

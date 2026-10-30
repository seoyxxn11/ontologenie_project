package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProposalItemRepository
        extends JpaRepository<ProposalItem, Long>, JpaSpecificationExecutor<ProposalItem> {}

package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProposalItemRepository
        extends JpaRepository<ProposalItem, Long>, JpaSpecificationExecutor<ProposalItem> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ProposalItem item where item.id = :id")
    Optional<ProposalItem> findForUpdate(@Param("id") long id);
}

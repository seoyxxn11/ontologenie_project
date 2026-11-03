package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalOption;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ProposalOptionRepository extends Repository<ProposalOption, Long> {

    List<ProposalOption> findByProposalItemIdAndRevisionOrderByOptionNo(
            long proposalItemId, int revision);

    @Query(
            value =
                    """
                    select exists (
                        select 1 from proposal_revision
                        where proposal_item_id = :proposalItemId and revision = :revision
                    )
                    """,
            nativeQuery = true)
    boolean existsRevision(
            @Param("proposalItemId") long proposalItemId, @Param("revision") int revision);
}

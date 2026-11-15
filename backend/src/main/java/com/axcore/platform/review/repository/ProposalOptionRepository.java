package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalOption;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 선택지는 만든 뒤 바꾸지 않으므로 저장·조회 메서드만 연다. */
public interface ProposalOptionRepository extends Repository<ProposalOption, Long> {

    <S extends ProposalOption> List<S> saveAllAndFlush(Iterable<S> options);

    boolean existsByIdAndProposalItemIdAndRevision(long id, long proposalItemId, int revision);

    List<ProposalOption> findByIdIn(List<Long> ids);

    Optional<ProposalOption> findByIdAndProposalItemIdAndRevision(
            long id, long proposalItemId, int revision);

    List<ProposalOption> findByProposalItemIdAndRevisionOrderByOptionNo(
            long proposalItemId, int revision);
}

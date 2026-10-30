package com.axcore.platform.review.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.LatestCheckResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalItemSpecifications;
import com.axcore.platform.review.repository.ProposalLatestCheckRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalService {

    private final ProposalItemRepository proposalItemRepository;
    private final ProposalLatestCheckRepository proposalLatestCheckRepository;

    public ProposalResponse getProposal(long id) {
        ProposalItem proposal =
                proposalItemRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(List.of(proposal));

        return toResponse(proposal, latestChecks.get(proposal.getId()));
    }

    public Page<ProposalResponse> search(ProposalSearchRequest request, Pageable pageable) {

        Page<ProposalItem> proposals =
                proposalItemRepository.findAll(
                        ProposalItemSpecifications.byFilters(
                                request.origin(),
                                request.itemKind(),
                                request.reviewStatus(),
                                request.applyStatus()),
                        pageable);

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(proposals.getContent());

        return proposals.map(proposal -> toResponse(proposal, latestChecks.get(proposal.getId())));
    }

    private Map<Long, LatestCheckResponse> loadLatestChecks(List<ProposalItem> proposals) {

        if (proposals.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> proposalRevisions =
                proposals.stream()
                        .collect(Collectors.toMap(ProposalItem::getId, ProposalItem::getRevision));

        return proposalLatestCheckRepository.findLatestByProposalRevisions(proposalRevisions);
    }

    private ProposalResponse toResponse(ProposalItem proposal, LatestCheckResponse latestCheck) {

        return new ProposalResponse(
                proposal.getId(),
                proposal.getAnalysisRunId(),
                proposal.getOrigin(),
                proposal.getItemKind(),
                proposal.getRevision(),
                proposal.getPayload(),
                proposal.getEvidence(),
                proposal.getAiConfidence(),
                proposal.getReviewStatus(),
                proposal.getApplyStatus(),
                proposal.getApplyError(),
                proposal.getAppliedAt(),
                latestCheck);
    }
}

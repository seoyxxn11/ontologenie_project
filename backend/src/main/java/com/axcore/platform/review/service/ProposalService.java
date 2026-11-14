package com.axcore.platform.review.service;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.domain.CandidateCheckTarget;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ProposalOption;
import com.axcore.platform.review.domain.ProposalRevision;
import com.axcore.platform.review.dto.LatestCheckResponse;
import com.axcore.platform.review.dto.ProposalDecisionResponse;
import com.axcore.platform.review.dto.ProposalOptionResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalRevisionResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalItemSpecifications;
import com.axcore.platform.review.repository.ProposalLatestCheckRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import com.axcore.platform.review.repository.ProposalRevisionRepository;
import com.axcore.platform.review.repository.ReviewDecisionRepository;
import com.axcore.platform.review.service.ProposalEvidenceScope.Shown;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제안 조회(목록, 049 상세, 선택지, 개정 이력, 020 분석별 제안). 근거의 예시 값은 보는 사람의 조회 범위로 가린다(403, {@link
 * ProposalEvidenceScope}). 가리지 않은 민감 예시 값을 내보내면 열람 기록(394)을 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalService {

    static final String SEARCH_VIEW = "PROPOSAL_SEARCH";
    static final String DETAIL_VIEW = "PROPOSAL_DETAIL";
    static final String OPTIONS_VIEW = "PROPOSAL_OPTIONS";
    static final String REVISIONS_VIEW = "PROPOSAL_REVISIONS";
    static final String ANALYSIS_VIEW = "ANALYSIS_PROPOSALS";
    static final String WRITE_VIEW = "PROPOSAL_WRITE";
    static final String TARGET_TYPE = "proposal_item";

    private final ProposalItemRepository proposalItemRepository;
    private final ProposalLatestCheckRepository proposalLatestCheckRepository;
    private final ProposalOptionRepository proposalOptionRepository;
    private final ProposalRevisionRepository proposalRevisionRepository;
    private final ReviewDecisionRepository reviewDecisionRepository;
    private final DataAccessPolicy dataAccessPolicy;
    private final ProposalEvidenceScope evidenceScope;
    private final RestrictedViewRecorder restrictedViews;

    public ProposalResponse getProposal(LoginAccount viewer, long id) {
        ProposalItem proposal =
                proposalItemRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(List.of(proposal));

        return responses(viewer, DETAIL_VIEW, List.of(proposal), latestChecks).getFirst();
    }

    public Page<ProposalResponse> search(
            LoginAccount viewer, ProposalSearchRequest request, Pageable pageable) {
        return search(viewer, SEARCH_VIEW, request, pageable, Specification.unrestricted());
    }

    public Page<ProposalResponse> searchByAnalysis(
            LoginAccount viewer,
            long analysisRunId,
            ProposalSearchRequest request,
            Pageable pageable) {
        return search(
                viewer,
                ANALYSIS_VIEW,
                request,
                pageable,
                (root, query, builder) -> builder.equal(root.get("analysisRunId"), analysisRunId));
    }

    private Page<ProposalResponse> search(
            LoginAccount viewer,
            String view,
            ProposalSearchRequest request,
            Pageable pageable,
            Specification<ProposalItem> scope) {
        Page<ProposalItem> proposals =
                proposalItemRepository.findAll(
                        ProposalItemSpecifications.byFilters(
                                        request.origin(),
                                        request.itemKind(),
                                        request.reviewStatus(),
                                        request.applyStatus())
                                .and(scope),
                        pageable);

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(proposals.getContent());

        return new PageImpl<>(
                responses(viewer, view, proposals.getContent(), latestChecks),
                proposals.getPageable(),
                proposals.getTotalElements());
    }

    public List<ProposalOptionResponse> getOptions(LoginAccount viewer, long id, Integer revision) {
        ProposalItem proposal =
                proposalItemRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));

        int targetRevision = revision == null ? proposal.getRevision() : revision;
        if (!proposalRevisionRepository.existsByProposalItemIdAndRevision(id, targetRevision)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of(
                            "resource", "proposal_revision",
                            "proposalItemId", id,
                            "revision", targetRevision));
        }

        List<ProposalOption> options =
                proposalOptionRepository.findByProposalItemIdAndRevisionOrderByOptionNo(
                        id, targetRevision);
        List<Map<String, Object>> evidence =
                show(viewer, OPTIONS_VIEW, id, options, ProposalOption::getEvidence);
        List<ProposalOptionResponse> responses = new ArrayList<>(options.size());
        for (int index = 0; index < options.size(); index++) {
            ProposalOption option = options.get(index);
            responses.add(
                    new ProposalOptionResponse(
                            option.getId(),
                            option.getOptionNo(),
                            option.getRevision(),
                            option.getPayload(),
                            evidence.get(index),
                            option.getAiConfidence()));
        }
        return responses;
    }

    public Page<ProposalRevisionResponse> getRevisions(
            LoginAccount viewer, long id, Pageable pageable) {
        if (!proposalItemRepository.existsById(id)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "proposal_item", "id", id));
        }

        Page<ProposalRevision> revisions =
                proposalRevisionRepository.findByProposalItemIdOrderByRevisionAsc(id, pageable);
        List<Map<String, Object>> evidence =
                show(
                        viewer,
                        REVISIONS_VIEW,
                        id,
                        revisions.getContent(),
                        ProposalRevision::getEvidence);
        List<ProposalRevisionResponse> responses = new ArrayList<>();
        for (int index = 0; index < revisions.getNumberOfElements(); index++) {
            ProposalRevision revision = revisions.getContent().get(index);
            responses.add(
                    new ProposalRevisionResponse(
                            revision.getRevision(),
                            revision.getPayload(),
                            evidence.get(index),
                            revision.getAiConfidence(),
                            revision.getCreatedBy(),
                            revision.getCreatedAt()));
        }
        return new PageImpl<>(responses, revisions.getPageable(), revisions.getTotalElements());
    }

    public Page<ProposalDecisionResponse> getDecisions(long id, Pageable pageable) {
        if (!proposalItemRepository.existsById(id)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "proposal_item", "id", id));
        }
        return reviewDecisionRepository
                .findByProposalItemIdOrderByDecidedAtDescIdDesc(id, pageable)
                .map(ProposalDecisionResponse::from);
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

    private List<ProposalResponse> responses(
            LoginAccount viewer,
            String view,
            List<ProposalItem> proposals,
            Map<Long, LatestCheckResponse> latestChecks) {
        List<Shown> shown =
                evidenceScope.show(
                        dataAccessPolicy.of(viewer),
                        proposals.stream().map(ProposalItem::getEvidence).toList());
        List<ProposalResponse> responses = new ArrayList<>(proposals.size());
        List<Seen> seen = new ArrayList<>();
        for (int index = 0; index < proposals.size(); index++) {
            ProposalItem proposal = proposals.get(index);
            responses.add(
                    toResponse(
                            proposal,
                            shown.get(index).evidence(),
                            latestChecks.get(proposal.getId())));
            seen.add(new Seen(TARGET_TYPE, proposal.getId(), shown.get(index).revealed()));
        }
        restrictedViews.record(viewer, view, seen);
        return responses;
    }

    /** 한 제안의 선택지·개정본 근거를 가린다. 열람 기록의 대상은 그 제안이다. */
    private <T> List<Map<String, Object>> show(
            LoginAccount viewer,
            String view,
            long proposalId,
            List<T> rows,
            Function<T, Map<String, Object>> evidence) {
        List<Shown> shown =
                evidenceScope.show(
                        dataAccessPolicy.of(viewer), rows.stream().map(evidence).toList());
        List<String> revealed =
                shown.stream()
                        .flatMap(item -> item.revealed().stream())
                        .distinct()
                        .sorted()
                        .toList();
        restrictedViews.record(viewer, view, List.of(new Seen(TARGET_TYPE, proposalId, revealed)));
        return shown.stream().map(Shown::evidence).toList();
    }

    private static ProposalResponse toResponse(
            ProposalItem proposal, Map<String, Object> evidence, LatestCheckResponse latestCheck) {
        // 검사 대상이 아닌 종류는 latestCheck를 주지 않는다. 이 정책 전에 접수돼 "자동 시험 미지원"으로 실패한 검사가 남아 있어도 승인과 무관하다.
        boolean checkRequired = CandidateCheckTarget.requiresCheck(proposal.getItemKind());
        return new ProposalResponse(
                proposal.getId(),
                proposal.getAnalysisRunId(),
                proposal.getOrigin(),
                proposal.getItemKind(),
                proposal.getRevision(),
                proposal.getPayload(),
                evidence,
                proposal.getAiConfidence(),
                proposal.getReviewStatus(),
                proposal.getApplyStatus(),
                proposal.getApplyError(),
                proposal.getAppliedAt(),
                checkRequired,
                checkRequired ? latestCheck : null);
    }
}

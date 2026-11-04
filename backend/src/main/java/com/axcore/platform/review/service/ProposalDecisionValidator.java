package com.axcore.platform.review.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ReviewErrorCode;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProposalDecisionValidator {
    private final ProposalItemRepository items;
    private final ProposalOptionRepository options;
    private final ProposalDecisionCheckRepository checks;

    /** Bean Validation을 통과한 결정 요청을 검증하고 잠긴 후보를 반환한다. 호출자는 같은 트랜잭션에서 결정 이력 저장·반영·감사를 마쳐야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProposalItem validate(
            long proposalId, ProposalDecisionRequest request, LoginAccount account) {
        if (account == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!"ADMIN".equals(account.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }

        ProposalItem item =
                items.findForUpdate(proposalId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "proposal_item",
                                                        "id",
                                                        proposalId)));

        if ("APPROVED".equals(item.getReviewStatus())
                || "REJECTED".equals(item.getReviewStatus())) {
            throw new BusinessException(
                    ReviewErrorCode.PROPOSAL_ALREADY_DECIDED,
                    Map.of("proposalId", proposalId, "reviewStatus", item.getReviewStatus()));
        }
        if (request.revision() != item.getRevision()) {
            throw new BusinessException(
                    ReviewErrorCode.REVISION_CONFLICT,
                    Map.of(
                            "field",
                            "revision",
                            "revision",
                            request.revision(),
                            "currentRevision",
                            item.getRevision()));
        }
        if (request.selectedOptionId() != null
                && !options.existsByIdAndProposalItemIdAndRevision(
                        request.selectedOptionId(), proposalId, item.getRevision())) {
            throw BusinessException.invalidField(
                    "selectedOptionId", "같은 후보·revision의 선택지를 지정해 주세요.");
        }

        if ("APPROVE".equals(request.decision())
                && !checks.hasSuccessfulCheck(proposalId, item.getRevision())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("revision", item.getRevision());
            checks.findLatestCheck(proposalId, item.getRevision())
                    .ifPresent(
                            check -> {
                                details.put("latestCheckRunId", check.checkRunId());
                                details.put("latestCheckStatus", check.status());
                            });
            throw new BusinessException(ReviewErrorCode.REVIEW_VALIDATION_FAILED, details);
        }
        return item;
    }
}

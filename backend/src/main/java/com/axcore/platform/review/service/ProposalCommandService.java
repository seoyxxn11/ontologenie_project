package com.axcore.platform.review.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.check.service.CandidateCheckService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ReviewErrorCode;
import com.axcore.platform.review.dto.ManualProposalCreateRequest;
import com.axcore.platform.review.dto.ProposalOptionCreateRequest;
import com.axcore.platform.review.dto.ProposalRevisionCreateRequest;
import com.axcore.platform.review.dto.ProposalWriteResponse;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalRevisionWriteRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProposalCommandService {
    private final ProposalItemRepository items;
    private final ProposalRevisionWriteRepository revisions;
    private final ProposalPayloadValidator validator;
    private final CandidateCheckService checks;
    private final AuditRecorder audits;

    public ProposalWriteResponse create(ManualProposalCreateRequest request, LoginAccount account) {
        if (!"LINK_RULE".equals(request.itemKind())) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "직접 추가는 연결 규칙(LINK_RULE)만 가능합니다. 정의·매핑은 직접 편집 화면에서 바꿔 주세요.",
                    Map.of("field", "itemKind", "allowed", List.of("LINK_RULE")));
        }
        Set<Long> types = validator.validate("LINK_RULE", request.payload(), null, "payload");
        ProposalItem item =
                items.saveAndFlush(ProposalItem.manual(request.payload(), request.evidence()));
        return saveRevision(item, List.of(), types, account, null);
    }

    public ProposalWriteResponse revise(
            long id, ProposalRevisionCreateRequest request, LoginAccount account) {
        ProposalItem item =
                items.findForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));
        if ("APPROVED".equals(item.getReviewStatus())
                || "REJECTED".equals(item.getReviewStatus())) {
            throw new BusinessException(
                    ReviewErrorCode.PROPOSAL_ALREADY_DECIDED,
                    Map.of("proposalId", id, "reviewStatus", item.getReviewStatus()));
        }
        if (request.baseRevision() != item.getRevision()) {
            throw new BusinessException(
                    ReviewErrorCode.REVISION_CONFLICT,
                    Map.of(
                            "field",
                            "baseRevision",
                            "baseRevision",
                            request.baseRevision(),
                            "currentRevision",
                            item.getRevision()));
        }
        Set<Long> types =
                new HashSet<>(
                        validator.validate(
                                item.getItemKind(),
                                request.payload(),
                                item.getAnalysisRunId(),
                                "payload"));
        for (int i = 0; i < request.options().size(); i++) {
            types.addAll(
                    validator.validate(
                            item.getItemKind(),
                            request.options().get(i).payload(),
                            item.getAnalysisRunId(),
                            "options[" + i + "].payload"));
        }
        Map<String, Object> before = metadata(item);
        item.revise(request.payload(), request.evidence());
        items.flush();
        return saveRevision(item, request.options(), types, account, before);
    }

    private ProposalWriteResponse saveRevision(
            ProposalItem item,
            List<ProposalOptionCreateRequest> options,
            Set<Long> types,
            LoginAccount account,
            Map<String, Object> before) {
        revisions.insert(item, options, account.id());
        long checkId =
                checks.enqueue(
                        item.getId(),
                        item.getRevision(),
                        item.getAnalysisRunId(),
                        types,
                        account.id());
        List<ChangeEntry> changes = new ArrayList<>();
        Map<String, Object> key = Map.of("id", item.getId());
        changes.add(
                before == null
                        ? ChangeEntry.insert("proposal_item", key, metadata(item))
                        : ChangeEntry.update("proposal_item", key, before, metadata(item)));
        changes.add(
                ChangeEntry.insert(
                        "proposal_revision",
                        Map.of("proposal_item_id", item.getId(), "revision", item.getRevision()),
                        Map.of("revision", item.getRevision(), "optionCount", options.size())));
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.CHECK_RUN)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType("check_run")
                        .targetId(checkId)
                        .input(
                                Map.of(
                                        "proposalItemId",
                                        item.getId(),
                                        "revision",
                                        item.getRevision()))
                        .result(Map.of("status", "QUEUED", "checkRunId", checkId))
                        .changes(changes)
                        .build());
        return ProposalWriteResponse.from(item, checkId);
    }

    private static Map<String, Object> metadata(ProposalItem item) {
        return Map.of(
                "revision",
                item.getRevision(),
                "origin",
                item.getOrigin(),
                "itemKind",
                item.getItemKind(),
                "reviewStatus",
                item.getReviewStatus(),
                "applyStatus",
                item.getApplyStatus());
    }
}

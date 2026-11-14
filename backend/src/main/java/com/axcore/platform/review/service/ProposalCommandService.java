package com.axcore.platform.review.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.domain.CandidateCheckTarget;
import com.axcore.platform.check.service.CandidateCheckService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ProposalOption;
import com.axcore.platform.review.domain.ProposalRevision;
import com.axcore.platform.review.domain.ReviewErrorCode;
import com.axcore.platform.review.dto.ManualProposalCreateRequest;
import com.axcore.platform.review.dto.ProposalOptionCreateRequest;
import com.axcore.platform.review.dto.ProposalRevisionCreateRequest;
import com.axcore.platform.review.dto.ProposalWriteResponse;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import com.axcore.platform.review.repository.ProposalRevisionRepository;
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
    private final ProposalRevisionRepository revisions;
    private final ProposalOptionRepository proposalOptions;
    private final ProposalPayloadValidator validator;
    private final CandidateCheckService checks;
    private final AuditRecorder audits;
    private final DataAccessPolicy dataAccessPolicy;
    private final ProposalEvidenceScope evidenceScope;

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
        // 화면은 가린 근거(049·선택지 응답)를 고쳐 통째로 보낸다. 가린 자리는 저장된 원본으로 되돌려 원본을 덮지 않는다(403).
        DataAccess access = dataAccessPolicy.of(account);
        Map<String, Object> evidence =
                evidenceScope.restore(access, item.getEvidence(), request.evidence());
        List<ProposalOptionCreateRequest> options = restoreOptions(access, item, request.options());
        Map<String, Object> before = metadata(item);
        item.revise(request.payload(), evidence);
        items.flush();
        return saveRevision(item, options, types, account, before);
    }

    /**
     * 선택지 근거도 같은 번호(지금 개정본의 선택지 순서)의 저장된 근거와 맞춰 가린 자리를 되돌린다. 화면은 선택지를 받은 순서대로 보낸다. 번호가 없는 새 선택지는
     * 그대로다.
     */
    private List<ProposalOptionCreateRequest> restoreOptions(
            DataAccess access, ProposalItem item, List<ProposalOptionCreateRequest> requested) {
        List<ProposalOption> stored =
                proposalOptions.findByProposalItemIdAndRevisionOrderByOptionNo(
                        item.getId(), item.getRevision());
        List<ProposalOptionCreateRequest> restored = new ArrayList<>(requested.size());
        for (int i = 0; i < requested.size(); i++) {
            ProposalOptionCreateRequest option = requested.get(i);
            restored.add(
                    i < stored.size()
                            ? new ProposalOptionCreateRequest(
                                    option.payload(),
                                    evidenceScope.restore(
                                            access, stored.get(i).getEvidence(), option.evidence()))
                            : option);
        }
        return restored;
    }

    private ProposalWriteResponse saveRevision(
            ProposalItem item,
            List<ProposalOptionCreateRequest> options,
            Set<Long> types,
            LoginAccount account,
            Map<String, Object> before) {
        // 선택지의 FK가 개정본을 가리키므로 개정본을 먼저 넣는다. 바로 뒤 검사 등록(CandidateCheckRepository, JdbcClient)이
        // 같은 트랜잭션에서 두 테이블을 SQL로 읽으므로 둘 다 flush한다.
        revisions.saveAndFlush(ProposalRevision.snapshot(item, account.id()));
        List<ProposalOption> newOptions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            ProposalOptionCreateRequest option = options.get(i);
            newOptions.add(ProposalOption.manual(item, i + 1, option.payload(), option.evidence()));
        }
        proposalOptions.saveAllAndFlush(newOptions);
        // 검사 대상이 아닌 종류는 후보 검사를 접수하지 않는다. 개정 기록은 proposal_revision(created_by)에 남는다.
        if (!CandidateCheckTarget.requiresCheck(item.getItemKind())) {
            return ProposalWriteResponse.from(item, null);
        }
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

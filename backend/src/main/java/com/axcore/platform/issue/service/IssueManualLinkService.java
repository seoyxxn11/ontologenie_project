package com.axcore.platform.issue.service;

import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.ActionDefinitionLookup;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.config.RequestIdFilter;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.dto.IssueManualLinkRequest;
import com.axcore.platform.issue.dto.IssueManualLinkResponse;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.ontology.dto.ConfirmedLinkRequest;
import com.axcore.platform.ontology.dto.SavedLink;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.service.ConfirmedLinkService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** API 058. 문제의 출발 객체를 수동으로 연결하고 변경을 감사에 남긴다. */
@Slf4j
@Service
public class IssueManualLinkService {

    private static final String ACTION = "CREATE_ISSUE_MANUAL_LINK";
    private static final String PERMISSION = "ISSUE_MANUAL_LINK";

    private final IssueRepository issues;
    private final LinkMappingRepository linkMappings;
    private final ConfirmedLinkService confirmedLinks;
    private final ActionReferenceRepository permissions;
    private final ActionDefinitionLookup actionDefinitions;
    private final AuditRecorder audits;
    private final TransactionTemplate transaction;

    public IssueManualLinkService(
            IssueRepository issues,
            LinkMappingRepository linkMappings,
            ConfirmedLinkService confirmedLinks,
            ActionReferenceRepository permissions,
            ActionDefinitionLookup actionDefinitions,
            AuditRecorder audits,
            PlatformTransactionManager transactions) {
        this.issues = issues;
        this.linkMappings = linkMappings;
        this.confirmedLinks = confirmedLinks;
        this.permissions = permissions;
        this.actionDefinitions = actionDefinitions;
        this.audits = audits;
        this.transaction = new TransactionTemplate(transactions);
    }

    public IssueManualLinkResponse create(
            long issueId, IssueManualLinkRequest request, LoginAccount account) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String reason = request.reason().strip();
        var audit =
                AuditEntry.builder()
                        .eventKind(EventKind.ACTION)
                        .entryPoint(EntryPoint.WEB)
                        .actor(Actor.user(account))
                        .requestId(requestId == null ? null : UUID.fromString(requestId))
                        .actionDefinitionId(actionDefinitions.idOf(ACTION))
                        .checkedPermissionCode(PERMISSION)
                        .targetType("issue")
                        .targetId(issueId)
                        .input(
                                Map.of(
                                        "issueId",
                                        issueId,
                                        "targetObjectId",
                                        request.targetObjectId(),
                                        "reason",
                                        reason,
                                        "rowVersion",
                                        request.rowVersion()));

        try {
            return transaction.execute(
                    status -> {
                        if (!permissions.roleHasPermission(account.roleCode(), PERMISSION)) {
                            throw new BusinessException(
                                    CommonErrorCode.FORBIDDEN,
                                    Map.of("requiredPermission", PERMISSION));
                        }
                        SavedLink saved = saveLink(issueId, request, account.id(), reason);
                        long auditId =
                                audits.record(
                                        audit.status(ExecutionStatus.SUCCEEDED)
                                                .result(
                                                        Map.of(
                                                                "linkId", saved.linkId(),
                                                                "outcome", saved.outcome()))
                                                .changes(List.of(saved.change()))
                                                .build());
                        return new IssueManualLinkResponse(
                                issueId, saved.linkId(), saved.outcome(), auditId);
                    });
        } catch (RuntimeException failure) {
            recordFailure(issueId, audit, failure);
            throw failure;
        }
    }

    private SavedLink saveLink(
            long issueId, IssueManualLinkRequest request, long accountId, String reason) {
        Issue issue =
                issues.findForUpdate(issueId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "issue", "id", issueId)));
        if (issue.getRowVersion() != request.rowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", issue.getRowVersion()));
        }
        if (issue.getIssueKind() != IssueKind.UNMATCHED
                || issue.getObjectInstanceId() == null
                || issue.getLinkMappingId() == null) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    Map.of("issueId", issueId, "reason", "출발 객체와 규칙이 있는 미연결 문제만 수동 연결할 수 있습니다."));
        }
        if (issue.getStatus() != IssueStatus.CHECKING) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT,
                    Map.of(
                            "currentStatus", issue.getStatus().name(),
                            "allowedStatuses", List.of(IssueStatus.CHECKING.name())));
        }

        long mappingId = issue.getLinkMappingId();
        // 발견 당시 개정본이 폐기됐어도 규칙 코드의 관계 유형은 같다.
        long linkTypeId =
                linkMappings
                        .findById(mappingId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "link_mapping",
                                                        "id",
                                                        mappingId)))
                        .getLinkTypeId();
        return confirmedLinks.saveManual(
                new ConfirmedLinkRequest(
                        linkTypeId,
                        issue.getObjectInstanceId(),
                        request.targetObjectId(),
                        accountId,
                        issue.getProposalItemId(),
                        Map.of("issueId", issueId, "reason", reason)));
    }

    private void recordFailure(
            long issueId, AuditEntry.AuditEntryBuilder audit, RuntimeException failure) {
        // 성공 기록 저장 중 실패했다면 롤백된 연결을 실패 기록의 변경 내역에 남기지 않는다.
        audit.changes(List.of());
        if (failure instanceof BusinessException business) {
            audit.status(ExecutionStatus.BLOCKED)
                    .blockedReason(business.getMessage())
                    .result(Map.of("errorCode", business.getErrorCode().code()));
        } else {
            audit.status(ExecutionStatus.FAILED)
                    .result(Map.of("errorCode", CommonErrorCode.INTERNAL_ERROR.code()));
        }
        try {
            audits.recordSeparately(audit.build());
        } catch (RuntimeException auditFailure) {
            log.error(
                    "수동 연결 실패 감사 저장 불가 issueId={} exception={}",
                    issueId,
                    auditFailure.getClass().getSimpleName());
        }
    }
}

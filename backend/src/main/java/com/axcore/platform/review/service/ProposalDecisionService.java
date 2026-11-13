package com.axcore.platform.review.service;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.config.RequestIdFilter;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplyService;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.dto.ProposalDecisionResult;
import com.axcore.platform.review.repository.ProposalDecisionAuditRepository;
import com.axcore.platform.review.repository.ProposalItemRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Service
@Validated
public class ProposalDecisionService {
    private static final String ACTION = "DECIDE_PROPOSAL";
    private static final String PERMISSION = "PROPOSAL_DECIDE";
    private final ProposalDecisionHistoryService history;
    private final ProposalItemRepository items;
    private final ProposalApplyPayloadResolver payloads;
    private final ProposalApplyService application;
    private final ProposalDecisionAuditRepository snapshots;
    private final ActionDefinitionRepository definitions;
    private final ActionReferenceRepository permissions;
    private final AuditRecorder audits;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProposalDecisionService(
            ProposalDecisionHistoryService history,
            ProposalItemRepository items,
            ProposalApplyPayloadResolver payloads,
            ProposalApplyService application,
            ProposalDecisionAuditRepository snapshots,
            ActionDefinitionRepository definitions,
            ActionReferenceRepository permissions,
            AuditRecorder audits,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.history = history;
        this.items = items;
        this.payloads = payloads;
        this.application = application;
        this.snapshots = snapshots;
        this.definitions = definitions;
        this.permissions = permissions;
        this.audits = audits;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** This entry point owns the commit; joining an outside business transaction is forbidden. */
    @Transactional(propagation = Propagation.NEVER)
    public ProposalDecisionResult decide(
            @Min(1) long proposalId,
            @NotNull @Valid ProposalDecisionRequest request,
            LoginAccount account) {
        if (account == null) throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        ActionDefinition definition =
                definitions
                        .findByCode(ACTION)
                        .orElseThrow(() -> new IllegalStateException("후보 결정 Action 정의가 없습니다."));
        Attempt attempt = new Attempt();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        UUID auditRequestId = requestId == null ? UUID.randomUUID() : UUID.fromString(requestId);
        Instant requestedAt = Instant.now(clock);
        try {
            return transaction.execute(
                    status ->
                            decideInTransaction(
                                    proposalId,
                                    request,
                                    account,
                                    definition,
                                    auditRequestId,
                                    requestedAt,
                                    attempt));
        } catch (RuntimeException failure) {
            Long failureAuditId =
                    recordFailure(
                            proposalId,
                            request,
                            account,
                            definition,
                            auditRequestId,
                            requestedAt,
                            attempt,
                            failure);
            if (failure instanceof BusinessException business) {
                if (failureAuditId == null) throw business;
                Map<String, Object> details = new LinkedHashMap<>(business.getDetails());
                details.put("actionExecutionId", failureAuditId);
                BusinessException enriched =
                        new BusinessException(
                                business.getErrorCode(), business.getMessage(), details);
                enriched.initCause(business);
                throw enriched;
            }
            log.error(
                    "후보 결정 실패 proposalId={} exception={}",
                    proposalId,
                    failure.getClass().getSimpleName());
            Map<String, Object> details = new LinkedHashMap<>();
            if (failureAuditId != null) details.put("actionExecutionId", failureAuditId);
            BusinessException unexpected =
                    new BusinessException(CommonErrorCode.INTERNAL_ERROR, details);
            unexpected.initCause(failure);
            throw unexpected;
        }
    }

    private ProposalDecisionResult decideInTransaction(
            long id,
            ProposalDecisionRequest request,
            LoginAccount account,
            ActionDefinition definition,
            UUID requestId,
            Instant requestedAt,
            Attempt attempt) {
        if (!"ADMIN".equals(account.roleCode())
                || !permissions.roleHasPermission(account.roleCode(), PERMISSION)) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", PERMISSION));
        }
        if (!definition.isActive()) {
            throw new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        }
        var recorded = history.recordValidated(id, request, account);
        long decisionId = recorded.decisionId();
        attempt.basisCheckRunId = recorded.basisCheckRunId();
        // 130 has already validated and locked this managed entity in this transaction.
        ProposalItem item = items.findById(id).orElseThrow();
        attempt.validationComplete = true;
        Map<String, Object> before = snapshots.proposal(id);
        ApplyResult applied = null;
        switch (request.decision()) {
            case "APPROVE" -> {
                applied =
                        application.apply(
                                new ApplyCommand(
                                        id,
                                        ProposalItemKind.valueOf(item.getItemKind()),
                                        ProposalOrigin.valueOf(item.getOrigin()),
                                        payloads.resolve(item, request.selectedOptionId()),
                                        account.id()));
                if (applied == null) {
                    throw new IllegalStateException("반영 서비스가 결과를 반환하지 않았습니다.");
                }
                item.approve(Instant.now(clock));
            }
            case "REJECT" -> item.reject();
            case "HOLD" -> item.hold();
            default -> throw new IllegalStateException("검증되지 않은 후보 결정입니다.");
        }
        items.flush();
        List<ChangeEntry> changes = new ArrayList<>();
        changes.add(
                ChangeEntry.insert(
                        "review_decision",
                        Map.of("id", decisionId),
                        snapshots.decision(decisionId)));
        Map<String, Object> after = snapshots.proposal(id);
        if (!before.equals(after)) {
            changes.add(ChangeEntry.update("proposal_item", Map.of("id", id), before, after));
        }
        List<Long> entityIds = applied == null ? List.of() : List.of(applied.targetId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionId", decisionId);
        result.put("basisCheckRunId", recorded.basisCheckRunId());
        result.put("revision", item.getRevision());
        result.put("decision", request.decision());
        result.put("reviewStatus", item.getReviewStatus());
        result.put("applyStatus", item.getApplyStatus());
        result.put("appliedEntityIds", entityIds);
        if (applied != null) {
            changes.addAll(applied.changes());
            result.put("targetTable", applied.targetTable());
            result.put("targetId", applied.targetId());
            result.put("reloadResourceIds", applied.reloadResourceIds());
            result.put("summary", applied.summary());
        }
        long auditId =
                audits.record(
                        entry(id, request, account, definition, requestId, requestedAt)
                                .status(ExecutionStatus.SUCCEEDED)
                                .result(result)
                                .changes(changes)
                                .build());
        items.flush();
        return new ProposalDecisionResult(
                decisionId,
                request.decision(),
                item.getReviewStatus(),
                item.getApplyStatus(),
                entityIds,
                auditId);
    }

    private Long recordFailure(
            long id,
            ProposalDecisionRequest request,
            LoginAccount account,
            ActionDefinition definition,
            UUID requestId,
            Instant requestedAt,
            Attempt attempt,
            RuntimeException failure) {
        boolean blocked = !attempt.validationComplete && failure instanceof BusinessException;
        String code =
                failure instanceof BusinessException business
                        ? business.getErrorCode().code()
                        : CommonErrorCode.INTERNAL_ERROR.code();
        String reason =
                failure instanceof BusinessException business
                        ? business.getMessage()
                        : CommonErrorCode.INTERNAL_ERROR.message();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorCode", code);
        result.put("reason", reason);
        result.put("basisCheckRunId", attempt.basisCheckRunId);
        try {
            return audits.recordSeparately(
                    entry(id, request, account, definition, requestId, requestedAt)
                            .status(blocked ? ExecutionStatus.BLOCKED : ExecutionStatus.FAILED)
                            .blockedReason(blocked ? reason : null)
                            .result(result)
                            .build());
        } catch (RuntimeException auditFailure) {
            log.error(
                    "후보 결정 실패 감사 저장 불가 proposalId={} original={} auditException={}",
                    id,
                    code,
                    auditFailure.getClass().getSimpleName());
            return null;
        }
    }

    private AuditEntry.AuditEntryBuilder entry(
            long id,
            ProposalDecisionRequest request,
            LoginAccount account,
            ActionDefinition definition,
            UUID requestId,
            Instant requestedAt) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("proposalItemId", id);
        input.put("revision", request.revision());
        input.put("decision", request.decision());
        input.put("selectedOptionId", request.selectedOptionId());
        input.put("reason", request.reason());
        return AuditEntry.builder()
                .eventKind(EventKind.ACTION)
                .actor(Actor.user(account))
                .entryPoint(EntryPoint.WEB)
                .requestId(requestId)
                .requestedAt(requestedAt)
                .actionDefinitionId(definition.getId())
                .checkedPermissionCode(PERMISSION)
                .targetType("proposal_item")
                .targetId(id)
                .input(input);
    }

    private static final class Attempt {
        private boolean validationComplete;
        private Long basisCheckRunId;
    }
}

package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.domain.IssueErrorCode;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.repository.IssueQueryRepository;
import com.axcore.platform.issue.repository.IssueStatusRepository;
import com.axcore.platform.issue.repository.IssueStatusRepository.LockedIssue;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 067 문제 처리 상태 변경. 상태와 이력을 한 트랜잭션에서 바꾼다.
 *
 * <p>해결은 재검증을 통과한 기록이 있을 때 관리자가 바꾼다. 재검증이 통과해도 자동으로 해결하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IssueStatusService {

    private final IssueStatusRepository issueStatusRepository;
    private final IssueQueryRepository issueQueryRepository;
    private final Clock clock;

    @Transactional
    public IssueResponse change(long issueId, IssueStatusChangeRequest request, long accountId) {
        IssueStatus target = IssueStatus.valueOf(request.status());
        requireRunOnlyForResolution(target, request.resolvedCheckRunId());

        LockedIssue issue =
                issueStatusRepository
                        .lock(issueId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "issue")));
        if (issue.rowVersion() != request.rowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", issue.rowVersion()));
        }
        if (!issue.status().canMoveTo(target)) {
            throw new BusinessException(
                    IssueErrorCode.INVALID_STATUS_TRANSITION,
                    Map.of(
                            "currentStatus",
                            issue.status().name(),
                            "requestedStatus",
                            target.name(),
                            "allowedStatuses",
                            issue.status().nextStatuses().stream().map(Enum::name).toList()));
        }
        if (target == IssueStatus.RESOLVED
                && !issueStatusRepository.isUsableReverification(
                        issueId, request.resolvedCheckRunId())) {
            throw new BusinessException(
                    IssueErrorCode.REVERIFICATION_REQUIRED,
                    Map.of(
                            "field", "resolvedCheckRunId",
                            "reason",
                                    "조치완료 뒤에 시작한 재검증(REVERIFY·SUCCEEDED)에서 이 문제가 PASSED인 기록이 없거나, 그 뒤 재검증에서 다시 실패함"));
        }

        Instant now = Instant.now(clock);
        issueStatusRepository.updateStatus(issueId, target, request.resolvedCheckRunId(), now);
        issueStatusRepository.insertHistory(
                issueId,
                issue.status(),
                target,
                request.reason(),
                request.resolvedCheckRunId(),
                accountId,
                now);
        return issueQueryRepository.findById(issueId).orElseThrow();
    }

    private static void requireRunOnlyForResolution(IssueStatus target, Long resolvedCheckRunId) {
        if (target == IssueStatus.RESOLVED && resolvedCheckRunId == null) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    Map.of("field", "resolvedCheckRunId", "reason", "RESOLVED로 바꿀 때는 필수입니다."));
        }
        if (target != IssueStatus.RESOLVED && resolvedCheckRunId != null) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    Map.of("field", "resolvedCheckRunId", "reason", "RESOLVED로 바꿀 때만 보냅니다."));
        }
    }
}

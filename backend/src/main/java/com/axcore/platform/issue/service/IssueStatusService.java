package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueErrorCode;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.issue.repository.IssueStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 067 문제 처리 상태 변경. 상태와 이력을 한 트랜잭션에서 바꾼다.
 *
 * <p>해결은 재검증을 통과한 기록이 있을 때 관리자가 바꾼다. 재검증이 통과해도 자동으로 해결하지 않는다. 재검증이 실패하면 검사가 확인중으로 되돌린다({@link
 * #reopenAfterFailedReverification}).
 *
 * <p>문제 행은 잠근 Entity로 바꾸고 rowVersion은 {@code @Version}이 한 번 올린다. 처리 이력은 JdbcClient로 넣으므로, 바꾼 뒤 바로
 * flush해 같은 트랜잭션의 SQL(이력 저장, 뒤에 이어지는 검사 SQL)이 바뀐 행을 보게 한다. 응답의 rowVersion도 flush 뒤 값이다.
 */
@Service
@RequiredArgsConstructor
public class IssueStatusService {

    private final IssueRepository issueRepository;
    private final IssueStatusRepository issueStatusRepository;
    private final Clock clock;
    private final IssueResponses responses;

    @Transactional
    public IssueResponse change(
            long issueId, IssueStatusChangeRequest request, LoginAccount actor) {
        long accountId = actor.id();
        IssueStatus target = IssueStatus.valueOf(request.status());
        requireRunOnlyForResolution(target, request.resolvedCheckRunId());

        Issue issue =
                issueRepository
                        .findForUpdate(issueId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "issue")));
        if (issue.getRowVersion() != request.rowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", issue.getRowVersion()));
        }
        IssueStatus from = issue.getStatus();
        if (!from.canMoveTo(target)) {
            throw new BusinessException(
                    IssueErrorCode.INVALID_STATUS_TRANSITION,
                    Map.of(
                            "currentStatus",
                            from.name(),
                            "requestedStatus",
                            target.name(),
                            "allowedStatuses",
                            from.nextStatuses().stream().map(Enum::name).toList()));
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
        if (target == IssueStatus.RESOLVED) {
            issue.resolve(request.resolvedCheckRunId());
        } else {
            issue.moveTo(target);
        }
        issueRepository.flush();
        issueStatusRepository.insertHistory(
                issueId,
                from,
                target,
                request.reason(),
                request.resolvedCheckRunId(),
                accountId,
                now);
        return responses.of(actor, "ISSUE_STATUS", issue);
    }

    /**
     * 재검증에서 다시 실패한 문제를 확인중으로 되돌린다. 재검증(135)이 이 문제의 FAILED 기록을 넣은 같은 트랜잭션에서 부른다.
     *
     * <p>검사가 바꾸는 상태는 이것 하나다. 해결은 사람만 한다. 아래 경우는 바꾸지 않고 false를 돌려준다.
     *
     * <ul>
     *   <li>문제가 조치완료·재검증대기가 아니다. 관리자가 아직 고쳤다고 하지 않았거나 이미 다른 상태다.
     *   <li>재검증이 조치완료 표시 뒤의 자료를 읽었다고 확인할 수 없다: 조치완료 전에 시작했거나, 데이터 기준의 FULL_COMPARE 표가 조치완료 전에 시작한
     *       전체 읽기의 자료다(376). 고치기 전 자료를 읽은 결과라 되돌릴 근거가 아니다. FAILED 검증 기록은 그대로 남는다.
     * </ul>
     *
     * <p>호출 조건: 같은 트랜잭션에서 이 메서드보다 먼저 Issue Entity를 읽었거나 issue 행을 SQL로 바꿨다면, 부르기 전에 flush하고 영속성
     * 컨텍스트를 비운다(clear). 그렇지 않으면 아래 잠금 조회가 DB를 다시 읽지 않고 이미 들고 있는 옛 값을 돌려준다(개발 가이드 9.3).
     *
     * @return 확인중으로 되돌렸으면 true
     * @throws IllegalStateException 이 재검증에 이 문제의 FAILED 기록이 없으면(호출 순서 오류)
     */
    @Transactional
    public boolean reopenAfterFailedReverification(long issueId, long checkRunId) {
        if (!issueStatusRepository.hasFailedVerification(issueId, checkRunId)) {
            throw new IllegalStateException(
                    "재검증 FAILED 기록이 없습니다: issueId=" + issueId + ", checkRunId=" + checkRunId);
        }
        Issue issue =
                issueRepository
                        .findForUpdate(issueId)
                        .orElseThrow(() -> new IllegalStateException("문제가 없습니다: " + issueId));
        if (issue.getStatus() != IssueStatus.FIXED_PENDING_REVERIFY
                || !issueStatusRepository.readDataAfterLastFix(issueId, checkRunId)) {
            return false;
        }

        Instant now = Instant.now(clock);
        issue.moveTo(IssueStatus.CHECKING);
        issueRepository.flush();
        issueStatusRepository.insertHistory(
                issueId,
                IssueStatus.FIXED_PENDING_REVERIFY,
                IssueStatus.CHECKING,
                null,
                checkRunId,
                null,
                now);
        return true;
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

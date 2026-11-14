package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueErrorCode;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.dto.IssueForwardRequest;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.repository.IssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 068 원천 담당자 전달 기록. 전달 시각과 기록한 관리자만 남긴다(FN-ISS-04).
 *
 * <p>플랫폼은 메시지를 보내지 않는다. 원천 수정이 끝났다는 뜻도 아니며 상태와 처리 이력은 그대로 둔다. 문제 행은 잠근 Entity로 바꾸고 rowVersion은
 * {@code @Version}이 올린다. 응답의 rowVersion이 바뀐 값이 되도록 flush한 뒤 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class IssueForwardService {

    private final IssueRepository issueRepository;
    private final Clock clock;
    private final IssueResponses responses;

    @Transactional
    public IssueResponse forward(long issueId, IssueForwardRequest request, LoginAccount actor) {
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
        // 해결된 문제는 더 고칠 것이 없다. 다시 생기면 새 문제로 등록된다.
        if (issue.getStatus() == IssueStatus.RESOLVED) {
            throw new BusinessException(
                    IssueErrorCode.ISSUE_ALREADY_RESOLVED,
                    Map.of("currentStatus", issue.getStatus().name()));
        }
        issue.forward(actor.id(), Instant.now(clock));
        issueRepository.flush();
        return responses.of(actor, "ISSUE_FORWARD", issue);
    }
}

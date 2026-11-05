package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueSearchRequest;
import com.axcore.platform.issue.repository.IssueQueryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 065·066 정합성 문제 목록과 근거 조회. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueQueryService {

    private final IssueQueryRepository issueQueryRepository;

    public Page<IssueResponse> search(IssueSearchRequest request, Pageable pageable) {
        return issueQueryRepository.search(request, pageable);
    }

    public IssueResponse getIssue(long id) {
        return issueQueryRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "issue")));
    }
}

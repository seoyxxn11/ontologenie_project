package com.axcore.platform.issue.service;

import com.axcore.platform.issue.domain.IssueRegistration;
import com.axcore.platform.issue.domain.NewIssue;
import com.axcore.platform.issue.repository.IssueWriteRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검사가 찾은 문제를 등록한다. 같은 키의 열린 문제가 있으면 새로 만들지 않고 그 ID를 돌려준다.
 *
 * <p>호출한 검사의 트랜잭션 안에서 실행된다. 검사 기록이 롤백되면 문제와 이력도 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class IssueRegistrationService {

    // 충돌한 열린 문제가 그 사이에 해결되면 다시 넣어 본다. 계속 엇갈리는 일은 없다고 보고 횟수를 둔다.
    private static final int MAX_ATTEMPTS = 3;

    private final IssueWriteRepository issueWriteRepository;

    @Transactional
    public IssueRegistration register(NewIssue issue) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<Long> createdId = issueWriteRepository.insertIfNoOpenIssue(issue);
            if (createdId.isPresent()) {
                issueWriteRepository.insertDetectedHistory(
                        createdId.get(), issue.detectedCheckRunId());
                return new IssueRegistration(createdId.get(), true);
            }
            Optional<Long> openId = issueWriteRepository.findOpenIssueId(issue.dedupeKey().value());
            if (openId.isPresent()) {
                return new IssueRegistration(openId.get(), false);
            }
        }
        throw new IllegalStateException(
                "열린 문제 등록이 계속 엇갈립니다: dedupeKey=" + issue.dedupeKey().value());
    }
}

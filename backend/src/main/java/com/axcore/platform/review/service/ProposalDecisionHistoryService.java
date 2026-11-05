package com.axcore.platform.review.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ProposalDecisionWriteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ProposalDecisionHistoryService {
    private final ProposalDecisionValidator validator;
    private final ProposalDecisionWriteRepository decisions;
    private final Clock clock;

    /** 호출자는 같은 트랜잭션에서 후보 상태 변경·반영·감사를 마쳐야 한다. 반환값은 승인 완료가 아닌 이력 ID다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long recordValidated(
            @Min(1) long proposalId,
            @NotNull @Valid ProposalDecisionRequest request,
            LoginAccount account) {
        ProposalItem item = validator.validate(proposalId, request, account);
        return decisions.insert(item, request, account.id(), Instant.now(clock));
    }
}

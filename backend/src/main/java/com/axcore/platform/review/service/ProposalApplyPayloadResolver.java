package com.axcore.platform.review.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import com.axcore.platform.review.repository.ProposalRevisionQueryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProposalApplyPayloadResolver {
    private final ProposalRevisionQueryRepository revisions;
    private final ProposalOptionRepository options;

    /** 호출자는 결정 검증을 마친 후보의 잠금을 같은 트랜잭션에서 유지해야 한다. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<String, Object> resolve(ProposalItem lockedItem, Long selectedOptionId) {
        if (selectedOptionId == null) {
            return revisions
                    .findPayload(lockedItem.getId(), lockedItem.getRevision())
                    .orElseThrow(() -> new IllegalStateException("후보의 불변 revision이 존재하지 않습니다."));
        }
        return options.findByIdAndProposalItemIdAndRevision(
                        selectedOptionId, lockedItem.getId(), lockedItem.getRevision())
                .orElseThrow(
                        () ->
                                BusinessException.invalidField(
                                        "selectedOptionId", "같은 후보·revision의 선택지를 지정해 주세요."))
                .getPayload();
    }
}

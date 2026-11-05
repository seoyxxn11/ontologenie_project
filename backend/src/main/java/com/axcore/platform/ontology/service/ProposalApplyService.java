package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인한 후보를 정의·매핑·규칙으로 반영한다 (FN-REV-06, Jira 189). 승인 흐름(Jira 131)이 연 트랜잭션 안에서만 돌고 따로 커밋하지 않는다. 반영 중
 * 예외가 나면 그 트랜잭션은 롤백만 할 수 있으므로, 호출하는 쪽은 같은 트랜잭션에서 예외를 잡고 이어 가지 않는다.
 */
@Service
public class ProposalApplyService {

    private final Map<ProposalItemKind, ProposalApplier> appliers;

    @Autowired
    public ProposalApplyService(ObjectProvider<ProposalApplier> appliers) {
        this(appliers.orderedStream().toList());
    }

    ProposalApplyService(List<ProposalApplier> appliers) {
        Map<ProposalItemKind, ProposalApplier> byKind = new EnumMap<>(ProposalItemKind.class);
        for (ProposalApplier applier : appliers) {
            if (byKind.putIfAbsent(applier.kind(), applier) != null) {
                throw new IllegalStateException("후보 종류마다 반영기는 하나입니다: " + applier.kind());
            }
        }
        this.appliers = byKind;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApplyResult apply(ApplyCommand command) {
        ProposalApplier applier = appliers.get(command.itemKind());
        if (applier == null) {
            throw new BusinessException(
                    ApplyErrorCode.APPLY_KIND_NOT_SUPPORTED,
                    Map.of("itemKind", command.itemKind().name()));
        }
        return applier.apply(command);
    }
}

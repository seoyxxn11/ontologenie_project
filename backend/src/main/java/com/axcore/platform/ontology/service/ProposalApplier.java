package com.axcore.platform.ontology.service;

import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;

/** 후보 종류 하나를 반영한다. 종류마다 구현체 하나를 빈으로 등록하면 {@link ProposalApplyService}가 찾아 쓴다. */
public interface ProposalApplier {

    ProposalItemKind kind();

    /** 호출하는 쪽 트랜잭션 안에서 반영한다. 실패하면 {@code BusinessException}을 던지고 커밋하지 않는다. */
    ApplyResult apply(ApplyCommand command);
}

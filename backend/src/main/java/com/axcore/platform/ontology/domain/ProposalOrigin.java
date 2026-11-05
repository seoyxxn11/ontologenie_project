package com.axcore.platform.ontology.domain;

/** 후보 출처. {@code proposal_item.origin} 값과 이름이 같고, 반영해 만든 정의·매핑·규칙의 출처를 정한다. */
public enum ProposalOrigin {
    AI(DefinitionOrigin.AI_PROPOSAL),
    MANUAL(DefinitionOrigin.MANUAL);

    private final DefinitionOrigin definitionOrigin;

    ProposalOrigin(DefinitionOrigin definitionOrigin) {
        this.definitionOrigin = definitionOrigin;
    }

    public DefinitionOrigin definitionOrigin() {
        return definitionOrigin;
    }
}

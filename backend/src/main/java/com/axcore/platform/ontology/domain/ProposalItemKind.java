package com.axcore.platform.ontology.domain;

/** 반영할 후보 종류. {@code proposal_item.item_kind} 값과 이름이 같다. */
public enum ProposalItemKind {
    OBJECT_TYPE,
    PROPERTY,
    LINK_TYPE,
    OBJECT_MAPPING,
    PROPERTY_MAPPING,
    LINK_RULE,
    DOCUMENT_LINK
}

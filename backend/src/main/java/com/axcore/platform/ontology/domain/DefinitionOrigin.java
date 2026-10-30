package com.axcore.platform.ontology.domain;

/** 정의·매핑의 생성 출처. 관리자가 직접 만들면 MANUAL, AI 제안을 승인해 만들면 AI_PROPOSAL이다. */
public enum DefinitionOrigin {
    AI_PROPOSAL,
    MANUAL,
    SEED
}

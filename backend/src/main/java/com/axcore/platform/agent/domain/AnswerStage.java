package com.axcore.platform.agent.domain;

/** 처리 중 답변의 단계 (정책 문서 3.1). answer_meta.stage에 이름 그대로 들어간다 */
public enum AnswerStage {
    INTERPRETING,
    SYNCING,
    QUERYING,
    ANSWERING
}

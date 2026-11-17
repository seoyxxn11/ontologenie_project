package com.axcore.platform.agent.service;

/**
 * 조회 도구 호출이 실패하거나 거절된 까닭의 종류(449). 조회 기록(action_execution AGENT_QUERY)에만 남기고 사용자에게 보이는 결과 문구에는 쓰지
 * 않는다. 운영에서 실패한 답변을 나중에 가릴 때 쓴다.
 */
public enum AgentFailureKind {
    /** AI가 보낸 입력이 잘못되어 조회하지 않았다. AI는 이유를 받아 고쳐 다시 부를 수 있다. */
    INVALID_INPUT,
    /** 이번 답변의 조회·도구 한도에 닿아 조회하지 않았다. */
    LIMIT,
    /** 이번 답변의 대상이 아닌 객체라 조회하지 않았다. */
    NOT_TARGET,
    /** 서버 오류·시간 초과처럼 조회를 끝내지 못했다. */
    ERROR
}

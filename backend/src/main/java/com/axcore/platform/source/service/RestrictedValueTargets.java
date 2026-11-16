package com.axcore.platform.source.service;

/**
 * 제안 검토의 제한 값 확인(S15P31C106-447) 요청이 아직 유효한지 묻는 곳. 원천을 읽는 배치({@link RestrictedValueJob})가 읽기 직전에
 * 부른다. 판단은 제안 쪽(review)이 하고, 원천 쪽은 이 인터페이스만 안다.
 */
public interface RestrictedValueTargets {

    /**
     * 제안이 아직 결정 전(대기·보류)이고, 요청할 때의 개정본 그대로이며, 그 원천 컬럼이 지금도 그 제안이 가리키는 제한 컬럼(지금의 원천 데이터 정책 기준)인가.
     * 하나라도 아니면 값을 읽거나 내보내지 않는다.
     */
    boolean stillEligible(long proposalId, int revision, long sourceResourceId, String column);
}

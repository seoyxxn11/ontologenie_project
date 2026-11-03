package com.axcore.platform.source.domain;

/**
 * 원천 테이블의 수집 방식(V5). CDC는 Debezium 변경 이벤트로 반영하고, FULL_COMPARE는 전체를 읽어 저장된 값과 비교한다. CDC 테이블도 첫 적재·매핑
 * 변경·복구 때는 전체를 다시 읽는다.
 */
public enum SyncStrategy {
    CDC,
    FULL_COMPARE
}

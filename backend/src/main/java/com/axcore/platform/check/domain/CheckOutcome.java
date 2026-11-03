package com.axcore.platform.check.domain;

/**
 * 출발 원천 행 하나의 최종 판정.
 *
 * <p>문제가 겹치면 조건 위반 > 중복 > 미연결 > 정상 순으로 판정한다.
 */
public enum CheckOutcome {
    MATCHED, // 정상: 조건 충족 시 도착이 ONE이면 1개, MANY이면 1개 이상
    UNMATCHED, // 미연결: 대응 대상 없음
    DUPLICATE, // 중복: 원천 키 중복 또는 허용 관계 수 초과. MANY도 원천 키 중복 검사
    VIOLATION // 조건 위반
}

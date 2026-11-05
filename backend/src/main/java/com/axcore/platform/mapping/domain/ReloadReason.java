package com.axcore.platform.mapping.domain;

/**
 * 매핑을 바꿔 다음 수집이 원천 테이블을 처음부터 다시 읽게 된 이유(API 036·038·039·101의 reloadReason). 다시 읽지 않으면 응답 값은 null이다.
 * 화면 문구는 FE가 이 값으로 정한다.
 */
public enum ReloadReason {
    /** 객체가 없는 객체 매핑의 원천 자원·유형·키를 바꿈(036). */
    IDENTITY_CHANGED,
    /** 멈춘 객체 매핑을 다시 켬(036). */
    REACTIVATED,
    /** 켜진 객체 매핑에 속성 매핑을 더함(038). */
    PROPERTY_MAPPING_ADDED,
    /** 켜진 객체 매핑의 속성 매핑을 바꿈(039). */
    PROPERTY_MAPPING_CHANGED,
    /** 켜진 객체 매핑의 속성 매핑을 지움(101). */
    PROPERTY_MAPPING_DELETED
}

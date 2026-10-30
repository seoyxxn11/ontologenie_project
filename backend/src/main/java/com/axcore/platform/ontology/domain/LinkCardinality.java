package com.axcore.platform.ontology.domain;

/** 관계 수 제약. 출발 객체 기준으로 읽는다(MANY_TO_ONE = 출발 여럿이 도착 하나에 연결). */
public enum LinkCardinality {
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    MANY_TO_MANY
}

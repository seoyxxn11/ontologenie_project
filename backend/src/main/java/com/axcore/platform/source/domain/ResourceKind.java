package com.axcore.platform.source.domain;

/** 원천 안의 자원 종류. CDC는 WAL에 변경이 남는 TABLE만 쓸 수 있다. */
public enum ResourceKind {
    TABLE,
    VIEW,
    DOCUMENT_SET,
    FILE
}

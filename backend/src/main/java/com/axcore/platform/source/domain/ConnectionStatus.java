package com.axcore.platform.source.domain;

/** 원천 연결 상태. 연결 검사(API 013)를 배치가 실행해 CONNECTED·FAILED로 바꾸고, 접속 설정을 바꾸면 UNVERIFIED로 돌아간다. */
public enum ConnectionStatus {
    UNVERIFIED,
    CONNECTED,
    FAILED
}

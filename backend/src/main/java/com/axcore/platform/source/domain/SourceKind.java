package com.axcore.platform.source.domain;

/** 원천 종류. POSTGRES만 DB에 접속하고(조회 계정·CDC), CSV·DOCUMENT는 파일·문서 묶음이라 접속 정보가 없다. */
public enum SourceKind {
    POSTGRES,
    CSV,
    DOCUMENT
}

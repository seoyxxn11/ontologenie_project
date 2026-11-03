package com.axcore.platform.source.domain;

/** 원천 접속 계정의 용도(data_source_credential.purpose). SYNC는 배치 수집·연결 검사, AI는 FastAPI 분석이 쓴다. */
public enum CredentialPurpose {
    SYNC,
    AI
}

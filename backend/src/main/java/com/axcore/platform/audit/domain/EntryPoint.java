package com.axcore.platform.audit.domain;

/** 기록이 어디로 들어왔는지 (API 공통 규칙 '로그' 절, 2026-10-30). */
public enum EntryPoint {
    /** 화면 요청. */
    WEB,
    /** Agent 대화에서 생긴 요청. */
    AGENT,
    /** 배치 컨테이너 안에서 생긴 일. 주기 수집, 수집 중 검사·자동 해소, 배치가 끝낸 작업의 결과. */
    BATCH,
    /** 백엔드(api) 안에서 사람 요청 없이 생긴 일. */
    SYSTEM,
    /** 다른 서비스가 내부 API로 알린 일. 예: 105 분석 완료. */
    INTERNAL
}

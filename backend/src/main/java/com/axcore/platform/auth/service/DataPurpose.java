package com.axcore.platform.auth.service;

/**
 * 데이터를 읽는 용도(S15P31C106-384). 같은 사람이라도 용도에 따라 받을 수 있는 값이 다르다. 역할 이름이 아니라 용도로 나누므로, 역할이 늘어도 이 구분은
 * 바뀌지 않는다.
 */
public enum DataPurpose {

    /** 사람이 화면·API 응답으로 본다. 민감(RESTRICTED) 값은 역할의 허용 행이 열어 줄 때만 본다. */
    VIEW,

    /**
     * 외부 언어 모델에 보낸다(Agent 도구 결과, Agent가 실행한 Action의 문구). 보이는 유형은 VIEW와 같지만, 민감 값은 역할과 관계없이 항상 가린다.
     */
    MODEL,

    /**
     * 시스템이 업무 조건을 검사하고 그 근거를 저장한다(재배정 계획 검사·스냅샷, S15P31C106-404). 보이는 유형은 VIEW와 같고, 민감 값은 역할과 관계없이
     * 가리지 않는다. 검사가 가려진 값을 "조건 없음"으로 보고 건너뛰지 않게 하려는 용도다. 이 범위로 읽은 값은 사람·모델에게 그대로 내보내지 않는다. 저장한 값은 보여
     * 줄 때 {@code AuditValueMasker}로 가리고, 문구에는 민감 값을 넣지 않는다({@link DataAccess#isRestricted}). 표시 이름은
     * 문구에 쓰이므로 모델 용도처럼 늘 가린 이름이다.
     */
    CHECK,

    /**
     * 정의(유형·속성·관계 이름)만 읽는 내부 호출(API 043 내부 모델 요약). 모든 유형과 민감 속성의 정의를 보지만, 객체 값은 읽을 수 없다({@link
     * DataAccess#requireObjectValues}).
     */
    DEFINITIONS
}

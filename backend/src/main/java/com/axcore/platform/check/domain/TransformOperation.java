package com.axcore.platform.check.domain;

/** 규칙의 값 변환 종류. 기존 AI 후보가 사용하는 변환 이름과 동일하게 유지한다. */
public enum TransformOperation {
    STRIP_PREFIX, // 지정한 접두어 제거
    ADD_PREFIX, // 지정한 접두어 추가
    PAD_ZEROS, // 지정한 자릿수까지 앞에 0 채우기
    UPPER, // 대문자로 변환
    TRIM, // 앞뒤 공백 제거
    EXTRACT_NUMBER, // 숫자 부분 추출
    TO_INTEGER, // 정수로 변환
    TO_DECIMAL, // 소수로 변환
    TO_DATE, // 날짜로 변환
    MAP_VALUE // 대응표에 지정된 값으로 변환
}

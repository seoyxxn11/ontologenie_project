package com.axcore.platform.audit.domain;

/**
 * 실행·감사 이력 화면의 종류 탭(API 096·080, 2026-11-03 결정). 서버가 기록마다 계산한다. ANALYSIS는 분석 실행, OPERATION은 업무
 * Action(action_mode=EXECUTE), CHANGE는 관리 Action(action_mode=RECORD), DATA_JOB은 수집·검사 등 작업이다.
 */
public enum ExecutionCategory {
    ANALYSIS,
    OPERATION,
    CHANGE,
    DATA_JOB
}

package com.axcore.platform.action.handler;

/**
 * 실행 기록의 대상(action_execution.target_type·target_id). type은 대상 테이블 이름이다. 실행 기록은 고치지 않고 객체별 실행 이력이 이
 * 값으로 조회하므로, 요청이 보낸 값이 아니라 Handler가 입력으로 정한다.
 */
public record ActionTarget(String type, long id) {

    public static final String OBJECT_INSTANCE = "object_instance";
}

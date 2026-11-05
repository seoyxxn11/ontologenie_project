package com.axcore.platform.action.domain;

/**
 * Action 정의의 실행 방식(action_definition.action_mode, Action 정의서 2절). EXECUTE는 091 실행 요청으로 Handler가
 * 실행하는 업무 Action이고, RECORD는 도메인 API가 직접 실행하며 감사 기록을 연결하려고 둔 관리 Action이다. 등록한 뒤 바꾸지 않는다.
 */
public enum ActionMode {
    EXECUTE,
    RECORD
}

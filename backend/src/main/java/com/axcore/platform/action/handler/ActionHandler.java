package com.axcore.platform.action.handler;

/**
 * 업무 Action(actionMode=EXECUTE)을 실행하는 코드. 091 실행 요청이 정의의 handlerKey와 같은 {@link #key()}를 가진 Bean을
 * 찾는다. Action 정의 등록·수정(088·090)은 Bean으로 등록된 키만 받는다. 실행 방법은 재배정 Handler(S15P31C106-209)와 함께 정한다.
 */
public interface ActionHandler {

    String key();
}

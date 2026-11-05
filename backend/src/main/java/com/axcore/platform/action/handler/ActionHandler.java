package com.axcore.platform.action.handler;

import java.util.Map;
import java.util.Optional;

/**
 * 업무 Action(actionMode=EXECUTE)을 실행하는 코드. 091 실행 요청이 정의의 handlerKey와 같은 {@link #key()}를 가진 Bean을
 * 찾는다. Action 정의 등록·수정(088·090)은 Bean으로 등록된 키만 받는다.
 *
 * <p>권한·활성·확인·입력 형식은 공통 실행기({@code ActionExecutionService})가 먼저 검사한다. Handler는 그 Action만의 업무 조건을
 * 검사하고 저장할 내용을 준비한다. 공통 실행기가 실행 기록과 같은 트랜잭션에서 {@link #prepare}를 부르고, 실행 기록을 남긴 뒤 준비한 저장을 실행한다.
 */
public interface ActionHandler {

    String key();

    /**
     * 실행 기록에 남길 대상. 입력에서 대상을 정할 수 없으면(형식이 틀린 입력 포함) 비어 있다. 공통 실행기가 실행 전 검사보다 먼저 부르므로 형식 검사를 통과하지 않은
     * 입력도 받을 수 있다.
     */
    default Optional<ActionTarget> target(Map<String, Object> input) {
        return Optional.empty();
    }

    /**
     * 업무 조건이 맞지 않으면 {@link ActionPreparation.Blocked}를, 맞으면 결과·바뀐 행·저장 함수를 담은 {@link
     * ActionPreparation.Ready}를 돌려준다.
     */
    ActionPreparation prepare(ActionContext context);
}

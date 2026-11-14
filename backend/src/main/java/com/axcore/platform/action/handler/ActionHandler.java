package com.axcore.platform.action.handler;

import java.util.Map;
import java.util.Optional;

/**
 * 업무 Action(actionMode=EXECUTE)을 실행하는 코드. 091 실행 요청이 정의의 handlerKey와 같은 {@link #key()}를 가진 Bean을
 * 찾는다. 정의는 이 Bean이 배포되기 전에도 등록할 수 있고, 그동안 091은 BLOCKED를 남긴다(handlerReady=false, Jira 287). 여러 정의가
 * 같은 키를 가리킬 수 있으므로 정의별 차이는 {@link ActionContext#definition()}으로 본다.
 *
 * <p>권한·활성·확인·입력 형식과 대상 객체 하나의 조회 범위는 공통 실행기({@code ActionExecutionService})가 먼저 검사한다. Handler는 그
 * Action만의 업무 조건을 검사하고 저장할 내용을 준비한다. 공통 실행기가 실행 기록과 같은 트랜잭션에서 {@link #prepare}를 부르고, 실행 기록을 남긴 뒤
 * 준비한 저장을 실행한다.
 *
 * <p>대상 밖의 객체를 더 읽는 Handler는 실행자의 조회 범위를 직접 적용해야 한다(S15P31C106-384·390). 객체는 공통 읽기 진입점({@code
 * ScopedObjectReader})으로 읽는다.
 *
 * <ul>
 *   <li>업무 조건 검사와 저장할 근거는 {@code DataAccessPolicy.forCheck(context.actor())}로 읽는다. 민감 값을 가리지 않은 원래
 *       값이라 가린 값(null)으로 검사가 건너뛰어지지 않는다(404).
 *   <li>차단 문구는 실행 기록에 저장되어 실행자·ADMIN·Agent(모델)가 본다. 보는 사람마다 가릴 수 없으므로 민감 속성 값을 넣지 않는다({@code
 *       DataAccess.isRestricted}).
 *   <li>입력·결과·변경 표의 자리를 {@code RecordedPropertyPaths}로 모두 알린다. 실행 응답(091)과 이력 조회(092·097·098)는 허용
 *       목록이라 알리지 않은 자리는 값이 가려지고, 알린 속성 자리는 보는 사람의 범위로 가려진다(Agent가 실행하면 모델 용도). 그래서 결과에 속성 값을 넣으려면 그
 *       자리를 속성 자리로 알린다.
 * </ul>
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

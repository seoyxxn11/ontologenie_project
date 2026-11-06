package com.axcore.platform.action.service;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.handler.ActionHandlerRegistry;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.global.security.LoginAccount;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 지금 실행할 수 있는 업무 Action 목록(Action 정의서 7절, Jira 303). Agent는 이 결과로만 Action Tool을 만든다.
 *
 * <p>조건은 업무 Action(EXECUTE), 사용 중, Handler 배포됨(handlerReady), 사용자 역할에 필요한 권한이 있음이다. 091 실행기({@link
 * ActionExecutionService})가 실행 전에 막는 조건과 같은 부품으로 판단해, Tool로 준 Action이 실행 단계에서 이 이유로 막히지 않게 한다. 권한이
 * 없는 사용자는 빈 목록을 받는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionCatalogService {

    private final ActionDefinitionRepository definitions;
    private final ActionReferenceRepository references;
    private final ActionHandlerRegistry handlers;

    public List<ExecutableAction> executableFor(LoginAccount actor) {
        Map<String, Boolean> permitted = new HashMap<>();
        return definitions
                .findByActionModeAndIsActiveTrueOrderByCodeAsc(ActionMode.EXECUTE)
                .stream()
                .filter(definition -> handlers.isRegistered(definition.getHandlerKey()))
                .filter(definition -> hasPermission(actor, definition, permitted))
                .map(ExecutableAction::from)
                .toList();
    }

    /** 같은 권한 코드를 여러 정의가 쓰면 한 번만 확인한다. */
    private boolean hasPermission(
            LoginAccount actor, ActionDefinition definition, Map<String, Boolean> permitted) {
        return permitted.computeIfAbsent(
                definition.getRequiredPermission(),
                permission -> references.roleHasPermission(actor.roleCode(), permission));
    }
}

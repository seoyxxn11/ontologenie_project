package com.axcore.platform.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionKind;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.domain.ActionTargetKind;
import com.axcore.platform.action.handler.ActionContext;
import com.axcore.platform.action.handler.ActionHandler;
import com.axcore.platform.action.handler.ActionHandlerRegistry;
import com.axcore.platform.action.handler.ActionPreparation;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Handler 배포 전후(Jira 303 완료 기준). 서버는 시작할 때 배포된 Handler로 {@link ActionHandlerRegistry}를 만든다. 같은 정의를
 * 배포 전 서버와 배포 후 서버에서 조회해, 정의를 고치지 않아도 배포 뒤에만 목록에 나오는지 본다.
 */
class ActionCatalogServiceDeployTests {

    private static final String HANDLER_KEY = "PENDING_HANDLER";
    private static final LoginAccount PLANNER = new LoginAccount(1L, "planner", "PLANNER", false);

    @Test
    void sameDefinitionAppearsOnlyAfterItsHandlerIsDeployed() {
        ActionDefinition definition =
                ActionDefinition.registerExecutable(
                        "PENDING_ACTION",
                        "구현 대기 Action",
                        "Handler를 나중에 배포하는 Action",
                        ActionTargetKind.SYSTEM,
                        null,
                        HANDLER_KEY,
                        Map.of("type", "object"),
                        ActionKind.WRITE,
                        false,
                        "PLAN_CREATE",
                        true);
        ActionDefinitionRepository definitions = mock(ActionDefinitionRepository.class);
        ActionReferenceRepository references = mock(ActionReferenceRepository.class);
        when(definitions.findByActionModeAndIsActiveTrueOrderByCodeAsc(ActionMode.EXECUTE))
                .thenReturn(List.of(definition));
        when(references.roleHasPermission("PLANNER", "PLAN_CREATE")).thenReturn(true);

        ActionCatalogService beforeDeploy =
                new ActionCatalogService(
                        definitions, references, new ActionHandlerRegistry(List.of()));
        ActionCatalogService afterDeploy =
                new ActionCatalogService(
                        definitions,
                        references,
                        new ActionHandlerRegistry(List.of(handler(HANDLER_KEY))));

        assertThat(beforeDeploy.executableFor(PLANNER)).isEmpty();
        assertThat(afterDeploy.executableFor(PLANNER))
                .extracting(ExecutableAction::code)
                .containsExactly("PENDING_ACTION");
        // 정의는 읽기만 하고 고치지 않는다.
        assertThat(definition.getHandlerKey()).isEqualTo(HANDLER_KEY);
        verify(definitions, times(2))
                .findByActionModeAndIsActiveTrueOrderByCodeAsc(ActionMode.EXECUTE);
        verifyNoMoreInteractions(definitions);
    }

    private static ActionHandler handler(String key) {
        return new ActionHandler() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public ActionPreparation prepare(ActionContext context) {
                return ActionPreparation.blocked("테스트");
            }
        };
    }
}

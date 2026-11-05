package com.axcore.platform.action.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 실행 준비 여부(handlerReady)는 저장하지 않고 서버에 배포된 Handler 목록으로 계산한다. */
class ActionHandlerRegistryTests {

    private static final String KEY = "PENDING_ACTION";

    @Test
    void sameKeyBecomesReadyOnceHandlerIsDeployed() {
        ActionHandlerRegistry beforeDeploy = new ActionHandlerRegistry(List.of());
        ActionHandlerRegistry afterDeploy = new ActionHandlerRegistry(List.of(handler(KEY)));

        assertThat(beforeDeploy.isRegistered(KEY)).isFalse();
        assertThat(beforeDeploy.find(KEY)).isEmpty();
        assertThat(afterDeploy.isRegistered(KEY)).isTrue();
        assertThat(afterDeploy.find(KEY)).isPresent();
    }

    @Test
    void recordActionWithoutKeyIsNotReady() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(handler(KEY)));

        assertThat(registry.isRegistered(null)).isFalse();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void rejectsTwoHandlersWithSameKey() {
        assertThatThrownBy(() -> new ActionHandlerRegistry(List.of(handler(KEY), handler(KEY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY);
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

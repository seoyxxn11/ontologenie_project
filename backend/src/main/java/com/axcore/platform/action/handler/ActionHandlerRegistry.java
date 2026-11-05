package com.axcore.platform.action.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 서버에 등록된 {@link ActionHandler}. 같은 키를 가진 Handler가 둘이면 어느 쪽을 실행할지 알 수 없어 시작하지 않는다.
 *
 * <p>정의는 Handler가 배포되기 전에도 등록할 수 있다. 실행 준비 여부(handlerReady)는 DB에 저장하지 않고 여기서 계산하므로, Handler를 배포하면
 * 정의를 고치지 않아도 실행할 수 있게 된다(Action 정의서 4-3, Jira 287).
 */
@Component
public class ActionHandlerRegistry {

    private final Map<String, ActionHandler> handlers;

    public ActionHandlerRegistry(List<ActionHandler> handlers) {
        this.handlers =
                handlers.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ActionHandler::key,
                                        Function.identity(),
                                        (first, second) -> {
                                            throw new IllegalStateException(
                                                    "같은 키를 가진 Action Handler가 둘입니다: "
                                                            + first.key());
                                        }));
    }

    /** 이 키의 Handler가 서버에 있는지. 관리 Action처럼 키가 없으면 false다. */
    public boolean isRegistered(String key) {
        return key != null && handlers.containsKey(key);
    }

    public Optional<ActionHandler> find(String key) {
        return Optional.ofNullable(key).map(handlers::get);
    }
}

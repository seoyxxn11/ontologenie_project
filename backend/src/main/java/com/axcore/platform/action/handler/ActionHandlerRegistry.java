package com.axcore.platform.action.handler;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 서버에 등록된 {@link ActionHandler}의 키 목록. */
@Component
public class ActionHandlerRegistry {

    private final Set<String> keys;

    public ActionHandlerRegistry(List<ActionHandler> handlers) {
        this.keys =
                handlers.stream().map(ActionHandler::key).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isRegistered(String key) {
        return keys.contains(key);
    }
}

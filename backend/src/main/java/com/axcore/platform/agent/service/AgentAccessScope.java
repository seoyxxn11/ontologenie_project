package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.repository.MessageScopeMark;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 과거 대화를 다시 쓸 때 지금 권한을 다시 적용한다(S15P31C106-413).
 *
 * <p>대화 답변·요약은 답할 때의 열람 범위로 만든 사본이다. 그래서 질문마다 그때의 범위(원천 데이터 정책 지문 + 보이는 유형마다 민감 값 열람 여부·민감 속성 목록)를
 * 질문 {@code context.accessScope}에 남긴다. 그 뒤 범위가 <b>줄었으면</b>(보이던 유형이 빠짐, 민감 값 열람 권한이 빠짐, 민감 속성이 늘어남,
 * 원천 정책이 바뀜) 그 답변은 화면에 다시 보여 주지 않고(084) 모델 문맥(최근 대화 원문·되묻기·이전 조건)에도 넣지 않는다. 새 유형을 승인하는 것처럼 범위가
 * 넓어지기만 했으면 그대로 쓴다. 요약은 만들 때의 범위를 따로 저장해 같은 기준으로 판단한다.
 *
 * <p>범위가 없는 예전 답변(이 기능 전)은 그대로 쓴다. 2026-11-14에 그 전 대화의 금지 값은 따로 지웠다. 한계: 답변 문장 안의 값이 줄어든 범위에 실제로
 * 걸리는지는 보지 않고 범위가 줄었다는 것만 본다(넘치게 가리는 쪽).
 */
@Component
@RequiredArgsConstructor
public class AgentAccessScope {

    /** 질문 context에서 범위를 담는 키. 응답(084·085)에서는 뺀다. */
    public static final String CONTEXT_KEY = "accessScope";

    /** 범위가 줄어 다시 보여 주지 않는 답변 자리의 문장. */
    static final String WITHDRAWN_CONTENT =
            "권한이나 데이터 정책이 바뀌기 전에 만든 답변이라 다시 보여 주지 않습니다. 같은 질문을 다시 해 주세요.";

    private static final String POLICY = "policy";
    private static final String TYPES = "types";
    private static final String RESTRICTED_VIEW = "restrictedView";
    private static final String RESTRICTED = "restricted";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DataAccessPolicy dataAccessPolicy;
    private final SourceColumnPolicy sourceColumnPolicy;

    /** 이 사용자의 지금 범위. 화면 열람 범위(VIEW) 기준이다. */
    public Map<String, Object> current(LoginAccount asker) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put(POLICY, sourceColumnPolicy.fingerprint());
        scope.put(TYPES, dataAccessPolicy.of(asker).visibleScopes());
        return scope;
    }

    /** 질문 context에 범위를 더한 사본. */
    static Map<String, Object> stamped(Map<String, Object> context, Map<String, Object> scope) {
        Map<String, Object> copy = new LinkedHashMap<>(context == null ? Map.of() : context);
        copy.put(CONTEXT_KEY, scope);
        return copy;
    }

    /**
     * then 범위에서 만든 답을 now 범위에서 다시 쓰면 안 되는가. then이 없으면(이 기능 전) false다. 원천 정책이 바뀌었거나, then에 보이던 유형이
     * now에 없거나, now에서 민감 값을 볼 수 없는데 then에 볼 수 있었거나 민감 속성이 늘었으면 true다.
     */
    static boolean narrowed(Object then, Map<String, Object> now) {
        if (!(then instanceof Map<?, ?> before)) {
            return false;
        }
        if (!Objects.equals(before.get(POLICY), now.get(POLICY))) {
            return true;
        }
        Map<?, ?> nowTypes = map(now.get(TYPES));
        for (Map.Entry<?, ?> entry : map(before.get(TYPES)).entrySet()) {
            Object current = nowTypes.get(entry.getKey());
            if (current == null) {
                return true;
            }
            Map<?, ?> was = map(entry.getValue());
            Map<?, ?> is = map(current);
            if (Boolean.TRUE.equals(is.get(RESTRICTED_VIEW))) {
                continue;
            }
            if (Boolean.TRUE.equals(was.get(RESTRICTED_VIEW))
                    || !codes(was).containsAll(codes(is))) {
                return true;
            }
        }
        return false;
    }

    /** 오래된 순 메시지에서, 질문할 때보다 지금 범위가 줄어든 질문에 대한 답변 ID. */
    static Set<Long> withdrawnAnswers(List<MessageScopeMark> oldestFirst, Map<String, Object> now) {
        Set<Long> withdrawn = new LinkedHashSet<>();
        Object scope = null;
        for (MessageScopeMark message : oldestFirst) {
            if (message.sender() == MessageSender.USER) {
                scope = scopeOf(message.context());
            } else if (narrowed(scope, now)) {
                withdrawn.add(message.id());
            }
        }
        return withdrawn;
    }

    /** {@link #withdrawnAnswers}를 이미 읽은 메시지로. */
    static Set<Long> withdrawnAnswersIn(List<AgentMessage> oldestFirst, Map<String, Object> now) {
        return withdrawnAnswers(
                oldestFirst.stream()
                        .map(m -> new MessageScopeMark(m.getId(), m.getSender(), m.getContext()))
                        .toList(),
                now);
    }

    /** 오래된 순 메시지에서 가장 최근 질문의 범위. 없거나 이 기능 전 질문이면 null. */
    static Map<String, Object> latestScope(List<AgentMessage> oldestFirst) {
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            AgentMessage message = oldestFirst.get(i);
            if (message.getSender() == MessageSender.USER) {
                return scopeOf(message.getContext()) instanceof Map<?, ?> scope
                        ? stringKeys(scope)
                        : null;
            }
        }
        return null;
    }

    /** 다시 쓰지 않을 답변과 그 바로 앞 질문을 뺀 목록. 질문·답변 짝이 어긋나지 않게 둘을 같이 뺀다. */
    static List<AgentMessage> withoutWithdrawnTurns(
            List<AgentMessage> oldestFirst, Set<Long> withdrawn) {
        if (withdrawn.isEmpty()) {
            return oldestFirst;
        }
        List<AgentMessage> kept = new ArrayList<>();
        for (AgentMessage message : oldestFirst) {
            if (withdrawn.contains(message.getId())) {
                if (!kept.isEmpty() && kept.getLast().getSender() == MessageSender.USER) {
                    kept.removeLast();
                }
                continue;
            }
            kept.add(message);
        }
        return kept;
    }

    /** 요약과 함께 저장할 범위 글. 범위가 없으면(이 기능 전 대화) null. */
    static String toText(Map<String, Object> scope) {
        return scope == null ? null : JSON.writeValueAsString(scope);
    }

    /** 요약을 만든 범위(저장한 글)에서 지금 범위가 줄지 않았으면 그 요약을 쓸 수 있다. 범위 없이 만든 예전 요약은 쓴다. 읽을 수 없는 글이면 쓰지 않는다. */
    static boolean summaryUsable(String summaryScope, Map<String, Object> now) {
        if (summaryScope == null) {
            return true;
        }
        try {
            return !narrowed(
                    JSON.readValue(summaryScope, new TypeReference<Map<String, Object>>() {}), now);
        } catch (JacksonException unreadable) {
            return false;
        }
    }

    private static Object scopeOf(Map<String, Object> context) {
        return context == null ? null : context.get(CONTEXT_KEY);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Set<Object> codes(Map<?, ?> type) {
        return type.get(RESTRICTED) instanceof Collection<?> codes
                ? new HashSet<>(codes)
                : Set.of();
    }

    private static Map<String, Object> stringKeys(Map<?, ?> scope) {
        Map<String, Object> copy = new LinkedHashMap<>();
        scope.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}

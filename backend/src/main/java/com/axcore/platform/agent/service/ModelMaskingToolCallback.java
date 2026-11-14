package com.axcore.platform.agent.service;

import com.axcore.platform.auth.service.ModelTextMasker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Agent 도구 결과를 외부 모델에 넘기기 전에 자유 글의 이름·전화번호 등을 가리는 감싸개(S15P31C106-414). 민감 속성 값은 도구가 이미 MODEL 용도로
 * 가렸고({@code DataAccess}), 여기서는 일반 속성 안의 글에 섞인 값을 {@link ModelTextMasker}로 한 번 더 가린다. 모델로 가는 도구 결과
 * 하나에서 가리므로 새 도구를 더해도 빠지지 않는다.
 *
 * <p>도구 결과는 보통 JSON이다. JSON 글자 그대로 가리면 줄바꿈이 {@code \n}으로 바뀌어 그 뒤 전화번호가 코드의 일부로 보이므로, JSON이면 문자열 값마다
 * 원문으로 가린 뒤 다시 JSON으로 쓴다.
 */
final class ModelMaskingToolCallback implements ToolCallback {

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    static final String UNREADABLE_INPUT_LABEL = "도구 입력 확인";

    private final ToolCallback delegate;
    private final AgentQuerySession session;

    private ModelMaskingToolCallback(ToolCallback delegate, AgentQuerySession session) {
        this.delegate = delegate;
        this.session = session;
    }

    /**
     * 도구 객체({@code @Tool} 메서드를 가진 객체), {@link ToolCallback}, 그 목록·배열을 모두 감싼 콜백 목록으로 바꾼다. {@code
     * ChatClient.tools}가 받던 것과 같은 모양을 받는다.
     */
    static ToolCallback[] wrapAll(Object... tools) {
        return wrapAllRecording(null, tools);
    }

    /** 위와 같고, 도구 입력을 읽지 못한 호출을 이번 답변의 조회 기록에 실패로 남긴다. session이 null이면 기록하지 않고 고칠 방법만 돌려준다. */
    static ToolCallback[] wrapAllRecording(AgentQuerySession session, Object... tools) {
        List<ToolCallback> wrapped = new ArrayList<>();
        for (Object tool : tools) {
            collect(tool, wrapped, session);
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    private static void collect(Object tool, List<ToolCallback> out, AgentQuerySession session) {
        if (tool instanceof ToolCallback callback) {
            out.add(new ModelMaskingToolCallback(callback, session));
        } else if (tool instanceof ToolCallback[] callbacks) {
            for (ToolCallback callback : callbacks) {
                out.add(new ModelMaskingToolCallback(callback, session));
            }
        } else if (tool instanceof Collection<?> items) {
            for (Object item : items) {
                collect(item, out, session);
            }
        } else {
            for (ToolCallback callback : ToolCallbacks.from(tool)) {
                out.add(new ModelMaskingToolCallback(callback, session));
            }
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        try {
            return masked(delegate.call(toolInput));
        } catch (RuntimeException ex) {
            return unreadableInput(toolInput, ex);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            return masked(delegate.call(toolInput, toolContext));
        } catch (RuntimeException ex) {
            return unreadableInput(toolInput, ex);
        }
    }

    /**
     * AI가 보낸 입력을 도구 인자 형식으로 바꾸지 못했으면(예: 문자열 자리에 목록) Java 오류 원문 대신 고칠 방법을 돌려주고 실패로 기록한다. 원문은 내부 클래스
     * 이름을 담고, 기록이 없어 답변 출처에도 남지 않았다(2026-11-14 대화 103). 입력 형식 오류가 아니면 그대로 던진다.
     */
    private String unreadableInput(String toolInput, RuntimeException ex) {
        MismatchedInputException mismatch = mismatchedInput(ex);
        if (mismatch == null) {
            throw ex;
        }
        String name = delegate.getToolDefinition().name();
        String reason =
                "입력 형식이 도구 설명과 달라 읽지 못했습니다"
                        + where(mismatch)
                        + ". 값마다 정해진 형식(숫자, 문자열, 목록)으로 고쳐 "
                        + name
                        + "을 다시 부르세요.";
        Map<String, Object> reply;
        if (session == null) {
            reply = Map.of("error", reason);
        } else {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("toolInput", toolInput == null ? null : abbreviate(toolInput));
            reply =
                    session.run(
                            name,
                            UNREADABLE_INPUT_LABEL,
                            input,
                            null,
                            null,
                            () -> {
                                throw new AgentQuerySession.InvalidInput(reason, ex);
                            });
        }
        return masked(JSON.writeValueAsString(reply));
    }

    private static MismatchedInputException mismatchedInput(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof MismatchedInputException mismatch) {
                return mismatch;
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }

    /** 틀린 칸과 기대한 형식. 어느 값이 틀렸는지 몰라 조건을 통째로 빼던 일이 있었다(!475 리뷰). Java 클래스 이름은 넣지 않는다. */
    private static String where(MismatchedInputException mismatch) {
        List<String> names = new ArrayList<>();
        for (JacksonException.Reference reference : mismatch.getPath()) {
            if (reference.getPropertyName() != null) {
                names.add(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                names.add("[" + reference.getIndex() + "]");
            }
        }
        String expected = expected(mismatch.getTargetType());
        if (names.isEmpty()) {
            return expected == null ? "" : "(" + expected + "이어야 하는 값)";
        }
        String at = String.join(".", names).replace(".[", "[");
        return expected == null ? "(위치: " + at + ")" : "(위치: " + at + ", " + expected + "이어야 함)";
    }

    private static String expected(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return "문자열 하나";
        }
        if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class)) {
            return "숫자";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "목록";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "객체";
        }
        return null;
    }

    private static String abbreviate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }

    /** JSON이면 문자열 값과 키마다, 아니면 글 전체를 가린다. */
    static String masked(String result) {
        if (result == null || result.isBlank()) {
            return result;
        }
        try {
            return JSON.writeValueAsString(maskedNode(JSON.readTree(result)));
        } catch (JacksonException notJson) {
            return ModelTextMasker.mask(result);
        }
    }

    private static JsonNode maskedNode(JsonNode node) {
        if (node.isString()) {
            return StringNode.valueOf(ModelTextMasker.mask(node.stringValue()));
        }
        if (node instanceof ObjectNode object) {
            // 값별 건수처럼 속성 값이 키로 오기도 해 키도 가린다. 가린 키가 겹치면 값을 합치거나 버리지 않고 키에 번호를 붙인다(*** 반장#2).
            ObjectNode masked = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> property : object.properties()) {
                String key = ModelTextMasker.mask(property.getKey());
                String unique = key;
                for (int n = 2; masked.has(unique); n++) {
                    unique = key + "#" + n;
                }
                masked.set(unique, maskedNode(property.getValue()));
            }
            return masked;
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, maskedNode(array.get(i)));
            }
        }
        return node;
    }
}

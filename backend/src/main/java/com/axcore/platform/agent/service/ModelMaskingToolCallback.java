package com.axcore.platform.agent.service;

import com.axcore.platform.auth.service.ModelTextMasker;
import java.util.ArrayList;
import java.util.Collection;
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

    private final ToolCallback delegate;

    private ModelMaskingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * 도구 객체({@code @Tool} 메서드를 가진 객체), {@link ToolCallback}, 그 목록·배열을 모두 감싼 콜백 목록으로 바꾼다. {@code
     * ChatClient.tools}가 받던 것과 같은 모양을 받는다.
     */
    static ToolCallback[] wrapAll(Object... tools) {
        List<ToolCallback> wrapped = new ArrayList<>();
        for (Object tool : tools) {
            collect(tool, wrapped);
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    private static void collect(Object tool, List<ToolCallback> out) {
        if (tool instanceof ToolCallback callback) {
            out.add(new ModelMaskingToolCallback(callback));
        } else if (tool instanceof ToolCallback[] callbacks) {
            for (ToolCallback callback : callbacks) {
                out.add(new ModelMaskingToolCallback(callback));
            }
        } else if (tool instanceof Collection<?> items) {
            for (Object item : items) {
                collect(item, out);
            }
        } else {
            for (ToolCallback callback : ToolCallbacks.from(tool)) {
                out.add(new ModelMaskingToolCallback(callback));
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
        return masked(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return masked(delegate.call(toolInput, toolContext));
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

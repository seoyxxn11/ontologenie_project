package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.auth.service.ModelTextMasker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Agent 도구 결과가 모델로 가기 전에 자유 글 가림을 거치는지 본다(S15P31C106-414). */
class ModelMaskingToolCallbackTests {

    static class NoteTools {
        @Tool(description = "인계 메모")
        String handoverNote() {
            return "야간 김 반장 확인, 연락 010-1234-5678. 설비 P103";
        }
    }

    record FixedCallback(String result) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("fixed")
                    .description("고정 결과")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return result;
        }
    }

    @Test
    void masksResultsOfAnnotatedToolsCallbacksAndLists() {
        ToolCallback[] wrapped =
                ModelMaskingToolCallback.wrapAll(
                        new NoteTools(),
                        new FixedCallback("홍길동님께 전달"),
                        List.of(new FixedCallback("kim@example.com")));

        assertThat(wrapped).hasSize(3);
        assertThat(wrapped[0].getToolDefinition().name()).isEqualTo("handoverNote");
        assertThat(wrapped[0].call("{}")).isEqualTo("\"야간 *** 반장 확인, 연락 ***. 설비 P103\"");
        assertThat(wrapped[1].call("{}")).isEqualTo("***님께 전달");
        assertThat(wrapped[2].call("{}", new ToolContext(java.util.Map.of()))).isEqualTo("***");
    }

    @Test
    void masksJsonStringValuesAsOriginalText() {
        // JSON 글자로 가리면 줄바꿈 이스케이프의 n 뒤 전화번호가 코드의 일부로 보여 빠진다
        String result =
                "{\"note\":\"연락처:\\n010-1234-5678\\nkim@example.com\",\"rows\":[\"김 대리\",1.10]}";

        assertThat(ModelMaskingToolCallback.masked(result))
                .isEqualTo("{\"note\":\"연락처:\\n***\\n***\",\"rows\":[\"*** 대리\",1.10]}");
        assertThat(ModelTextMasker.mask(result)).contains("010-1234-5678");
    }

    @Test
    void masksKeysSuchAsValueCountsWithoutLosingEntries() {
        String result = "{\"valueCounts\":{\"김 반장\":3,\"이 반장\":2,\"정비팀\":1}}";

        assertThat(ModelMaskingToolCallback.masked(result))
                .isEqualTo("{\"valueCounts\":{\"*** 반장\":3,\"*** 반장#2\":2,\"정비팀\":1}}");
    }
}

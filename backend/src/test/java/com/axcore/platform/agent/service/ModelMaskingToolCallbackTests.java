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

    static class ConditionTools {
        @Tool(description = "조건 하나")
        String byCondition(java.util.Map<String, String> propertyValues) {
            return "조건 " + propertyValues;
        }

        @Tool(description = "늘 실패")
        String broken() {
            throw new IllegalStateException("내부 오류");
        }
    }

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

    /**
     * AI에게 주는 도구만 이번 답변의 도구 호출 한도에 센다. 한도를 넘으면 도구를 부르지 않고 지금까지 결과로 답하라고 돌려준다. 서버가 미리 부르는 호출(납기 위험
     * 사전 비교)은 세지 않는다.
     */
    @Test
    void countsOnlyToolsGivenToTheModel() {
        AgentQuerySession session =
                new AgentQuerySession(
                        new com.axcore.platform.global.security.LoginAccount(
                                7L, "planner", "PLANNER", false),
                        11L,
                        org.mockito.Mockito.mock(AgentQueryRecorder.class),
                        java.time.Clock.systemUTC());
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback counting =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return new FixedCallback("결과").getToolDefinition();
                    }

                    @Override
                    public String call(String toolInput) {
                        calls.incrementAndGet();
                        return "결과";
                    }
                };
        ToolCallback server = ModelMaskingToolCallback.wrapAllRecording(session, counting)[0];
        ToolCallback model = ModelMaskingToolCallback.wrapAllCounted(session, counting)[0];

        for (int i = 0; i < AgentQuerySession.MAX_TOOL_CALLS; i++) {
            assertThat(server.call("{}")).isEqualTo("결과");
            assertThat(model.call("{}")).isEqualTo("결과");
        }

        assertThat(model.call("{}")).contains(AgentQuerySession.TOOL_CALL_LIMIT);
        assertThat(server.call("{}")).isEqualTo("결과");
        assertThat(calls).hasValue(AgentQuerySession.MAX_TOOL_CALLS * 2 + 1);
    }

    /**
     * 입력을 도구 인자로 바꾸지 못하면(문자열 자리에 목록) Java 오류 원문 대신 고칠 방법을 주고 이번 답변에 실패로 남긴다(2026-11-14 대화 103). 다른
     * 오류는 그대로 던진다.
     */
    @Test
    void reportsUnreadableInputAsFixableFailure() {
        AgentQuerySession session =
                new AgentQuerySession(
                        new com.axcore.platform.global.security.LoginAccount(
                                7L, "planner", "PLANNER", false),
                        11L,
                        org.mockito.Mockito.mock(AgentQueryRecorder.class),
                        java.time.Clock.systemUTC());
        ToolCallback[] wrapped =
                ModelMaskingToolCallback.wrapAllRecording(session, new ConditionTools());
        ToolCallback byCondition =
                java.util.Arrays.stream(wrapped)
                        .filter(tool -> tool.getToolDefinition().name().equals("byCondition"))
                        .findFirst()
                        .orElseThrow();

        assertThat(byCondition.call("{\"propertyValues\":{\"status\":\"진행\"}}")).contains("조건");
        String reply = byCondition.call("{\"propertyValues\":{\"status\":[\"진행\",\"대기\"]}}");

        assertThat(reply)
                .contains("입력 형식이 도구 설명과 달라", "byCondition", "위치: status", "문자열 하나")
                .doesNotContain("Cannot deserialize", "java.lang");
        assertThat(session.results())
                .extracting(
                        AgentQueryResult::toolCode,
                        AgentQueryResult::label,
                        AgentQueryResult::succeeded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "byCondition",
                                ModelMaskingToolCallback.UNREADABLE_INPUT_LABEL,
                                false));
        ToolCallback broken =
                java.util.Arrays.stream(wrapped)
                        .filter(tool -> tool.getToolDefinition().name().equals("broken"))
                        .findFirst()
                        .orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> broken.call("{}"))
                .as("입력 형식 오류가 아니면 바꾸지 않는다")
                .isInstanceOf(RuntimeException.class);
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

package com.axcore.platform.agent.service;

import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.audit.domain.EntryPoint;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업무 Action 하나를 AI 도구로 감싼다(144, Action 정의서 7절). 실행은 091 HTTP가 아니라 {@link ActionExecutionService}를
 * 서버 안에서 불러 entryPoint=AGENT, agentMessageId=질문 메시지로 남긴다. 실행 기록(actorType=AGENT)은 실행기가 남긴다. 결과는 답변의
 * answerMeta.actions에도 남겨 화면이 성공·차단과 만든 계획을 보여 준다(160).
 *
 * <p>실행 전에 사용자 확인이 필요한 Action(requiresConfirmation)은 실행하지 않고 확인이 필요하다고만 AI에게 알린다. 확인 뒤 실행하는 흐름은 재배정
 * 단계(S15P31C106-159~161)에서 만든다. 확인 전에 실행기를 부르면 BLOCKED가 기록되고 같은 requestId로는 다시 실행할 수 없기 때문이다.
 */
@Slf4j
public class AgentActionTool implements ToolCallback {
    static final String CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED";
    static final String NOT_RUN = "NOT_RUN";
    static final String REASSIGNMENT_PLAN = "CREATE_REASSIGNMENT_PLAN";

    private static final TypeReference<Map<String, Object>> INPUT_TYPE = new TypeReference<>() {};
    private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final ExecutableAction action;
    private final AgentQuerySession session;
    private final ActionExecutionService executions;
    private final JsonMapper json;
    private final ToolDefinition definition;

    public AgentActionTool(
            ExecutableAction action,
            AgentQuerySession session,
            ActionExecutionService executions,
            JsonMapper json) {
        this.action = action;
        this.session = session;
        this.executions = executions;
        this.json = json;
        this.definition =
                ToolDefinition.builder()
                        .name(action.code())
                        .description(description(action))
                        .inputSchema(schema(action, json))
                        .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        if (action.requiresConfirmation()) {
            return notRun(CONFIRMATION_REQUIRED, "실행 전에 사용자 확인이 필요해 실행하지 않았습니다. 사용자에게 실행할지 물어보세요.");
        }
        Map<String, Object> input;
        try {
            input = json.readValue(toolInput == null ? "{}" : toolInput, INPUT_TYPE);
        } catch (JacksonException ex) {
            return notRun(NOT_RUN, "입력을 읽지 못해 실행하지 않았습니다.");
        }
        String unchecked = REASSIGNMENT_PLAN.equals(action.code()) ? uncheckedPlan(input) : null;
        if (unchecked != null) {
            return notRun(NOT_RUN, unchecked);
        }
        ActionExecutionResponse response;
        try {
            response =
                    executions.execute(
                            new ActionExecutionCommand(
                                    session.asker(),
                                    action.code(),
                                    UUID.randomUUID(),
                                    null,
                                    null,
                                    input,
                                    false,
                                    EntryPoint.AGENT,
                                    session.questionId()));
        } catch (RuntimeException ex) {
            log.error("Agent Action {} 실행 실패 question={}", action.code(), session.questionId(), ex);
            return notRun(NOT_RUN, "실행하지 못했습니다.");
        }
        // 실행기가 결과를 돌려준 뒤에는 "실행하지 못함"으로 바꾸지 않는다. 실제로 실행된 Action을 한 번만 남긴다.
        session.addAction(
                new AgentActionOutcome(
                        action.code(),
                        action.name(),
                        response.status().name(),
                        response.id(),
                        response.result(),
                        response.blockedReason()));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("status", response.status().name());
        reply.put("blockedReason", response.blockedReason());
        reply.put("result", response.result());
        return json.writeValueAsString(reply);
    }

    /**
     * 재배정 계획을 저장하기 전에, 이번 답변에서 그 작업지시를 설비 조건 비교로 확인했고 정지 설비가 비교한 공정의 현재 설비인지 본다. 아니면 실행하지 않고 고칠 방법을
     * 돌려준다. 대체 설비만 말한 요청에서 그 설비를 정지 설비로도 넣거나(P101 → P101), 조회 없이 정지 설비를 짐작한 일이 있었다(2026-11-14 대화
     * 95). 실행기의 업무 검증(같은 설비, 배정, 설비 조건)은 그대로 다시 한다. 입력 형식 오류는 실행기의 입력 검증에 맡긴다.
     */
    private String uncheckedPlan(Map<String, Object> input) {
        if (input == null || !(input.get("items") instanceof List<?> items)) {
            return null;
        }
        Long from =
                input.get("fromEquipmentId") instanceof Number number ? number.longValue() : null;
        Set<Long> orders = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            if (map.get("workOrderId") instanceof Number id) {
                orders.add(id.longValue());
            }
            if (from != null
                    && map.get("toEquipmentId") instanceof Number to
                    && to.longValue() == from) {
                return ("fromEquipmentId와 toEquipmentId가 모두 #%d입니다. fromEquipmentId는 옮길 공정이 지금 배정된 설비(멈춘 설비),"
                                + " toEquipmentId는 옮겨 갈 대체 설비입니다. 설비 조건 비교 결과 operations의 assignedEquipmentId를"
                                + " fromEquipmentId로 넣어 다시 부르세요.")
                        .formatted(from);
            }
        }
        Set<Long> assigned = new LinkedHashSet<>();
        for (Long order : orders) {
            Set<Long> equipment = session.assignedEquipment(order);
            if (equipment == null) {
                return ("작업지시 #%d를 이번 답변에서 설비 조건 비교(COMPARE_EQUIPMENT_CAPABILITY)로 확인하지 않아 저장하지 않았습니다."
                                + " 먼저 작업지시와 대체 설비를 비교하고, 결과 operations의 assignedEquipmentId(옮길 공정이 지금 배정된 설비)를"
                                + " fromEquipmentId로 넣어 다시 부르세요.")
                        .formatted(order);
            }
            assigned.addAll(equipment);
        }
        if (assigned.isEmpty() || from == null) {
            return null;
        }
        if (!assigned.contains(from)) {
            return ("fromEquipmentId #%d는 옮길 공정이 지금 배정된 설비가 아닙니다. 배정된 설비: %s. fromEquipmentId는 옮길 공정이 지금 배정된"
                            + " 설비(멈춘 설비), toEquipmentId는 옮겨 갈 대체 설비입니다. 공정마다 배정 설비가 다르면 설비마다 나눠 저장하거나 사용자에게 물으세요.")
                    .formatted(from, assigned.stream().map(id -> "#" + id).toList());
        }
        return null;
    }

    /** 실행기를 부르지 않았다. 답변에도 실행하지 않았다고 남겨, 화면이 성공으로 보여 주지 않게 한다. */
    private String notRun(String status, String message) {
        session.addAction(AgentActionOutcome.notExecuted(action.code(), action.name(), status));
        return json.writeValueAsString(Map.of("status", status, "message", message));
    }

    private static String description(ExecutableAction action) {
        String text =
                action.description() == null
                        ? action.name()
                        : action.name() + ": " + action.description();
        return action.requiresConfirmation() ? text + " (실행 전에 사용자 확인 필요)" : text;
    }

    private static String schema(ExecutableAction action, JsonMapper json) {
        Map<String, Object> schema = action.inputSchema();
        return schema == null || schema.isEmpty()
                ? EMPTY_OBJECT_SCHEMA
                : json.writeValueAsString(schema);
    }
}

package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 대체 설비 조건 비교 도구(저장 없음). 비교는 {@link EquipmentCapabilityComparer}가 재배정 저장 검증과 같은 규칙으로 하고, 여기서는 AI가
 * 보낸 값을 확인하고 조회로 기록한다(AGENT_QUERY). 재배정 계획 저장 Action을 대신 불러 가능 여부를 확인하지 않게 하려고 둔다.
 */
public class AgentEquipmentCapabilityTools {
    static final String COMPARE_EQUIPMENT_CAPABILITY = "COMPARE_EQUIPMENT_CAPABILITY";

    private final AgentQuerySession session;
    private final EquipmentCapabilityComparer comparer;

    public AgentEquipmentCapabilityTools(
            AgentQuerySession session, EquipmentCapabilityComparer comparer) {
        this.session = session;
        this.comparer = comparer;
    }

    /** 작업지시나 설비를 하나라도 이번 답변에서 조회할 수 없으면 비교하지 않고 기록도 남기지 않는다(147). */
    @Tool(
            name = COMPARE_EQUIPMENT_CAPABILITY,
            description =
                    "작업지시의 공정을 다른 설비(대체 설비)에서 할 수 있는지 설비 조건을 비교한다. 작업지시 제품의 공정 표준에서 공정마다 필요한"
                            + " 설비 종류와 최소 능력(톤)을 찾아 후보 설비의 종류·능력과 비교하고, 설비마다 충족·미충족·미확인과 이유를"
                            + " 돌려준다. 재배정 계획을 저장하지 않는 조회다. 금형 호환·착수 순서·납기는 비교하지 않는다.")
    public Map<String, Object> compareEquipmentCapability(
            @ToolParam(description = "작업지시 객체 ID. 검색·관계 조회 결과나 참고 객체에 나온 ID만 쓴다") Long workOrderId,
            @ToolParam(description = "후보 설비 객체 ID 목록(최대 5개). 검색·관계 조회 결과나 참고 객체에 나온 ID만 쓴다")
                    List<Long> equipmentIds,
            @ToolParam(
                            description =
                                    "비교할 공정 순서 목록(예: [\"20\", \"30\"]). 비우면 끝나지 않은 공정 중 후보 설비 종류로 하는 공정",
                            required = false)
                    List<String> operationSequences) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "workOrderId", workOrderId);
        AgentQueryTools.putIfPresent(input, "equipmentIds", equipmentIds);
        AgentQueryTools.putIfPresent(input, "operationSequences", operationSequences);
        // 빈 값은 대상 확인에서 빼고, 아래에서 입력 오류로 기록한다(완료 시각 계산과 같다).
        List<Long> given =
                equipmentIds == null
                        ? List.of()
                        : equipmentIds.stream().filter(Objects::nonNull).toList();
        if ((workOrderId != null && !session.objectsAllowed(List.of(workOrderId)))
                || !session.objectsAllowed(given)) {
            return Map.of("error", AgentQuerySession.NOT_TARGET);
        }
        return session.run(
                COMPARE_EQUIPMENT_CAPABILITY,
                "설비 조건 비교",
                input,
                AgentQueryTools.targetTypeOf(workOrderId),
                workOrderId,
                () -> {
                    if (workOrderId == null) {
                        throw new AgentQuerySession.InvalidInput("workOrderId가 없습니다.", null);
                    }
                    List<Long> machines = equipmentIds(equipmentIds);
                    Set<String> sequences =
                            operationSequences == null
                                    ? Set.of()
                                    : CompletionEstimator.normalizeSequences(operationSequences);
                    EquipmentCapabilityComparer.Comparison comparison =
                            comparer.compare(session.asker(), workOrderId, machines, sequences);
                    if (comparison.category() == AgentResultCategory.FOUND) {
                        // 재배정 계획 저장에서 정지 설비를 이 값으로 확인한다(AgentActionTool).
                        session.recordAssignments(
                                workOrderId,
                                comparison.result().operations().stream()
                                        .map(
                                                EquipmentCapabilityComparer.OperationRequirement
                                                        ::assignedEquipmentId)
                                        .filter(Objects::nonNull)
                                        .toList());
                    }
                    return new AgentQuerySession.Outcome(
                            comparison.result(),
                            comparison.category(),
                            comparison.objectIds().stream()
                                    .map(id -> Map.<String, Object>of("objectId", id))
                                    .toList(),
                            comparison.objectIds().isEmpty()
                                    ? null
                                    : session.dataAsOfObjects(comparison.objectIds()));
                });
    }

    /** AI가 빈 목록·빈 값·중복을 보내면 비교하지 않고 입력 오류로 이유를 돌려준다. */
    private static List<Long> equipmentIds(List<Long> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            throw new AgentQuerySession.InvalidInput("equipmentIds가 없습니다.", null);
        }
        if (equipmentIds.stream().anyMatch(Objects::isNull)) {
            throw new AgentQuerySession.InvalidInput("equipmentIds에 빈 값이 있습니다.", null);
        }
        if (equipmentIds.size() > EquipmentCapabilityComparer.MAX_EQUIPMENT) {
            throw new AgentQuerySession.InvalidInput(
                    "equipmentIds는 %d개까지 받습니다."
                            .formatted(EquipmentCapabilityComparer.MAX_EQUIPMENT),
                    null);
        }
        if (new HashSet<>(equipmentIds).size() != equipmentIds.size()) {
            throw new AgentQuerySession.InvalidInput("equipmentIds에 같은 설비가 두 번 있습니다.", null);
        }
        return List.copyOf(equipmentIds);
    }
}

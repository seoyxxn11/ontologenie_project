package com.axcore.platform.agent.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 작업지시 예상 완료 시각 계산 도구(FN-AGT-09, S15P31C106-148, Action 정의서 7절 ESTIMATE_COMPLETION). 계산은 {@link
 * CompletionEstimator}가 하고, 여기서는 AI가 보낸 값을 확인하고 기록한다. AI가 직접 곱하고 더하면 값이 틀릴 수 있어 계산을 서버에 둔다.
 */
public class AgentCompletionEstimateTools {
    static final String ESTIMATE_COMPLETION = "ESTIMATE_COMPLETION";

    /** 사용자가 시각을 말하며 오프셋을 빼먹으면 한국 시간으로 본다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final AgentQuerySession session;
    private final CompletionEstimator estimator;

    public AgentCompletionEstimateTools(AgentQuerySession session, CompletionEstimator estimator) {
        this.session = session;
        this.estimator = estimator;
    }

    /**
     * 작업지시나 설비를 하나라도 이번 답변에서 조회할 수 없으면 계산하지 않고 기록도 남기지 않는다(147). 계산에 필요한 값이 없으면 NO_DATA이고
     * missingInputs에 이유가 있다.
     */
    @Tool(
            name = ESTIMATE_COMPLETION,
            description =
                    "작업지시의 남은 공정을 끝내는 데 걸리는 시간과 예상 완료 시각을 계산한다. 공정마다 남은 수량 × 개당 표준 가공 시간 + 표준"
                            + " 준비 시간으로 구하고, 받은 작업지시 순서·공정 순서대로 쉬지 않고 이어서 한다고 본다(교대·휴무·비가동 미반영)."
                            + " 앞에 먼저 해야 할 작업지시가 있으면 그 작업지시를 목록 앞에 넣는다. 특정 설비에서 하면 걸리는 시간을 물으면"
                            + " equipmentId에 그 설비를 넣는다. 그러면 그 설비 종류로 하는 공정만 계산한다.")
    public Map<String, Object> estimateCompletion(
            @ToolParam(
                            description =
                                    "작업지시 객체 ID 목록(최대 20개). 이어서 할 순서대로 적는다. 검색·관계 조회 결과에 나온 ID만 쓴다")
                    List<Long> workOrderIds,
            @ToolParam(
                            description =
                                    "계산할 공정 순서 목록(예: [\"20\", \"30\"]). 모든 작업지시에 같이 적용한다. 비우면 끝나지 않은 공정 전체",
                            required = false)
                    List<String> operationSequences,
            @ToolParam(
                            description =
                                    "이 설비에서 한다고 볼 설비 객체 ID. 사용자가 \"P101에서 찍으면\"처럼 설비를 말했을 때만 넣는다. 검색·관계 조회 결과에 나온"
                                            + " ID만 쓴다",
                            required = false)
                    Long equipmentId,
            @ToolParam(
                            description =
                                    "계산을 시작할 시각(ISO-8601, 예: 2026-10-30T14:00+09:00). 비우면 현재 시각",
                            required = false)
                    String startAt) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "workOrderIds", workOrderIds);
        AgentQueryTools.putIfPresent(input, "operationSequences", operationSequences);
        AgentQueryTools.putIfPresent(input, "equipmentId", equipmentId);
        AgentQueryTools.putIfPresent(input, "startAt", startAt);
        if ((workOrderIds != null && !session.objectsAllowed(workOrderIds))
                || (equipmentId != null && !session.objectsAllowed(List.of(equipmentId)))) {
            return session.refuse(
                    ESTIMATE_COMPLETION,
                    "예상 완료 시각",
                    input,
                    null,
                    null,
                    AgentQuerySession.NOT_TARGET);
        }
        // 작업지시가 하나면 그 객체를 기록 대상으로 남긴다. 여럿이면 입력에만 남긴다.
        Long target =
                workOrderIds != null && workOrderIds.size() == 1 ? workOrderIds.getFirst() : null;
        return session.run(
                ESTIMATE_COMPLETION,
                "예상 완료 시각",
                input,
                AgentQueryTools.targetTypeOf(target),
                target,
                () -> {
                    List<Long> ids = workOrderIds(workOrderIds);
                    Set<String> sequences =
                            operationSequences == null
                                    ? Set.of()
                                    : CompletionEstimator.normalizeSequences(operationSequences);
                    CompletionEstimator.Estimate estimate =
                            estimator.estimate(
                                    session.asker(), ids, sequences, equipmentId, startOf(startAt));
                    return new AgentQuerySession.Outcome(
                            estimate.result(),
                            estimate.category(),
                            estimate.objectIds().stream()
                                    .map(id -> Map.<String, Object>of("objectId", id))
                                    .toList(),
                            estimate.objectIds().isEmpty()
                                    ? null
                                    : session.dataAsOfObjects(estimate.objectIds()));
                });
    }

    /** AI가 빈 목록·빈 값·중복을 보내면 계산하지 않고 실패로 기록한다. */
    private static List<Long> workOrderIds(List<Long> workOrderIds) {
        if (workOrderIds == null || workOrderIds.isEmpty()) {
            throw new IllegalArgumentException("workOrderIds가 없습니다.");
        }
        if (workOrderIds.size() > AgentQueryTools.MAX_ROWS) {
            throw new IllegalArgumentException(
                    "workOrderIds는 %d개까지 받습니다.".formatted(AgentQueryTools.MAX_ROWS));
        }
        if (workOrderIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("workOrderIds에 빈 값이 있습니다.");
        }
        if (new HashSet<>(workOrderIds).size() != workOrderIds.size()) {
            throw new IllegalArgumentException("workOrderIds에 같은 작업지시가 두 번 있습니다.");
        }
        return List.copyOf(workOrderIds);
    }

    private Instant startOf(String startAt) {
        if (startAt == null || startAt.isBlank()) {
            return session.now();
        }
        String value = startAt.strip();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            try {
                return LocalDateTime.parse(value).atZone(USER_ZONE).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("startAt 형식이 올바르지 않습니다: " + value, ex);
            }
        }
    }
}

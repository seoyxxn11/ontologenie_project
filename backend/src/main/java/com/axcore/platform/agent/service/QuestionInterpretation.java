package com.axcore.platform.agent.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문에서 뽑은 조회 조건(142). AI 출력을 서버에서 검사한 뒤의 값이다. 답변의 answerMeta.conditions로 저장해 다음 질문 때 읽는다(대화방 상태,
 * 2026-11-04 결정).
 */
public record QuestionInterpretation(
        String purpose,
        List<String> targets,
        LocalDate from,
        LocalDate to,
        List<Long> referenceObjectIds) {

    public boolean hasPeriod() {
        return from != null && to != null;
    }

    /** answerMeta.conditions 값. 확인하지 못한 조건은 null로 둔다. */
    public Map<String, Object> toConditions() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("purpose", purpose);
        conditions.put("targets", targets);
        conditions.put(
                "period",
                hasPeriod() ? Map.of("from", from.toString(), "to", to.toString()) : null);
        conditions.put("referenceObjectIds", referenceObjectIds);
        return conditions;
    }
}

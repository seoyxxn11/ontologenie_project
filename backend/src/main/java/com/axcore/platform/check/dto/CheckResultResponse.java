package com.axcore.platform.check.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** API 062 검사 결과 응답. 시험 매칭률은 AI 신뢰도와 구분한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CheckResultResponse(
        long id,
        long checkRunId,
        Long linkMappingId,
        Long proposalItemId,
        Integer proposalItemRevision,
        Long previousResultId,
        int totalCount,
        int matchedCount,
        int unmatchedCount,
        int duplicateCount,
        int violationCount,
        // matchedCount / totalCount. 전체 검사 건수가 0이면 null이다.
        BigDecimal matchRate,
        List<Map<String, Object>> sampleRows) {

    public CheckResultResponse {
        // 근거 표본이 없을 때도 응답의 배열 형태를 유지한다.
        sampleRows = sampleRows == null ? List.of() : sampleRows;
    }
}

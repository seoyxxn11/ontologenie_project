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
        // 비교값으로 바꾸지 못해 대응 후보에서 뺀 도착 행 수(388). 위 분류 건수와 따로다.
        int excludedTargetCount,
        // matchedCount / totalCount. 전체 검사 건수가 0이면 null이다.
        BigDecimal matchRate,
        List<Map<String, Object>> sampleRows) {

    public CheckResultResponse {
        // 근거 표본이 없을 때도 응답의 배열 형태를 유지한다.
        sampleRows = sampleRows == null ? List.of() : sampleRows;
    }
}

package com.axcore.platform.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * FastAPI 분석 시작 알림 요청 본문(API 018 하단, POST /internal/v1/analysis-runs). 필수 필드만 보낸다.
 *
 * <p>{@code sources[].connection}에 원천 AI 계정 비밀번호가 들어 있다. 이 객체를 로그에 남기지 않고, 문자열로 바꿀 때도 접속 정보를 가린다.
 */
public record AnalysisStartRequest(
        long runId, String runKind, List<Target> targets, List<Source> sources) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(
            long sourceResourceId,
            String dataSourceCode,
            String resourceKey,
            List<String> columns,
            Map<String, Object> sourceMetadata) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(
            String dataSourceCode,
            String sourceKind,
            Map<String, Object> connection,
            String schema,
            String path) {

        @Override
        public String toString() {
            return "Source[dataSourceCode=%s, sourceKind=%s, connection=****, schema=%s, path=%s]"
                    .formatted(dataSourceCode, sourceKind, schema, path);
        }
    }
}

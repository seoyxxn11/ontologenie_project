package com.axcore.platform.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * FastAPI 분석 시작 알림 요청 본문(API 018 하단, POST /internal/v1/analysis-runs). 재분석에만 이력을 보낸다.
 *
 * <p>{@code sources[].connection}에 원천 AI 계정 비밀번호가 들어 있다. 이 객체를 로그에 남기지 않고, 문자열로 바꿀 때도 접속 정보를 가린다.
 */
public record AnalysisStartRequest(
        long runId,
        String runKind,
        List<Target> targets,
        List<Source> sources,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PastDecision> pastDecisions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PreviousProposal> previousProposals) {

    public record PastDecision(
            String candidateId,
            String itemKind,
            int revision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String fingerprint,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> payload,
            String decision,
            String reason) {}

    public record PreviousProposal(String candidateId, String fingerprint) {}

    /**
     * {@code structureOnlyColumns}: 값을 읽지 않고 이름·자료형·원천 주석만 넘기는 민감 컬럼(S15P31C106-416). AI 계정은 민감 컬럼을
     * SELECT할 수 없어 원천에서 보이지 않으므로, 속성·매핑 후보를 만들 수 있게 카탈로그에서 넘긴다. 금지 컬럼은 넣지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(
            long sourceResourceId,
            String dataSourceCode,
            String resourceKey,
            List<String> columns,
            Map<String, Object> sourceMetadata,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
                    List<StructureOnlyColumn> structureOnlyColumns) {}

    /** 값 없이 구조만 넘기는 컬럼. nullable은 원천 컬럼의 NULL 허용(필수 여부를 값 대신 이것으로 정한다), grade는 원천 데이터 정책 등급. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructureOnlyColumn(
            String name, String dataType, String comment, boolean nullable, String grade) {}

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

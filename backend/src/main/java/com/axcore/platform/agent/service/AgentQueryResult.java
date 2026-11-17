package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 조회 도구 호출 한 건의 결과. 답변의 answerMeta.results[] 한 줄이 된다(API 084). 결과 유형은 서버가 정하고 AI가 추측하지 않는다.
 *
 * @param resultCategory 조회가 끝났으면(SUCCEEDED) 필수, 실패(FAILED)면 null
 * @param dataAsOf 기준 시점. 알 수 없으면 null이고 다른 시각으로 채우지 않는다(정책 문서 4장)
 * @param errorMessage 사용자에게 보이는 실패 문구. 실패 까닭과 관계없이 같은 문구다(359)
 * @param failureKind 실패한 까닭의 종류(449). 성공이면 null. 조회 기록에만 남기고 answerMeta에는 넣지 않는다
 * @param failureReason 실패한 까닭(449). AI에게 돌려준 이유나 오류 종류다. 조회 기록에만 남긴다
 * @param retried 실패한 뒤 같은 도구(객체 하나를 보는 도구는 같은 객체)로 다시 조회해 성공했는가(449). 성공이면 false
 */
public record AgentQueryResult(
        String label,
        String toolCode,
        boolean succeeded,
        AgentResultCategory resultCategory,
        List<Map<String, Object>> sources,
        Instant dataAsOf,
        boolean estimated,
        String errorMessage,
        AgentFailureKind failureKind,
        String failureReason,
        boolean retried) {

    public AgentQueryResult(
            String label,
            String toolCode,
            boolean succeeded,
            AgentResultCategory resultCategory,
            List<Map<String, Object>> sources,
            Instant dataAsOf,
            boolean estimated,
            String errorMessage) {
        this(
                label,
                toolCode,
                succeeded,
                resultCategory,
                sources,
                dataAsOf,
                estimated,
                errorMessage,
                null,
                null,
                false);
    }

    /** 실패한 조회. 사용자에게는 일반 문구를, 조회 기록에는 까닭을 남긴다. */
    static AgentQueryResult failed(
            String label,
            String toolCode,
            String errorMessage,
            AgentFailureKind failureKind,
            String failureReason) {
        return new AgentQueryResult(
                label,
                toolCode,
                false,
                null,
                List.of(),
                null,
                false,
                errorMessage,
                failureKind,
                failureReason,
                false);
    }

    AgentQueryResult withRetried(boolean retried) {
        return retried == this.retried
                ? this
                : new AgentQueryResult(
                        label,
                        toolCode,
                        succeeded,
                        resultCategory,
                        sources,
                        dataAsOf,
                        estimated,
                        errorMessage,
                        failureKind,
                        failureReason,
                        retried);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("toolCode", toolCode);
        result.put("status", succeeded ? "SUCCEEDED" : "FAILED");
        result.put("resultCategory", resultCategory == null ? null : resultCategory.name());
        result.put("sources", sources);
        result.put("dataAsOf", dataAsOf == null ? null : dataAsOf.toString());
        result.put("estimated", estimated);
        result.put("errorMessage", errorMessage);
        if (!succeeded) {
            result.put("retried", retried);
        }
        return result;
    }
}

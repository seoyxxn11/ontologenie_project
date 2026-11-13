package com.axcore.platform.issue.domain;

import java.util.Map;
import java.util.Objects;

/** 검사가 등록하려는 문제 하나. 같은 dedupeKey의 열린 문제가 있으면 새로 만들지 않는다. */
public record NewIssue(
        IssueKind kind,
        IssueDedupeKey dedupeKey,
        long detectedCheckRunId,
        String failureReason,
        Long linkMappingId,
        Long proposalItemId,
        Long objectInstanceId,
        String rawValue,
        String transformedValue,
        Map<String, Object> evidence) {

    public NewIssue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        if (detectedCheckRunId < 1) {
            throw new IllegalArgumentException("detectedCheckRunId는 1 이상이어야 합니다.");
        }
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason이 비어 있습니다.");
        }
        // 문제는 반영된 규칙 또는 승인 전 후보 중 정확히 하나에서 나온다.
        if ((linkMappingId == null) == (proposalItemId == null)) {
            throw new IllegalArgumentException("linkMappingId와 proposalItemId 중 정확히 하나가 필요합니다.");
        }
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}

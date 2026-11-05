package com.axcore.platform.issue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** API 065·066 정합성 문제. 없는 값도 null로 내보내 화면이 필드 유무를 따로 확인하지 않게 한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IssueResponse(
        long id,
        String issueKind,
        String status,
        String dedupeKey,
        String failureReason,
        String rawValue,
        String transformedValue,
        // causeConfirmed는 항상 들어 있다. 저장된 근거에 없으면 false다.
        Map<String, Object> evidence,
        Long objectInstanceId,
        Long linkMappingId,
        Long proposalItemId,
        Long heldItemId,
        long detectedCheckRunId,
        Long resolvedCheckRunId,
        Instant forwardedAt,
        Long forwardedBy,
        Instant createdAt,
        long rowVersion) {}

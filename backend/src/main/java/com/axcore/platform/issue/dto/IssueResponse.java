package com.axcore.platform.issue.dto;

import com.axcore.platform.issue.domain.Issue;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 065·066 정합성 문제. 없는 값도 null로 내보내 화면이 필드 유무를 따로 확인하지 않게 한다.
 *
 * <p>rawValue·transformedValue·evidence.transformedValues는 보는 사람의 조회 범위로 가린 값이다(403). 가린 칸 이름은
 * restrictedFields에 있다. 값이 없는 것과 가린 것을 나눌 수 있게 한다.
 */
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
        long detectedCheckRunId,
        Long resolvedCheckRunId,
        Instant forwardedAt,
        Long forwardedBy,
        Instant createdAt,
        long rowVersion,
        List<String> restrictedFields) {

    /**
     * @param shown 가린 뒤의 rawValue·transformedValue·transformedValues. transformedValues는 근거에 있던
     *     경우에만 들어 있다
     * @param restrictedFields 가린 칸 이름
     */
    public static IssueResponse of(
            Issue issue, Map<String, Object> shown, List<String> restrictedFields) {
        return new IssueResponse(
                issue.getId(),
                issue.getIssueKind().name(),
                issue.getStatus().name(),
                issue.getDedupeKey(),
                issue.getFailureReason(),
                (String) shown.get("rawValue"),
                (String) shown.get("transformedValue"),
                evidence(issue.getEvidence(), shown),
                issue.getObjectInstanceId(),
                issue.getLinkMappingId(),
                issue.getProposalItemId(),
                issue.getDetectedCheckRunId(),
                issue.getResolvedCheckRunId(),
                issue.getForwardedAt(),
                issue.getForwardedBy(),
                issue.getCreatedAt(),
                issue.getRowVersion(),
                List.copyOf(restrictedFields));
    }

    private static Map<String, Object> evidence(
            Map<String, Object> stored, Map<String, Object> shown) {
        // Entity의 근거를 바꾸지 않도록 복사본에 채운다.
        Map<String, Object> evidence = new LinkedHashMap<>(stored);
        if (shown.containsKey("transformedValues")) {
            evidence.put("transformedValues", shown.get("transformedValues"));
        }
        // 원인 확인 여부를 남기지 않은 근거는 원인 미확정으로 본다(FN-ISS-02).
        if (!(evidence.get("causeConfirmed") instanceof Boolean)) {
            evidence.put("causeConfirmed", false);
        }
        return evidence;
    }
}

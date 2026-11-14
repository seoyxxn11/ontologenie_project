package com.axcore.platform.issue.service;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.service.StoredCheckValues;
import com.axcore.platform.check.service.StoredCheckValues.Masked;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.dto.IssueVerificationResponse;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.issue.repository.IssueVerificationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * API 070 문제 재검증 기록. detail의 rawValue·transformedValue는 그 판정에 쓴 규칙 개정본(detail.linkMappingId)과 보는
 * 사람의 조회 범위로 가린다(403, {@link StoredCheckValues}). 가린 칸 이름은 detail.restrictedFields에 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueVerificationQueryService {

    static final String VIEW = "ISSUE_VERIFICATIONS";

    private final IssueRepository issues;
    private final IssueVerificationRepository verifications;
    private final DataAccessPolicy dataAccessPolicy;
    private final StoredCheckValues storedValues;
    private final RestrictedViewRecorder restrictedViews;

    public Page<IssueVerificationResponse> search(
            LoginAccount viewer, long issueId, Pageable pageable) {
        if (!issues.existsById(issueId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "issue"));
        }
        Page<IssueVerificationResponse> page = verifications.findByIssue(issueId, pageable);
        StoredCheckValues.Viewer values = storedValues.viewer(dataAccessPolicy.of(viewer));
        List<IssueVerificationResponse> masked = new ArrayList<>(page.getNumberOfElements());
        List<String> revealed = new ArrayList<>();
        for (IssueVerificationResponse verification : page.getContent()) {
            masked.add(mask(values, verification, revealed));
        }
        restrictedViews.record(
                viewer,
                VIEW,
                List.of(
                        new Seen(
                                IssueResponses.TARGET_TYPE,
                                issueId,
                                revealed.stream().distinct().sorted().toList())));
        return new PageImpl<>(masked, page.getPageable(), page.getTotalElements());
    }

    private static IssueVerificationResponse mask(
            StoredCheckValues.Viewer values,
            IssueVerificationResponse verification,
            List<String> revealed) {
        if (!(verification.detail() instanceof ObjectNode detail)) {
            return verification;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        for (String field : List.of("rawValue", "transformedValue")) {
            if (detail.has(field)) {
                JsonNode value = detail.get(field);
                stored.put(field, value.isNull() ? null : value.asString());
            }
        }
        if (stored.isEmpty()) {
            return verification;
        }
        JsonNode rule = detail.path("linkMappingId");
        Masked masked =
                values.mask(values.rule(rule.isIntegralNumber() ? rule.asLong() : null), stored);
        revealed.addAll(masked.revealed());
        if (masked.restrictedFields().isEmpty()) {
            return verification;
        }
        ObjectNode copy = detail.deepCopy();
        masked.restrictedFields().forEach(copy::putNull);
        ArrayNode fields = copy.putArray("restrictedFields");
        masked.restrictedFields().forEach(fields::add);
        return new IssueVerificationResponse(
                verification.issueId(),
                verification.checkRunId(),
                verification.triggerKind(),
                verification.status(),
                verification.outcome(),
                copy,
                verification.checkedAt());
    }
}

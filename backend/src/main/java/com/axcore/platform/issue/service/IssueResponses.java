package com.axcore.platform.issue.service;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.service.StoredCheckValues;
import com.axcore.platform.check.service.StoredCheckValues.Masked;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.dto.IssueResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 문제 응답(065~068)을 보는 사람의 조회 범위로 만든다(S15P31C106-403). 문제는 검사가 객체 값을 복사해 저장한 것이라, 값을 낸 규칙의 지금 정의와 범위로
 * 다시 가린다. 기준은 {@link StoredCheckValues}에 있다. 가리지 않은 민감 값을 내보내면 열람 기록(394)을 남긴다.
 */
@Component
@RequiredArgsConstructor
public class IssueResponses {

    static final String TARGET_TYPE = "issue";

    private final DataAccessPolicy dataAccessPolicy;
    private final StoredCheckValues storedValues;
    private final RestrictedViewRecorder restrictedViews;

    public IssueResponse of(LoginAccount viewer, String view, Issue issue) {
        return of(viewer, view, List.of(issue)).getFirst();
    }

    public List<IssueResponse> of(LoginAccount viewer, String view, List<Issue> issues) {
        StoredCheckValues.Viewer values = storedValues.viewer(dataAccessPolicy.of(viewer));
        List<IssueResponse> responses = new ArrayList<>(issues.size());
        List<Seen> seen = new ArrayList<>();
        for (Issue issue : issues) {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("rawValue", issue.getRawValue());
            stored.put("transformedValue", issue.getTransformedValue());
            if (issue.getEvidence().containsKey("transformedValues")) {
                stored.put("transformedValues", issue.getEvidence().get("transformedValues"));
            }
            Masked masked = values.mask(values.rule(issue.getLinkMappingId()), stored);
            responses.add(IssueResponse.of(issue, masked.values(), masked.restrictedFields()));
            seen.add(new Seen(TARGET_TYPE, issue.getId(), masked.revealed()));
        }
        restrictedViews.record(viewer, view, seen);
        return responses;
    }
}

package com.axcore.platform.check.service;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Category;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Coverage;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Evidence;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Row;
import com.axcore.platform.check.repository.ReverificationComparisonRepository;
import com.axcore.platform.check.repository.ReverificationComparisonRepository.StoredRow;
import com.axcore.platform.check.service.StoredCheckValues.Masked;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReverificationComparisonService {

    static final String VIEW = "CHECK_COMPARISON";
    static final String TARGET_TYPE = "check_run";

    private final CheckRunQueryService runs;
    private final ReverificationComparisonRepository evidence;
    private final DataAccessPolicy dataAccessPolicy;
    private final StoredCheckValues storedValues;
    private final RestrictedViewRecorder restrictedViews;

    public Comparison get(LoginAccount viewer, long id, Pageable page) {
        CheckRunResponse run = runs.get(id);
        Long baseline = run.comparedToRunId();
        if (!"REVERIFY".equals(run.triggerKind()) || baseline == null) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION, "재검증 실행만 비교할 수 있습니다.", Map.of());
        }
        if (!"SUCCEEDED".equals(run.status())) {
            return new Comparison(baseline, Coverage.UNAVAILABLE, false, Page.empty(page));
        }

        Map<RowKey, Evidence> previous = new HashMap<>();
        for (StoredRow row : evidence.findEvidence(baseline)) {
            previous.put(new RowKey(row.ruleCode(), row.objectInstanceId()), row.evidence());
        }

        boolean hasBaselinePassEvidence =
                previous.values().stream().anyMatch(row -> "MATCHED".equals(row.classification()));
        boolean comparable = false;
        List<Row> changes = new ArrayList<>();
        for (StoredRow row : evidence.findEvidence(id)) {
            Evidence before = previous.get(new RowKey(row.ruleCode(), row.objectInstanceId()));
            if (before == null) {
                continue;
            }
            comparable = true;
            Evidence after = row.evidence();
            Category category = category(before, after);
            if (category != null) {
                changes.add(
                        new Row(category, row.ruleCode(), row.objectInstanceId(), before, after));
            }
        }
        int start = (int) Math.min(page.getOffset(), changes.size());
        int end = (int) Math.min((long) start + page.getPageSize(), changes.size());
        List<Row> shown = mask(viewer, id, changes.subList(start, end));
        Coverage coverage = comparable ? Coverage.PARTIAL : Coverage.UNAVAILABLE;
        return new Comparison(
                baseline,
                coverage,
                hasBaselinePassEvidence,
                new PageImpl<>(shown, page, changes.size()));
    }

    /**
     * 보여 줄 행의 비교값을 그 판정에 쓴 규칙 개정본과 보는 사람의 조회 범위로 가린다(403, {@link StoredCheckValues}). 분류는 가리기 전 값으로
     * 이미 정했다. 가리지 않은 민감 값을 내보내면 이 재검증 실행을 대상으로 열람 기록(394)을 남긴다.
     */
    private List<Row> mask(LoginAccount viewer, long runId, List<Row> rows) {
        StoredCheckValues.Viewer values = storedValues.viewer(dataAccessPolicy.of(viewer));
        Set<String> revealed = new TreeSet<>();
        List<Row> shown = new ArrayList<>(rows.size());
        for (Row row : rows) {
            shown.add(
                    new Row(
                            row.category(),
                            row.ruleCode(),
                            row.objectInstanceId(),
                            mask(values, row.before(), revealed),
                            mask(values, row.after(), revealed)));
        }
        restrictedViews.record(
                viewer, VIEW, List.of(new Seen(TARGET_TYPE, runId, List.copyOf(revealed))));
        return shown;
    }

    private static Evidence mask(
            StoredCheckValues.Viewer values, Evidence evidence, Set<String> revealed) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("rawValue", evidence.rawValue());
        stored.put("transformedValue", evidence.transformedValue());
        Masked masked = values.mask(values.rule(evidence.linkMappingId()), stored);
        revealed.addAll(masked.revealed());
        return new Evidence(
                evidence.linkMappingId(),
                evidence.classification(),
                (String) masked.values().get("rawValue"),
                (String) masked.values().get("transformedValue"),
                evidence.reason(),
                masked.restrictedFields());
    }

    private static Category category(Evidence before, Evidence after) {
        boolean previouslyMatched = "MATCHED".equals(before.classification());
        boolean currentlyMatched = "MATCHED".equals(after.classification());
        if (previouslyMatched) {
            return currentlyMatched ? null : Category.NEW;
        }
        return currentlyMatched ? Category.RESOLVED : Category.REMAINING;
    }

    private record RowKey(String ruleCode, long objectInstanceId) {}

    public record Comparison(
            long comparedToRunId,
            Coverage coverage,
            boolean hasBaselinePassEvidence,
            Page<Row> rows) {}
}

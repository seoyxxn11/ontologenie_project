package com.axcore.platform.check.service;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.dto.CheckResultResponse;
import com.axcore.platform.check.dto.CheckResultSearchRequest;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckResultSpecifications;
import com.axcore.platform.check.repository.CheckRunQueryRepository;
import com.axcore.platform.check.service.StoredCheckValues.Masked;
import com.axcore.platform.check.service.StoredCheckValues.RuleValues;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * 검사 실행 상태를 확인하고 결과 집계와 판정 근거를 응답으로 변환한다. 판정 근거 표본(sampleRows)은 그 결과를 낸 규칙의 지금 정의와 보는 사람의 조회 범위로
 * 가린다(403, {@link StoredCheckValues}). 가린 칸 이름은 표본의 restrictedFields에 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckResultService {

    static final String VIEW = "CHECK_RESULTS";
    static final String TARGET_TYPE = "check_result";

    private final CheckResultRepository checkResultRepository;
    private final CheckRunQueryRepository checkRunQueryRepository;
    private final DataAccessPolicy dataAccessPolicy;
    private final StoredCheckValues storedValues;
    private final RestrictedViewRecorder restrictedViews;

    public Page<CheckResultResponse> search(
            LoginAccount viewer,
            long checkRunId,
            CheckResultSearchRequest request,
            Pageable pageable) {

        // 결과가 없다는 이유만으로 실행 자체가 없다고 판단하지 않는다.
        String status =
                checkRunQueryRepository
                        .findStatusById(checkRunId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "check_run", "id", checkRunId)));

        // 진행 중이거나 실패한 실행의 부분 결과는 응답에 포함하지 않는다.
        if (!"SUCCEEDED".equals(status)) {
            return Page.empty(pageable);
        }

        Page<CheckResult> results =
                checkResultRepository.findAll(
                        CheckResultSpecifications.byFilters(
                                checkRunId, request.linkMappingId(), request.proposalItemId()),
                        pageable);

        StoredCheckValues.Viewer values = storedValues.viewer(dataAccessPolicy.of(viewer));
        List<Seen> seen = new ArrayList<>();
        List<CheckResultResponse> responses =
                results.getContent().stream()
                        .map(result -> toResponse(result, values, seen))
                        .toList();
        restrictedViews.record(viewer, VIEW, seen);
        return new PageImpl<>(responses, results.getPageable(), results.getTotalElements());
    }

    private static CheckResultResponse toResponse(
            CheckResult result, StoredCheckValues.Viewer values, List<Seen> seen) {
        RuleValues rule =
                result.getLinkMappingId() != null
                        ? values.rule(result.getLinkMappingId())
                        : values.candidate(result.getCheckRunId());
        List<Map<String, Object>> samples = new ArrayList<>(result.getSampleRows().size());
        Set<String> revealed = new TreeSet<>();
        for (Map<String, Object> sample : result.getSampleRows()) {
            Masked masked = values.sample(rule, sample);
            // 표본 값에 null이 있을 수 있어 Map.copyOf 대신 순서를 지키는 복사본을 쓴다.
            samples.add(Collections.unmodifiableMap(masked.values()));
            revealed.addAll(masked.revealed());
        }
        seen.add(new Seen(TARGET_TYPE, result.getId(), List.copyOf(revealed)));

        BigDecimal matchRate = null;

        // 전체 건수가 0이면 매칭률을 계산할 수 없으므로 null로 둔다.
        if (result.getTotalCount() > 0) {
            matchRate =
                    BigDecimal.valueOf(result.getMatchedCount())
                            .divide(
                                    BigDecimal.valueOf(result.getTotalCount()),
                                    4,
                                    RoundingMode.HALF_UP);
        }

        return new CheckResultResponse(
                result.getId(),
                result.getCheckRunId(),
                result.getLinkMappingId(),
                result.getProposalItemId(),
                result.getProposalItemRevision(),
                result.getPreviousResultId(),
                result.getTotalCount(),
                result.getMatchedCount(),
                result.getUnmatchedCount(),
                result.getDuplicateCount(),
                result.getViolationCount(),
                result.getExcludedTargetCount(),
                matchRate,
                samples);
    }
}

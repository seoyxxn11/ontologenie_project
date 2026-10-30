package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.dto.CheckResultResponse;
import com.axcore.platform.check.dto.CheckResultSearchRequest;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckResultSpecifications;
import com.axcore.platform.check.repository.CheckRunQueryRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검사 실행 상태를 확인하고 결과 집계와 판정 근거를 응답으로 변환한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckResultService {

    private final CheckResultRepository checkResultRepository;
    private final CheckRunQueryRepository checkRunQueryRepository;

    public Page<CheckResultResponse> search(
            long checkRunId, CheckResultSearchRequest request, Pageable pageable) {

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

        return results.map(this::toResponse);
    }

    private CheckResultResponse toResponse(CheckResult result) {
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
                matchRate,
                result.getSampleRows());
    }
}

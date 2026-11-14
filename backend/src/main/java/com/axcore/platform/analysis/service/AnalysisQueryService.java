package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.dto.AnalysisProposalSearchRequest;
import com.axcore.platform.analysis.dto.AnalysisResponse;
import com.axcore.platform.analysis.dto.AnalysisSearchRequest;
import com.axcore.platform.analysis.repository.AnalysisQueryRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.service.ProposalService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisQueryService {
    private final AnalysisQueryRepository repository;
    private final ProposalService proposals;

    public Page<AnalysisResponse> search(AnalysisSearchRequest request, Pageable pageable) {
        return repository.search(request, pageable);
    }

    public AnalysisResponse get(long id) {
        return repository.findById(id).orElseThrow(AnalysisQueryService::notFound);
    }

    public Page<ProposalResponse> proposals(
            LoginAccount viewer,
            long id,
            AnalysisProposalSearchRequest request,
            Pageable pageable) {
        if (!repository.exists(id)) {
            throw notFound();
        }
        return proposals.searchByAnalysis(
                viewer,
                id,
                new ProposalSearchRequest(
                        "AI",
                        request.itemKind(),
                        request.reviewStatus(),
                        request.applyStatus(),
                        request.page(),
                        request.size()),
                pageable);
    }

    private static BusinessException notFound() {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "analysis_run"));
    }
}

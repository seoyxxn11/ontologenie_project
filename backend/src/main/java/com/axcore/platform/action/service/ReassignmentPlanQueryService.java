package com.axcore.platform.action.service;

import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import com.axcore.platform.action.repository.ReassignmentPlanQueryRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 재배정 계획 조회 (API 093~095, FN-ACT-06). 로그인한 모든 역할이 볼 수 있다(D4). 계획 생성은 091로만 하고, 조회는 원천 MES를 바꾸지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReassignmentPlanQueryService {

    private final ReassignmentPlanQueryRepository plans;

    public Page<ReassignmentPlanResponse> search(
            Long fromEquipmentId, Long createdBy, Pageable pageable) {
        return plans.search(fromEquipmentId, createdBy, pageable);
    }

    public ReassignmentPlanResponse get(long id) {
        return plans.findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "reassignment_plan")));
    }

    public List<ReassignmentPlanItemResponse> items(long id) {
        return get(id).items();
    }
}

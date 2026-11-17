package com.axcore.platform.action.service;

import com.axcore.platform.action.domain.ReassignmentPlan;
import com.axcore.platform.action.domain.ReassignmentPlanItem;
import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import com.axcore.platform.action.handler.ReassignmentProperties;
import com.axcore.platform.action.handler.ReassignmentSnapshot;
import com.axcore.platform.action.repository.ReassignmentPlanItemRepository;
import com.axcore.platform.action.repository.ReassignmentPlanQueryRepository;
import com.axcore.platform.action.repository.ReassignmentPlanSpecifications;
import com.axcore.platform.audit.service.AuditValueMasker;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 재배정 계획 조회 (API 093~095, FN-ACT-06). 로그인한 역할이면 부를 수 있다(D4). 계획 생성은 091로만 하고, 조회는 원천 MES를 바꾸지
 * 않는다.
 *
 * <p>계획은 설비·작업지시·공정 객체를 가리키고, 항목의 공정 코드와 스냅샷(source_snapshot)에는 그 객체들의 속성 값이 들어 있다. 그래서 보는 사람의 조회
 * 범위를 적용한다(S15P31C106-384·390).
 *
 * <ul>
 *   <li>설비·작업지시·공정 유형 중 하나라도 볼 수 없으면 계획이 없는 것처럼 다룬다(목록은 비고, 한 건은 404).
 *   <li>항목의 스냅샷은 정한 필드({@link ReassignmentSnapshot#explicit})만 내보낸다. 공정 코드와 그 필드는 Handler가 변경 기록에
 *       알린 자리({@link AuditValueMasker#maskRow})로 민감 값을 가린다. 감사 098과 같은 규칙이다. 스냅샷에는 원래 값이 저장돼
 *       있다(404).
 *   <li>선정 근거(selectionBasis)는 이유 문장(reason)만 내보낸다({@link ReassignmentPlanResponse#from}).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReassignmentPlanQueryService {

    private static final String ITEM_TABLE = "reassignment_plan_item";
    private static final String OPERATION_CODE = "operation_code";
    private static final String SNAPSHOT = "source_snapshot";

    /** 최근에 만든 계획부터. 요청의 정렬은 받지 않는다. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ReassignmentPlanQueryRepository plans;
    private final ReassignmentPlanItemRepository planItems;
    private final ReassignmentProperties properties;
    private final DataAccessPolicy dataAccessPolicy;
    private final AuditValueMasker masker;
    private final RestrictedViewRecorder restrictedViews;

    /** 조건이 null이면 거르지 않는다. 계획 한 페이지를 고른 뒤 그 계획들의 항목은 한 번에 붙인다. */
    public Page<ReassignmentPlanResponse> search(
            LoginAccount viewer, Long fromEquipmentId, Long createdBy, Pageable pageable) {
        return search(viewer, fromEquipmentId, createdBy, null, pageable);
    }

    public Page<ReassignmentPlanResponse> search(
            LoginAccount viewer,
            Long fromEquipmentId,
            Long createdBy,
            String createdByName,
            Pageable pageable) {
        DataAccess access = dataAccessPolicy.of(viewer);
        if (!seesPlanObjects(access)) {
            return Page.empty(pageable);
        }
        Page<ReassignmentPlan> page =
                plans.findAll(
                        ReassignmentPlanSpecifications.byFilters(
                                fromEquipmentId, createdBy, createdByName),
                        PageRequest.of(
                                pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
        return new PageImpl<>(
                withItems(page.getContent(), access, viewer), pageable, page.getTotalElements());
    }

    public ReassignmentPlanResponse get(LoginAccount viewer, long id) {
        DataAccess access = dataAccessPolicy.of(viewer);
        ReassignmentPlan plan =
                plans.findById(id)
                        .filter(found -> seesPlanObjects(access))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "reassignment_plan")));
        return withItems(List.of(plan), access, viewer).getFirst();
    }

    public List<ReassignmentPlanItemResponse> items(LoginAccount viewer, long id) {
        return get(viewer, id).items();
    }

    /** 계획이 가리키는 설비·작업지시·공정 유형을 모두 볼 수 있는가. */
    private boolean seesPlanObjects(DataAccess access) {
        return access.canView(properties.equipment().type())
                && access.canView(properties.workOrder().type())
                && access.canView(properties.operation().type());
    }

    /** 계획마다 항목을 붙인다. 가리지 않고 보여 준 민감 자리가 있으면 계획별로 열람 기록을 남긴다(394). */
    private List<ReassignmentPlanResponse> withItems(
            List<ReassignmentPlan> found, DataAccess access, LoginAccount viewer) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ReassignmentPlanItemResponse>> itemsByPlan = new HashMap<>();
        Map<Long, Set<String>> revealedByPlan = new TreeMap<>();
        // 스냅샷은 계획을 만든 때의 값이라, 그 뒤 민감에서 내린 속성도 그때 민감이었으면 가린다(431)
        Map<Long, DataAccess> accessByPlan = new HashMap<>();
        found.forEach(plan -> accessByPlan.put(plan.getId(), access.asOf(plan.getCreatedAt())));
        planItems
                .findByPlanIdInOrderById(found.stream().map(ReassignmentPlan::getId).toList())
                .forEach(
                        item ->
                                itemsByPlan
                                        .computeIfAbsent(
                                                item.getPlanId(), planId -> new ArrayList<>())
                                        .add(
                                                masked(
                                                        item,
                                                        accessByPlan.get(item.getPlanId()),
                                                        revealedByPlan)));
        restrictedViews.record(
                viewer,
                "REASSIGNMENT_PLAN",
                revealedByPlan.entrySet().stream()
                        .map(
                                entry ->
                                        new Seen(
                                                "reassignment_plan",
                                                entry.getKey(),
                                                List.copyOf(entry.getValue())))
                        .toList());
        return found.stream()
                .map(
                        plan ->
                                ReassignmentPlanResponse.from(
                                        plan, itemsByPlan.getOrDefault(plan.getId(), List.of())))
                .toList();
    }

    /** 공정 코드와 정한 스냅샷 필드를 저장된 행 모양으로 묶어 Handler가 알린 자리로 가린다. */
    @SuppressWarnings("unchecked")
    private ReassignmentPlanItemResponse masked(
            ReassignmentPlanItem item, DataAccess access, Map<Long, Set<String>> revealedByPlan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(OPERATION_CODE, item.getOperationCode());
        // 저장된 스냅샷에 다른 필드가 있어도 정한 필드만 내보낸다(404).
        row.put(SNAPSHOT, ReassignmentSnapshot.explicit(item.getSourceSnapshot()));
        Masked<Map<String, Object>> masked = masker.maskRow(ITEM_TABLE, row, access);
        if (!masked.revealedPropertyCodes().isEmpty()) {
            revealedByPlan
                    .computeIfAbsent(item.getPlanId(), planId -> new TreeSet<>())
                    .addAll(masked.revealedPropertyCodes());
        }
        return ReassignmentPlanItemResponse.of(
                item,
                (String) masked.value().get(OPERATION_CODE),
                (Map<String, Object>) masked.value().get(SNAPSHOT),
                masked.restrictedPropertyCodes());
    }
}

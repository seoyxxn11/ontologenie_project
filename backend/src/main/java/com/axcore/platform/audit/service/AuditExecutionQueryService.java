package com.axcore.platform.audit.service;

import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.dto.AuditEntityChangeResponse;
import com.axcore.platform.audit.dto.AuditExecutionChangesResponse;
import com.axcore.platform.audit.dto.AuditExecutionDetailResponse;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.AuditExecutionSearchRequest;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.repository.AuditExecutionQueryRepository;
import com.axcore.platform.audit.repository.AuditExecutionQueryRepository.Filter;
import com.axcore.platform.audit.repository.EntityChangeRepository;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행·감사 이력 조회 (API 096·097·098·080, FN-LOG-01~04·FN-EXP-03). Agent 조회 기록(AGENT_QUERY)은 어디에도 보여 주지
 * 않는다. 080은 역할의 객체 조회 범위(S15P31C106-205)를 따른다. 097·098은 ADMIN이 아니면 사용자가 볼 수 없는 민감 속성 값을 가린다.
 * 목록(096·080)은 입력값과 변경 내역을 주지 않아 가릴 값이 없다(S15P31C106-374).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditExecutionQueryService {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_MINE = "MINE";
    private static final String ADMIN = "ADMIN";
    private static final String OBJECT_INSTANCE = "object_instance";

    private final AuditExecutionQueryRepository history;
    private final ActionExecutionRepository executions;
    private final EntityChangeRepository changes;
    private final DataAccessPolicy dataAccessPolicy;
    private final AuditValueMasker masker;
    private final RestrictedViewRecorder restrictedViews;

    /** 096. ADMIN은 전체, 그 밖의 역할은 본인 실행(본인이 요청한 작업의 결과 줄 포함)만 본다. */
    public Page<AuditExecutionResponse> search(
            LoginAccount viewer, AuditExecutionSearchRequest request, Pageable pageable) {
        if (request.eventKind() == EventKind.AGENT_QUERY) {
            throw BusinessException.invalidField("eventKind", "Agent 조회 기록은 조회할 수 없습니다.");
        }
        // 민감 값 열람 기록(394)은 ADMIN이 골라 볼 때만 연다.
        if (request.eventKind() == EventKind.RESTRICTED_VIEW && !isAdmin(viewer)) {
            throw forbidden();
        }
        if (request.targetId() != null && request.targetType() == null) {
            throw BusinessException.invalidField("targetId", "targetId는 targetType과 함께 보내야 합니다.");
        }
        boolean admin = isAdmin(viewer);
        if (!admin && request.actorId() != null && !request.actorId().equals(viewer.id())) {
            throw forbidden();
        }
        Filter filter =
                new Filter(
                        request.actorId(),
                        request.actionCode(),
                        request.status(),
                        request.entryPoint(),
                        request.eventKind(),
                        request.category(),
                        request.targetType(),
                        request.targetId(),
                        request.from(),
                        request.to());
        return history.search(
                filter, admin ? null : viewer.id(), dataAccessPolicy.of(viewer), pageable);
    }

    public static String scopeOf(LoginAccount viewer) {
        return isAdmin(viewer) ? SCOPE_ALL : SCOPE_MINE;
    }

    /**
     * 097. 실행 하나의 입력·결과·막힌 이유·실패 이유. ADMIN은 전체 기록, 그 밖의 역할은 096과 같은 본인 실행 범위만 연다. 입력값과 Handler 결과는
     * Handler가 알린 자리만 보여 주고, 민감 속성 값은 역할의 허용 행대로 가린다(ADMIN 포함, 384·404).
     */
    public AuditExecutionDetailResponse get(LoginAccount viewer, long id) {
        ActionExecution execution = visibleExecution(viewer, id);
        // ADMIN도 role_data_scope 허용 행대로 가린다(384: 정의·시스템 관리와 값 열람을 나눈다). 실행 때 민감이었다가 내린 속성도
        // 가린다(431).
        DataAccess access = dataAccessPolicy.of(viewer).asOf(execution.getRequestedAt());
        Masked<Map<String, Object>> input =
                masker.maskInput(execution.getActionDefinitionId(), execution.getInput(), access);
        Masked<Map<String, Object>> result =
                masker.maskResult(
                        execution.getActionDefinitionId(),
                        execution.getStatus(),
                        execution.getResult(),
                        access);
        TreeSet<String> restricted = new TreeSet<>(input.restrictedPropertyCodes());
        restricted.addAll(result.restrictedPropertyCodes());
        TreeSet<String> revealed = new TreeSet<>(input.revealedPropertyCodes());
        revealed.addAll(result.revealedPropertyCodes());
        restrictedViews.record(
                viewer,
                "AUDIT_DETAIL",
                List.of(new Seen("action_execution", id, List.copyOf(revealed))));
        return AuditExecutionDetailResponse.of(
                execution,
                input.value(),
                result.value(),
                masker.maskBlockedReason(
                        execution.getActionDefinitionId(),
                        execution.getStatus(),
                        execution.getBlockedReason(),
                        execution.getResult(),
                        access),
                List.copyOf(restricted));
    }

    /** 098. 실행 하나가 바꾼 행 전체. 열람 범위는 097과 같고, 변경 내역의 민감 속성 값은 역할의 허용 행대로 가린다(ADMIN 포함, 384). */
    public AuditExecutionChangesResponse changes(LoginAccount viewer, long id) {
        ActionExecution execution = visibleExecution(viewer, id);
        List<AuditEntityChangeResponse> found =
                changes.findByActionExecutionIdOrderByIdAsc(id).stream()
                        .map(AuditEntityChangeResponse::from)
                        .toList();
        Masked<List<AuditEntityChangeResponse>> masked =
                masker.maskChanges(
                        found, dataAccessPolicy.of(viewer).asOf(execution.getRequestedAt()));
        restrictedViews.record(
                viewer,
                "AUDIT_CHANGES",
                List.of(new Seen("action_execution", id, masked.revealedPropertyCodes())));
        return new AuditExecutionChangesResponse(masked.value(), masked.restrictedPropertyCodes());
    }

    /** 범위 밖 기록, 없는 기록, Agent 조회 기록은 모두 같은 404로 막아 있다는 사실도 알리지 않는다. */
    private ActionExecution visibleExecution(LoginAccount viewer, long id) {
        if (!history.isVisible(id, isAdmin(viewer) ? null : viewer.id())) {
            throw executionNotFound();
        }
        return executions.findById(id).orElseThrow(AuditExecutionQueryService::executionNotFound);
    }

    /**
     * 080. 객체를 대상으로 한 실행. ADMIN은 전체, 그 밖의 역할은 실행자가 본인인 기록(본인을 대신한 Agent 포함)만 본다. 조회 범위 밖 유형의 객체는 없는
     * 객체와 같은 404로 막아 있다는 사실도 알리지 않는다(077과 같은 규칙).
     */
    public Page<AuditExecutionResponse> objectExecutions(
            LoginAccount viewer,
            long objectId,
            ObjectExecutionSearchRequest request,
            Pageable pageable) {
        return objectExecutions(viewer, DataPurpose.VIEW, objectId, request, pageable);
    }

    /** 위와 같고, 읽는 용도를 정한다. Agent는 모델 용도로 불러 대상 표시 이름의 민감 값을 늘 가린다(384·390). */
    public Page<AuditExecutionResponse> objectExecutions(
            LoginAccount viewer,
            DataPurpose purpose,
            long objectId,
            ObjectExecutionSearchRequest request,
            Pageable pageable) {
        DataAccess access = dataAccessPolicy.of(viewer, purpose);
        history.findObjectTypeId(objectId)
                .filter(access::canView)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", OBJECT_INSTANCE)));
        Filter filter =
                new Filter(
                        isAdmin(viewer) ? null : viewer.id(),
                        null,
                        request.status(),
                        null,
                        null,
                        null,
                        OBJECT_INSTANCE,
                        objectId,
                        request.from(),
                        request.to());
        return history.search(filter, null, access, pageable);
    }

    private static boolean isAdmin(LoginAccount viewer) {
        return Objects.equals(ADMIN, viewer.roleCode());
    }

    private static BusinessException executionNotFound() {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "action_execution"));
    }

    private static BusinessException forbidden() {
        return new BusinessException(
                CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", ADMIN));
    }
}

package com.axcore.platform.action.service;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse;
import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.dto.AuditEntityChangeResponse;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.repository.EntityChangeRepository;
import com.axcore.platform.audit.service.AuditValueMasker;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 기록 하나의 결과·차단 이유 조회 (API 092, FN-ACT-06·FN-LOG-02). 091에서 BLOCKED로 돌려받은 실행도 같은 id로 본다. 실행자
 * 본인(Agent가 본인을 대신한 실행 포함)과 ADMIN만 볼 수 있다. Agent 조회 기록(AGENT_QUERY)은 공개하지 않으므로 없는 기록처럼 404다.
 *
 * <p>input과 변경 내용의 민감 속성 값은 보는 사람의 범위로 가린다. 감사 097·098과 같은 가림({@link AuditValueMasker})을 쓰므로 같은 기록은
 * 어느 API로 봐도 같게 가려진다. ADMIN도 허용 행대로 가린다(384·390).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionExecutionQueryService {

    private static final String ADMIN = "ADMIN";

    private final ActionExecutionRepository executions;
    private final EntityChangeRepository changes;
    private final AuditValueMasker masker;
    private final DataAccessPolicy dataAccessPolicy;
    private final RestrictedViewRecorder restrictedViews;

    public ActionExecutionDetailResponse get(LoginAccount viewer, long id) {
        ActionExecution execution =
                executions
                        .findById(id)
                        .filter(found -> found.getEventKind() != EventKind.AGENT_QUERY)
                        // 민감 값 열람 기록(394)은 Action 결과가 아니다. 096에서 ADMIN만 본다.
                        .filter(found -> found.getEventKind() != EventKind.RESTRICTED_VIEW)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "action_execution")));
        if (!ADMIN.equals(viewer.roleCode())
                && !Objects.equals(execution.getActorId(), viewer.id())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", ADMIN));
        }
        DataAccess access = dataAccessPolicy.of(viewer);
        Masked<Map<String, Object>> input =
                masker.maskInput(execution.getActionDefinitionId(), execution.getInput(), access);
        Masked<Map<String, Object>> result =
                masker.maskResult(
                        execution.getActionDefinitionId(),
                        execution.getStatus(),
                        execution.getResult(),
                        access);
        Masked<List<AuditEntityChangeResponse>> changed =
                masker.maskChanges(
                        changes.findByActionExecutionIdOrderByIdAsc(id).stream()
                                .map(AuditEntityChangeResponse::from)
                                .toList(),
                        access);
        TreeSet<String> restricted = new TreeSet<>(input.restrictedPropertyCodes());
        restricted.addAll(result.restrictedPropertyCodes());
        restricted.addAll(changed.restrictedPropertyCodes());
        TreeSet<String> revealed = new TreeSet<>(input.revealedPropertyCodes());
        revealed.addAll(result.revealedPropertyCodes());
        revealed.addAll(changed.revealedPropertyCodes());
        restrictedViews.record(
                viewer,
                "ACTION_RESULT",
                List.of(new Seen("action_execution", id, List.copyOf(revealed))));
        return ActionExecutionDetailResponse.of(
                execution,
                input.value(),
                result.value(),
                masker.maskBlockedReason(
                        execution.getActionDefinitionId(),
                        execution.getStatus(),
                        execution.getBlockedReason(),
                        execution.getResult(),
                        access),
                changed.value().stream()
                        .map(
                                change ->
                                        new EntityChangeResponse(
                                                change.tableName(),
                                                change.rowKey(),
                                                change.changeKind(),
                                                change.beforeData(),
                                                change.afterData()))
                        .toList(),
                List.copyOf(restricted));
    }
}

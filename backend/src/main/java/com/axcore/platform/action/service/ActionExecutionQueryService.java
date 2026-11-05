package com.axcore.platform.action.service;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse;
import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.repository.EntityChangeRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 기록 하나의 결과·차단 이유 조회 (API 092, FN-ACT-06·FN-LOG-02). 091에서 BLOCKED로 돌려받은 실행도 같은 id로 본다. 실행자
 * 본인(Agent가 본인을 대신한 실행 포함)과 ADMIN만 볼 수 있다. Agent 조회 기록(AGENT_QUERY)은 공개하지 않으므로 없는 기록처럼 404다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionExecutionQueryService {

    private static final String ADMIN = "ADMIN";

    private final ActionExecutionRepository executions;
    private final EntityChangeRepository changes;

    public ActionExecutionDetailResponse get(LoginAccount viewer, long id) {
        ActionExecution execution =
                executions
                        .findById(id)
                        .filter(found -> found.getEventKind() != EventKind.AGENT_QUERY)
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
        return ActionExecutionDetailResponse.of(
                execution, changes.findByActionExecutionIdOrderByIdAsc(id));
    }
}

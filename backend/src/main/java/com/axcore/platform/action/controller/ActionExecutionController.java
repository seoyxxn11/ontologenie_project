package com.axcore.platform.action.controller;

import com.axcore.platform.action.dto.ActionExecutionRequest;
import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Action 실행 요청 (API 091). 권한·입력·업무 조건으로 막혀도 실행 기록이 남으므로 200 + status=BLOCKED다(D1). 오류 코드는 기록을 만들 수
 * 없을 때만 쓴다(400 요청 형식, 404 없는 Action, 409 requestId 재사용). 화면 요청은 늘 WEB으로 기록한다.
 */
@RestController
@RequestMapping("/api/v1/actions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ActionExecutionController {

    private final ActionExecutionService actionExecutionService;

    @PostMapping("/{code}/executions")
    public ApiResponse<ActionExecutionResponse> execute(
            @PathVariable("code") @Size(max = 100) String code,
            @Valid @RequestBody ActionExecutionRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        if (request.agentMessageId() != null) {
            throw BusinessException.invalidField(
                    "agentMessageId", "Agent 경유 실행은 서버 안에서만 요청할 수 있습니다.");
        }
        if ((request.targetType() == null) != (request.targetId() == null)) {
            throw BusinessException.invalidField(
                    "targetType", "targetType과 targetId는 함께 보내거나 함께 생략해야 합니다.");
        }
        return ApiResponse.of(
                actionExecutionService.execute(
                        new ActionExecutionCommand(
                                loginAccount,
                                code,
                                request.requestId(),
                                request.targetType(),
                                request.targetId(),
                                request.input(),
                                Boolean.TRUE.equals(request.confirmed()),
                                EntryPoint.WEB,
                                null)));
    }
}

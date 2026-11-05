package com.axcore.platform.action.service;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.handler.ActionContext;
import com.axcore.platform.action.handler.ActionHandler;
import com.axcore.platform.action.handler.ActionHandlerRegistry;
import com.axcore.platform.action.handler.ActionPreparation;
import com.axcore.platform.action.handler.ActionTarget;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.audit.service.AuditSanitizer;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업무 Action 공통 실행기 (API 091, FN-ACT-04·05, FN-LOG-02, Jira 207·210·213).
 *
 * <ol>
 *   <li>같은 requestId의 기록이 있으면 새로 실행하지 않는다. 같은 요청이면 그 기록을 돌려주고, 다른 요청이면 409다.
 *   <li>관리 Action·비활성·Handler 구현 전·권한·입력 형식(inputSchema)·확인을 검사한다. 막히면 BLOCKED를 따로 기록하고 200으로
 *       돌려준다(D1).
 *   <li>Handler가 업무 조건을 검사하고 저장을 준비한다. 막히면 BLOCKED다. 통과하면 실행 기록(SUCCEEDED)과 Handler의 저장을 한 트랜잭션에
 *       커밋한다.
 *   <li>예상하지 못한 오류는 트랜잭션을 되돌리고 FAILED를 따로 기록한다. 자세한 원인은 서버 로그에만 남긴다.
 * </ol>
 *
 * <p>실행 기록의 대상(targetType·targetId)은 Handler가 입력으로 정한다. 요청이 대상을 보내면 그 값과 같아야 하고, 다르면 400이다.
 *
 * <p>같은 requestId가 동시에 들어오면 request_id UNIQUE 제약이 하나만 남기고, 나중 요청은 먼저 저장된 기록을 돌려받는다.
 */
@Slf4j
@Service
public class ActionExecutionService {

    static final String FAILED_MESSAGE = "Action을 실행하지 못했습니다. 관리자에게 문의해 주세요.";
    static final String HANDLER_NOT_READY_MESSAGE =
            "Handler 구현 전인 Action입니다. 개발자가 Handler를 배포한 뒤 실행할 수 있습니다.";

    private final ActionDefinitionRepository definitions;
    private final ActionReferenceRepository references;
    private final ActionHandlerRegistry handlers;
    private final InputSchemaValidator inputSchemas;
    private final ActionExecutionRepository executions;
    private final AuditRecorder audits;
    private final JsonMapper json;
    private final TransactionTemplate transactionTemplate;

    public ActionExecutionService(
            ActionDefinitionRepository definitions,
            ActionReferenceRepository references,
            ActionHandlerRegistry handlers,
            InputSchemaValidator inputSchemas,
            ActionExecutionRepository executions,
            AuditRecorder audits,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.definitions = definitions;
        this.references = references;
        this.handlers = handlers;
        this.inputSchemas = inputSchemas;
        this.executions = executions;
        this.audits = audits;
        this.json = json;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ActionExecutionResponse execute(ActionExecutionCommand command) {
        if ((command.entryPoint() == EntryPoint.AGENT) != (command.agentMessageId() != null)) {
            throw new IllegalArgumentException("Agent 실행에만 agentMessageId가 있어야 합니다.");
        }
        if (command.agentMessageId() != null
                && !references.isQuestionOf(command.agentMessageId(), command.actor().id())) {
            throw new IllegalArgumentException("agentMessageId가 실행자 본인의 질문 메시지가 아닙니다.");
        }
        ActionDefinition definition =
                definitions
                        .findByCode(command.code())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "action_definition",
                                                        "code",
                                                        command.code())));
        ActionHandler handler = handlers.find(definition.getHandlerKey()).orElse(null);
        ActionTarget target = handler == null ? null : handler.target(command.input()).orElse(null);
        requireRequestedTarget(command, target);
        Optional<ActionExecution> previous = executions.findByRequestId(command.requestId());
        if (previous.isPresent()) {
            return replay(previous.get(), definition, command, target);
        }

        Optional<String> gate = gateReason(definition, handler, command);
        if (gate.isPresent()) {
            return recordSeparately(
                    definition, command, target, ExecutionStatus.BLOCKED, gate.get());
        }

        ActionContext context =
                new ActionContext(
                        definition,
                        command.actor(),
                        command.entryPoint(),
                        command.input(),
                        command.agentMessageId());
        try {
            Long executionId =
                    transactionTemplate.execute(
                            status ->
                                    runInTransaction(
                                            handler, context, definition, command, target));
            return ActionExecutionResponse.from(executions.findById(executionId).orElseThrow());
        } catch (Blocked blocked) {
            return recordSeparately(
                    definition, command, target, ExecutionStatus.BLOCKED, blocked.getMessage());
        } catch (DataIntegrityViolationException ex) {
            Optional<ActionExecution> concurrent = executions.findByRequestId(command.requestId());
            if (concurrent.isPresent()) {
                return replay(concurrent.get(), definition, command, target);
            }
            log.error("Action 실행 실패 code={}", definition.getCode(), ex);
            return recordSeparately(
                    definition, command, target, ExecutionStatus.FAILED, FAILED_MESSAGE);
        } catch (RuntimeException ex) {
            log.error("Action 실행 실패 code={}", definition.getCode(), ex);
            return recordSeparately(
                    definition, command, target, ExecutionStatus.FAILED, FAILED_MESSAGE);
        }
    }

    private Long runInTransaction(
            ActionHandler handler,
            ActionContext context,
            ActionDefinition definition,
            ActionExecutionCommand command,
            ActionTarget target) {
        ActionPreparation preparation = handler.prepare(context);
        if (preparation instanceof ActionPreparation.Blocked blocked) {
            throw new Blocked(blocked.reason());
        }
        ActionPreparation.Ready ready = (ActionPreparation.Ready) preparation;
        Long executionId =
                audits.record(
                        entry(definition, command, target, ExecutionStatus.SUCCEEDED, null)
                                .result(ready.result())
                                .changes(ready.changes())
                                .build());
        ready.write().accept(executionId);
        return executionId;
    }

    /**
     * 요청이 대상을 보냈고 Handler가 입력으로 대상을 정했으면 둘이 같아야 한다. 대상은 고칠 수 없는 실행 기록과 객체별 이력에 쓰이므로 요청 값을 그대로 믿지
     * 않는다.
     */
    private static void requireRequestedTarget(
            ActionExecutionCommand command, ActionTarget target) {
        // 서버가 대상을 정하지 못했으면(형식이 틀린 입력, 관리 Action, Handler 없음) 보낸 값은 쓰지 않고 대상 없이 기록한다.
        // 이런 요청은 뒤의 검사에서 BLOCKED·FAILED로 남아야 하므로 여기서 400으로 끊지 않는다.
        if (target == null || (command.targetType() == null && command.targetId() == null)) {
            return;
        }
        boolean same =
                target.type().equals(command.targetType())
                        && Objects.equals(target.id(), command.targetId());
        if (!same) {
            throw BusinessException.invalidField(
                    "targetId", "대상은 입력으로 정해집니다. 보낸 targetType·targetId가 입력의 대상과 다릅니다. 생략해도 됩니다.");
        }
    }

    /**
     * 실행하기 전에 막는 조건. 091의 검사 순서(관리 Action → 비활성 → Handler 구현 전 → 권한 → 입력 형식 → 확인)대로 검사해 첫 이유를 돌려준다.
     * Handler 구현 전은 권한보다 먼저 본다. 실행할 수 없는 Action에 권한 차단 사유를 보여 주지 않기 위해서다.
     */
    private Optional<String> gateReason(
            ActionDefinition definition, ActionHandler handler, ActionExecutionCommand command) {
        if (definition.getActionMode() == ActionMode.RECORD) {
            return Optional.of("직접 실행할 수 없는 Action입니다. 해당 관리 화면에서 실행해 주세요.");
        }
        if (!definition.isActive()) {
            return Optional.of("사용이 중지된 Action입니다.");
        }
        if (handler == null) {
            return Optional.of(HANDLER_NOT_READY_MESSAGE);
        }
        if (!references.roleHasPermission(
                command.actor().roleCode(), definition.getRequiredPermission())) {
            return Optional.of(definition.getRequiredPermission() + " 권한이 없습니다.");
        }
        Optional<String> inputViolation =
                inputSchemas
                        .firstViolation(definition.getInputSchema(), command.input())
                        .map(violation -> "입력이 형식에 맞지 않습니다. " + violation);
        if (inputViolation.isPresent()) {
            return inputViolation;
        }
        if (definition.isRequiresConfirmation() && !command.confirmed()) {
            return Optional.of("실행 전에 확인이 필요한 Action입니다. 확인한 뒤 다시 요청해 주세요.");
        }
        return Optional.empty();
    }

    /**
     * 같은 requestId의 기록. 같은 사람이 같은 Action·대상·입력으로 다시 보낸 것이면 그 기록을 돌려주고, 아니면 409다. 기록된 입력은 비밀값을 뺀
     * 모양이므로 새 입력도 같은 모양으로 바꿔 비교한다.
     */
    private ActionExecutionResponse replay(
            ActionExecution previous,
            ActionDefinition definition,
            ActionExecutionCommand command,
            ActionTarget target) {
        boolean same =
                Objects.equals(previous.getActionDefinitionId(), definition.getId())
                        && Objects.equals(previous.getActorId(), command.actor().id())
                        && Objects.equals(
                                previous.getTargetType(), target == null ? null : target.type())
                        && Objects.equals(
                                previous.getTargetId(), target == null ? null : target.id())
                        && asJson(previous.getInput())
                                .equals(asJson(AuditSanitizer.clean(command.input())));
        if (!same) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT,
                    "같은 requestId가 다른 입력으로 이미 사용되었습니다.",
                    Map.of("field", "requestId"));
        }
        return ActionExecutionResponse.from(previous);
    }

    /**
     * 비교용 JSON. 문자열로 썼다가 다시 읽어 숫자 표현을 맞춘다. DB에서 읽은 기록은 Integer, 서버 안 호출(Agent)은 Long일 수 있는데 Jackson
     * 노드는 둘을 다르게 본다.
     */
    private JsonNode asJson(Map<String, Object> value) {
        return json.readTree(json.writeValueAsString(value));
    }

    /** 차단·실패를 업무와 따로 커밋한다. 같은 requestId가 동시에 저장됐으면 그 기록을 돌려준다. */
    private ActionExecutionResponse recordSeparately(
            ActionDefinition definition,
            ActionExecutionCommand command,
            ActionTarget target,
            ExecutionStatus status,
            String reason) {
        AuditEntry.AuditEntryBuilder entry = entry(definition, command, target, status, reason);
        if (status == ExecutionStatus.FAILED) {
            entry.result(Map.of("errorMessage", reason));
        }
        try {
            Long id = audits.recordSeparately(entry.build());
            return ActionExecutionResponse.from(executions.findById(id).orElseThrow());
        } catch (DataIntegrityViolationException ex) {
            ActionExecution concurrent =
                    executions.findByRequestId(command.requestId()).orElseThrow(() -> ex);
            return replay(concurrent, definition, command, target);
        }
    }

    private static AuditEntry.AuditEntryBuilder entry(
            ActionDefinition definition,
            ActionExecutionCommand command,
            ActionTarget target,
            ExecutionStatus status,
            String blockedReason) {
        Map<String, Object> input = new LinkedHashMap<>(command.input());
        return AuditEntry.builder()
                .eventKind(EventKind.ACTION)
                .actor(
                        command.entryPoint() == EntryPoint.AGENT
                                ? Actor.agent(command.actor())
                                : Actor.user(command.actor()))
                .entryPoint(command.entryPoint())
                .status(status)
                .requestId(command.requestId())
                .actionDefinitionId(definition.getId())
                .checkedPermissionCode(definition.getRequiredPermission())
                .targetType(target == null ? null : target.type())
                .targetId(target == null ? null : target.id())
                .agentMessageId(command.agentMessageId())
                .input(input)
                .blockedReason(status == ExecutionStatus.BLOCKED ? blockedReason : null);
    }

    /** Handler가 업무 조건에서 막았다. 트랜잭션을 되돌리려고 예외로 빠져나온다. */
    private static final class Blocked extends RuntimeException {
        Blocked(String reason) {
            super(reason, null, false, false);
        }
    }
}

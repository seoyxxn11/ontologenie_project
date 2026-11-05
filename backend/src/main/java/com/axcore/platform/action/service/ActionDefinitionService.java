package com.axcore.platform.action.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.domain.ActionKind;
import com.axcore.platform.action.domain.ActionMode;
import com.axcore.platform.action.domain.ActionTargetKind;
import com.axcore.platform.action.dto.ActionDefinitionCreateRequest;
import com.axcore.platform.action.dto.ActionDefinitionResponse;
import com.axcore.platform.action.dto.ActionDefinitionUpdateRequest;
import com.axcore.platform.action.handler.ActionHandlerRegistry;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.ActionDefinitionLookup;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Action 정의 관리 (API 087~090, FN-ACT-01·08, Action 정의서 2·4절).
 *
 * <ul>
 *   <li>목록(087)·등록(088)·수정(090)은 ADMIN만 쓴다. 조회(089)는 ADMIN이거나 그 Action의 권한을 가진 역할이 쓴다.
 *   <li>API로는 업무 Action(EXECUTE)만 등록·수정한다. 관리 Action(RECORD)은 도메인 API의 감사 기록이 깨지지 않게 Flyway로만 넣고
 *       고치지 않는다.
 *   <li>handlerKey는 서버에 아직 없는 Handler 이름도 받는다. 관리자가 정의를 먼저 등록하고 개발자가 Handler를 나중에 배포한다. 배포 전에는 응답의
 *       handlerReady가 false이고 091이 BLOCKED를 남긴다(11-05 결정, Jira 287).
 *   <li>등록·수정은 관리 Action이라 성공하면 업무와 같은 트랜잭션에 ACTION 기록을 남기고, 막히면 트랜잭션이 끝난 뒤 BLOCKED를 따로 남긴다
 *       (AdminAccountService와 같은 방식).
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActionDefinitionService {

    static final String CREATE_ACTION = "CREATE_ACTION_DEFINITION";
    static final String UPDATE_ACTION = "UPDATE_ACTION_DEFINITION";
    static final String MANAGE_PERMISSION = "ACTION_DEFINITION_MANAGE";

    private static final String TABLE = "action_definition";
    private static final String DUPLICATE_CODE_MESSAGE = "같은 식별자로 등록된 항목이 있습니다.";
    private static final String RECORD_DEFINITION_MESSAGE = "관리 Action 정의는 바꿀 수 없습니다.";

    private final ActionDefinitionRepository definitions;
    private final ActionReferenceRepository references;
    private final ActionHandlerRegistry handlers;
    private final InputSchemaValidator inputSchemas;
    private final AuditRecorder audits;
    private final ActionDefinitionLookup actionDefinitions;
    private final TransactionTemplate transactionTemplate;

    public ActionDefinitionService(
            ActionDefinitionRepository definitions,
            ActionReferenceRepository references,
            ActionHandlerRegistry handlers,
            InputSchemaValidator inputSchemas,
            AuditRecorder audits,
            ActionDefinitionLookup actionDefinitions,
            PlatformTransactionManager transactionManager) {
        this.definitions = definitions;
        this.references = references;
        this.handlers = handlers;
        this.inputSchemas = inputSchemas;
        this.audits = audits;
        this.actionDefinitions = actionDefinitions;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** API 087. */
    @Transactional(readOnly = true)
    public Page<ActionDefinitionResponse> search(
            LoginAccount actor, Boolean active, ActionKind actionKind, Pageable pageable) {
        requireAdmin(actor, RoleCode.ADMIN.name());
        return definitions.search(active, actionKind, pageable).map(this::toResponse);
    }

    /** API 089. 화면이 확인 창과 입력 형식을 정할 때 쓴다. ADMIN이 아니면 그 Action의 권한을 가진 역할만 본다. */
    @Transactional(readOnly = true)
    public ActionDefinitionResponse get(LoginAccount actor, String code) {
        ActionDefinition definition = findDefinition(code);
        boolean allowed =
                RoleCode.ADMIN.name().equals(actor.roleCode())
                        || references.roleHasPermission(
                                actor.roleCode(), definition.getRequiredPermission());
        if (!allowed) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN,
                    Map.of("requiredPermission", definition.getRequiredPermission()));
        }
        return toResponse(definition);
    }

    /** API 088. 새 정의는 rowVersion 0, isActive를 생략하면 true다. */
    public ActionDefinitionResponse create(
            LoginAccount actor, ActionDefinitionCreateRequest request) {
        Long definitionId = actionDefinitions.idOf(CREATE_ACTION);
        Map<String, Object> input = createInput(request);
        return runBlockable(
                actor,
                definitionId,
                null,
                input,
                () -> createInTransaction(actor, request, definitionId, input));
    }

    /** API 090. 보낸 필드만 바꾼다. 바꾼 뒤의 값 전체가 정의 규칙(대상 종류·유형, 권한)을 지켜야 한다. */
    public ActionDefinitionResponse update(
            LoginAccount actor, String code, ActionDefinitionUpdateRequest request) {
        Long definitionId = actionDefinitions.idOf(UPDATE_ACTION);
        Long targetId = definitions.findByCode(code).map(ActionDefinition::getId).orElse(null);
        Map<String, Object> input = updateInput(code, request);
        return runBlockable(
                actor,
                definitionId,
                targetId,
                input,
                () -> updateInTransaction(actor, code, request, definitionId, input));
    }

    private ActionDefinitionResponse createInTransaction(
            LoginAccount actor,
            ActionDefinitionCreateRequest request,
            Long definitionId,
            Map<String, Object> input) {
        requireAdmin(actor, MANAGE_PERMISSION);
        if (request.actionMode() != null && request.actionMode() != ActionMode.EXECUTE) {
            throw BusinessException.invalidField(
                    "actionMode", "API로는 업무 Action(EXECUTE)만 등록할 수 있습니다.");
        }
        requireTarget(request.targetKind(), request.targetObjectTypeId());
        requireInputSchema(request.inputSchema());
        requirePermission(request.requiredPermission());
        if (definitions.existsByCode(request.code())) {
            throw duplicateCode();
        }
        ActionDefinition definition =
                ActionDefinition.registerExecutable(
                        request.code(),
                        request.name(),
                        request.description(),
                        request.targetKind(),
                        request.targetObjectTypeId(),
                        request.handlerKey(),
                        request.inputSchema(),
                        request.actionKind(),
                        request.requiresConfirmation(),
                        request.requiredPermission(),
                        request.isActive() == null || request.isActive());
        try {
            definitions.saveAndFlush(definition);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateCode();
        }
        audits.record(
                success(actor, definitionId, definition.getId(), input)
                        .changes(
                                List.of(
                                        ChangeEntry.insert(
                                                TABLE,
                                                Map.of("id", definition.getId()),
                                                snapshot(definition))))
                        .build());
        return toResponse(definition);
    }

    private ActionDefinitionResponse updateInTransaction(
            LoginAccount actor,
            String code,
            ActionDefinitionUpdateRequest request,
            Long definitionId,
            Map<String, Object> input) {
        requireAdmin(actor, MANAGE_PERMISSION);
        if (request.hasCode()) {
            throw BusinessException.invalidField("code", "code는 바꿀 수 없습니다. 경로로만 지정합니다.");
        }
        if (request.hasActionMode()) {
            throw BusinessException.invalidField("actionMode", "actionMode는 등록한 뒤 바꿀 수 없습니다.");
        }
        if (!request.hasChange()) {
            throw BusinessException.invalidField("request", "바꿀 필드를 하나 이상 보내야 합니다.");
        }
        ActionDefinition definition = findDefinition(code);
        if (definition.getActionMode() == ActionMode.RECORD) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    RECORD_DEFINITION_MESSAGE,
                    Map.of("field", "actionMode", "actionMode", ActionMode.RECORD.name()));
        }
        if (request.getRowVersion() != definition.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.getRowVersion(),
                            "actual", definition.getRowVersion()));
        }

        ActionTargetKind targetKind =
                request.getTargetKind() == null
                        ? definition.getTargetKind()
                        : request.getTargetKind();
        Long targetObjectTypeId =
                request.hasTargetObjectTypeId()
                        ? request.getTargetObjectTypeId()
                        : definition.getTargetObjectTypeId();
        requireTarget(targetKind, targetObjectTypeId);
        if (request.getInputSchema() != null) {
            requireInputSchema(request.getInputSchema());
        }
        if (request.getRequiredPermission() != null) {
            requirePermission(request.getRequiredPermission());
        }

        Map<String, Object> before = snapshot(definition);
        if (request.getName() != null) {
            definition.rename(request.getName());
        }
        if (request.getDescription() != null) {
            definition.describe(request.getDescription());
        }
        definition.changeTarget(targetKind, targetObjectTypeId);
        if (request.getHandlerKey() != null) {
            definition.changeHandler(request.getHandlerKey());
        }
        if (request.getInputSchema() != null) {
            definition.changeInputSchema(request.getInputSchema());
        }
        if (request.getActionKind() != null) {
            definition.changeActionKind(request.getActionKind());
        }
        if (request.getRequiresConfirmation() != null) {
            definition.changeConfirmation(request.getRequiresConfirmation());
        }
        if (request.getRequiredPermission() != null) {
            definition.changePermission(request.getRequiredPermission());
        }
        if (request.getIsActive() != null) {
            definition.changeActive(request.getIsActive());
        }
        // 응답의 rowVersion과 기록의 변경 후 값이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        try {
            definitions.flush();
        } catch (OptimisticLockingFailureException ex) {
            // 버전을 비교한 뒤 다른 요청이 먼저 바꿨다.
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT, Map.of("field", "rowVersion"));
        }
        Map<String, Object> after = snapshot(definition);

        AuditEntry.AuditEntryBuilder entry =
                success(actor, definitionId, definition.getId(), input);
        changedColumns(before, after)
                .ifPresent(
                        diff ->
                                entry.changes(
                                        List.of(
                                                ChangeEntry.update(
                                                        TABLE,
                                                        Map.of("id", definition.getId()),
                                                        diff.before(),
                                                        diff.after()))));
        audits.record(entry.build());
        return toResponse(definition);
    }

    /** SYSTEM이면 대상 객체 유형이 없어야 한다. OBJECT는 유형을 비워도 되고, 정하면 있는 유형이어야 한다. */
    private void requireTarget(ActionTargetKind targetKind, Long targetObjectTypeId) {
        if (targetObjectTypeId == null) {
            return;
        }
        if (targetKind == ActionTargetKind.SYSTEM) {
            throw BusinessException.invalidField(
                    "targetObjectTypeId", "targetKind가 SYSTEM이면 targetObjectTypeId는 null이어야 합니다.");
        }
        if (!references.objectTypeExists(targetObjectTypeId)) {
            throw BusinessException.invalidField("targetObjectTypeId", "없는 객체 유형입니다.");
        }
    }

    /** handlerReady는 저장하지 않고 응답할 때 서버의 Handler 목록으로 계산한다. */
    private ActionDefinitionResponse toResponse(ActionDefinition definition) {
        return ActionDefinitionResponse.from(
                definition, handlers.isRegistered(definition.getHandlerKey()));
    }

    /** 091이 검사하지 못하는 키워드가 있으면 거절한다. 그대로 두면 정의에 적힌 규칙이 실행 때 조용히 무시된다. */
    private void requireInputSchema(Map<String, Object> inputSchema) {
        List<String> problems = inputSchemas.unsupported(inputSchema);
        if (!problems.isEmpty()) {
            throw BusinessException.invalidField("inputSchema", problems.get(0));
        }
    }

    private void requirePermission(String permissionCode) {
        if (!references.permissionExists(permissionCode)) {
            throw BusinessException.invalidField("requiredPermission", "등록되지 않은 권한 코드입니다.");
        }
    }

    /** 트랜잭션을 실행하고, 업무 규칙으로 막히면 트랜잭션이 끝난 뒤 BLOCKED 기록을 남기고 같은 오류를 다시 던진다. */
    private <T> T runBlockable(
            LoginAccount actor,
            Long definitionId,
            Long targetId,
            Map<String, Object> input,
            Supplier<T> work) {
        try {
            return transactionTemplate.execute(tx -> work.get());
        } catch (BusinessException ex) {
            recordBlocked(actor, definitionId, targetId, input, ex);
            throw ex;
        }
    }

    private void recordBlocked(
            LoginAccount actor,
            Long definitionId,
            Long targetId,
            Map<String, Object> input,
            BusinessException ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorCode", ex.getErrorCode().code());
        result.put("details", ex.getDetails());
        try {
            audits.recordSeparately(
                    AuditEntry.builder()
                            .eventKind(EventKind.ACTION)
                            .actor(Actor.user(actor))
                            .entryPoint(EntryPoint.WEB)
                            .status(ExecutionStatus.BLOCKED)
                            .actionDefinitionId(definitionId)
                            .checkedPermissionCode(MANAGE_PERMISSION)
                            .targetType(targetId == null ? null : TABLE)
                            .targetId(targetId)
                            .input(input)
                            .result(result)
                            .blockedReason(blockedReason(ex))
                            .build());
        } catch (RuntimeException recordFailure) {
            // 기록 실패가 원래 오류 응답을 가리지 않게 한다.
            log.error("차단 기록 저장 실패: action={}", definitionId, recordFailure);
            ex.addSuppressed(recordFailure);
        }
    }

    private static AuditEntry.AuditEntryBuilder success(
            LoginAccount actor, Long definitionId, Long targetId, Map<String, Object> input) {
        return AuditEntry.builder()
                .eventKind(EventKind.ACTION)
                .actor(Actor.user(actor))
                .entryPoint(EntryPoint.WEB)
                .status(ExecutionStatus.SUCCEEDED)
                .actionDefinitionId(definitionId)
                .checkedPermissionCode(MANAGE_PERMISSION)
                .targetType(TABLE)
                .targetId(targetId)
                .input(input)
                .result(Map.of("actionDefinitionId", targetId));
    }

    private static Map<String, Object> createInput(ActionDefinitionCreateRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("code", request.code());
        input.put("targetKind", request.targetKind() == null ? null : request.targetKind().name());
        input.put("targetObjectTypeId", request.targetObjectTypeId());
        input.put("handlerKey", request.handlerKey());
        input.put("actionKind", request.actionKind() == null ? null : request.actionKind().name());
        input.put("requiresConfirmation", request.requiresConfirmation());
        input.put("requiredPermission", request.requiredPermission());
        input.put("isActive", request.isActive());
        return input;
    }

    private static Map<String, Object> updateInput(
            String code, ActionDefinitionUpdateRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("code", code);
        if (request.getName() != null) {
            input.put("name", request.getName());
        }
        if (request.getTargetKind() != null) {
            input.put("targetKind", request.getTargetKind().name());
        }
        if (request.hasTargetObjectTypeId()) {
            input.put("targetObjectTypeId", request.getTargetObjectTypeId());
        }
        if (request.getHandlerKey() != null) {
            input.put("handlerKey", request.getHandlerKey());
        }
        if (request.getActionKind() != null) {
            input.put("actionKind", request.getActionKind().name());
        }
        if (request.getRequiresConfirmation() != null) {
            input.put("requiresConfirmation", request.getRequiresConfirmation());
        }
        if (request.getRequiredPermission() != null) {
            input.put("requiredPermission", request.getRequiredPermission());
        }
        if (request.getIsActive() != null) {
            input.put("isActive", request.getIsActive());
        }
        input.put("rowVersion", request.getRowVersion());
        return input;
    }

    private static String blockedReason(BusinessException ex) {
        Object reason = ex.getDetails().get("reason");
        return reason == null ? ex.getMessage() : ex.getMessage() + " " + reason;
    }

    /** entity_change에 남길 컬럼 값. */
    private static Map<String, Object> snapshot(ActionDefinition definition) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", definition.getCode());
        values.put("name", definition.getName());
        values.put("description", definition.getDescription());
        values.put("target_kind", definition.getTargetKind().name());
        values.put("target_object_type_id", definition.getTargetObjectTypeId());
        values.put("action_mode", definition.getActionMode().name());
        values.put("handler_key", definition.getHandlerKey());
        values.put("input_schema", definition.getInputSchema());
        values.put("action_kind", definition.getActionKind().name());
        values.put("requires_confirmation", definition.isRequiresConfirmation());
        values.put("required_permission", definition.getRequiredPermission());
        values.put("is_active", definition.isActive());
        values.put("row_version", definition.getRowVersion());
        return values;
    }

    private static Optional<Diff> changedColumns(
            Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        before.forEach(
                (column, value) -> {
                    if (!Objects.equals(value, after.get(column))) {
                        changedBefore.put(column, value);
                        changedAfter.put(column, after.get(column));
                    }
                });
        return changedBefore.isEmpty()
                ? Optional.empty()
                : Optional.of(new Diff(changedBefore, changedAfter));
    }

    private ActionDefinition findDefinition(String code) {
        return definitions
                .findByCode(code)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", TABLE, "code", code)));
    }

    private static BusinessException duplicateCode() {
        return new BusinessException(
                CommonErrorCode.CONFLICT, DUPLICATE_CODE_MESSAGE, Map.of("field", "code"));
    }

    private static void requireAdmin(LoginAccount actor, String requiredPermission) {
        if (!RoleCode.ADMIN.name().equals(actor.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", requiredPermission));
        }
    }

    private record Diff(Map<String, Object> before, Map<String, Object> after) {}
}

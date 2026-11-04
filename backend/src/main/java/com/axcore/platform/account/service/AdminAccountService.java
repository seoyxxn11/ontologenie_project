package com.axcore.platform.account.service;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.AccountStatus;
import com.axcore.platform.account.domain.Role;
import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.account.dto.AccountCreateRequest;
import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.account.dto.AccountUpdateRequest;
import com.axcore.platform.account.dto.RoleResponse;
import com.axcore.platform.account.repository.AccountRepository;
import com.axcore.platform.account.repository.RoleQueryRepository;
import com.axcore.platform.account.repository.RoleRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.ActionDefinitionLookup;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.auth.service.AccountSessionService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 계정·역할 관리 (API 004~008, FN-ADM-02·03). 모두 ADMIN만 쓸 수 있다.
 *
 * <p>생성(005)·변경(007)은 관리 Action이라 성공하면 업무와 같은 트랜잭션에 ACTION 기록을 남긴다. 막히면 트랜잭션이 끝난 뒤 BLOCKED를 따로 남긴다.
 * 트랜잭션 안에서 따로 남기면 잠근 계정 행을 새 트랜잭션의 외래 키 검사가 기다려 교착이 생길 수 있어서다. 세션 삭제도 변경이 커밋된 뒤에 한다.
 */
@Slf4j
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminAccountService {

    static final String CREATE_ACTION = "CREATE_ACCOUNT";
    static final String UPDATE_ACTION = "UPDATE_ACCOUNT";
    static final String MANAGE_PERMISSION = "ACCOUNT_MANAGE";

    private static final String TABLE = "account";
    private static final String DUPLICATE_LOGIN_ID_MESSAGE = "같은 식별자로 등록된 항목이 있습니다.";
    private static final String LAST_ADMIN_MESSAGE = "마지막 활성 관리자 계정의 역할이나 상태는 바꿀 수 없습니다.";

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final RoleQueryRepository roleQueryRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountSessionService sessionService;
    private final AuditRecorder audits;
    private final ActionDefinitionLookup actionDefinitions;
    private final TransactionTemplate transactionTemplate;

    public AdminAccountService(
            AccountRepository accountRepository,
            RoleRepository roleRepository,
            RoleQueryRepository roleQueryRepository,
            PasswordEncoder passwordEncoder,
            AccountSessionService sessionService,
            AuditRecorder audits,
            ActionDefinitionLookup actionDefinitions,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.roleQueryRepository = roleQueryRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.audits = audits;
        this.actionDefinitions = actionDefinitions;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** API 004. */
    @Transactional(readOnly = true)
    public Page<AccountResponse> search(
            LoginAccount actor,
            String q,
            AccountStatus status,
            RoleCode roleCode,
            Pageable pageable) {
        requireAdmin(actor, RoleCode.ADMIN.name());
        return accountRepository
                .search(likePattern(q), status, roleCode, pageable)
                .map(AccountResponse::from);
    }

    /** API 008. 역할 4개와 각 역할의 권한 코드. */
    @Transactional(readOnly = true)
    public List<RoleResponse> roles(LoginAccount actor) {
        requireAdmin(actor, RoleCode.ADMIN.name());
        return roleQueryRepository.findAllWithPermissions();
    }

    /** API 006. */
    @Transactional(readOnly = true)
    public AccountResponse get(LoginAccount actor, Long id) {
        requireAdmin(actor, RoleCode.ADMIN.name());
        return AccountResponse.from(findAccount(id));
    }

    /**
     * API 005. 새 계정은 ACTIVE·임시 비밀번호 상태다(D03). 임시 비밀번호는 해시로만 저장하고 기록·응답에 넣지 않는다(D04). 같은 로그인 ID로 동시에
     * 만들면 DB 제약이 하나만 남긴다.
     */
    public AccountResponse create(LoginAccount actor, AccountCreateRequest request) {
        Long definitionId = actionDefinitions.idOf(CREATE_ACTION);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("loginId", request.loginId());
        input.put("displayName", request.displayName());
        input.put("roleCode", request.roleCode().name());
        return runBlockable(
                actor,
                definitionId,
                null,
                input,
                () -> createInTransaction(actor, request, definitionId, input));
    }

    /**
     * API 007. 보낸 필드만 바꾼다. 역할을 바꾸거나 LOCKED·DISABLED로 바꾸면 그 계정의 세션을 모두 끊는다. 마지막 활성 ADMIN을 다른 역할이나
     * 비활성 상태로 바꾸지 못하게 막는다.
     */
    public AccountResponse update(LoginAccount actor, Long id, AccountUpdateRequest request) {
        Long definitionId = actionDefinitions.idOf(UPDATE_ACTION);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", id);
        if (request.displayName() != null) {
            input.put("displayName", request.displayName());
        }
        if (request.roleCode() != null) {
            input.put("roleCode", request.roleCode().name());
        }
        if (request.status() != null) {
            input.put("status", request.status().name());
        }
        input.put("rowVersion", request.rowVersion());
        UpdateResult result =
                runBlockable(
                        actor,
                        definitionId,
                        id,
                        input,
                        () -> updateInTransaction(actor, id, request, definitionId, input));
        if (result.expireSessions()) {
            sessionService.expireAll(id);
        }
        return result.account();
    }

    private AccountResponse createInTransaction(
            LoginAccount actor,
            AccountCreateRequest request,
            Long definitionId,
            Map<String, Object> input) {
        requireAdmin(actor, MANAGE_PERMISSION);
        PasswordPolicy.violation(request.loginId(), request.temporaryPassword())
                .ifPresent(
                        reason -> {
                            throw BusinessException.invalidField("temporaryPassword", reason);
                        });
        if (accountRepository.existsByLoginId(request.loginId())) {
            throw duplicateLoginId();
        }
        Role role = findRole(request.roleCode());
        Account account =
                Account.create(
                        request.loginId(),
                        request.displayName(),
                        passwordEncoder.encode(request.temporaryPassword()),
                        role);
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateLoginId();
        }
        audits.record(
                success(actor, definitionId, account.getId(), input)
                        .changes(
                                List.of(
                                        ChangeEntry.insert(
                                                TABLE,
                                                Map.of("id", account.getId()),
                                                snapshot(account, true))))
                        .build());
        return AccountResponse.from(account);
    }

    private UpdateResult updateInTransaction(
            LoginAccount actor,
            Long id,
            AccountUpdateRequest request,
            Long definitionId,
            Map<String, Object> input) {
        requireAdmin(actor, MANAGE_PERMISSION);
        if (!request.hasChange()) {
            throw BusinessException.invalidField(
                    "request", "displayName·roleCode·status 중 하나 이상 보내야 합니다.");
        }
        Account account = findAccount(id);
        if (request.rowVersion() != account.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", account.getRowVersion()));
        }
        Role role = request.roleCode() == null ? account.getRole() : findRole(request.roleCode());
        AccountStatus status = request.status() == null ? account.getStatus() : request.status();
        boolean roleChanged = !role.getCode().equals(account.getRole().getCode());
        if (removesActiveAdmin(account, role, status)) {
            requireAnotherActiveAdmin(account.getId(), roleChanged ? "roleCode" : "status");
        }

        Map<String, Object> before = snapshot(account, false);
        if (request.displayName() != null) {
            account.rename(request.displayName());
        }
        if (roleChanged) {
            account.changeRole(role);
        }
        if (request.status() != null) {
            account.changeStatus(request.status());
        }
        // 응답의 rowVersion과 기록의 변경 후 값이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        try {
            accountRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            // 버전을 비교한 뒤 다른 요청이 먼저 바꿨다.
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT, Map.of("field", "rowVersion"));
        }
        Map<String, Object> after = snapshot(account, false);

        AuditEntry.AuditEntryBuilder entry = success(actor, definitionId, id, input);
        changedColumns(before, after)
                .ifPresent(
                        diff ->
                                entry.changes(
                                        List.of(
                                                ChangeEntry.update(
                                                        TABLE,
                                                        Map.of("id", id),
                                                        diff.before(),
                                                        diff.after()))));
        audits.record(entry.build());

        boolean blocksLogin =
                request.status() == AccountStatus.LOCKED
                        || request.status() == AccountStatus.DISABLED;
        return new UpdateResult(AccountResponse.from(account), roleChanged || blocksLogin);
    }

    /** 바꾼 뒤 ADMIN 역할이 아니거나 ACTIVE가 아니게 되는 활성 ADMIN인가. */
    private static boolean removesActiveAdmin(
            Account account, Role newRole, AccountStatus newStatus) {
        boolean activeAdmin =
                account.getRole().getCode() == RoleCode.ADMIN
                        && account.getStatus() == AccountStatus.ACTIVE;
        return activeAdmin
                && (newRole.getCode() != RoleCode.ADMIN || newStatus != AccountStatus.ACTIVE);
    }

    private void requireAnotherActiveAdmin(Long accountId, String field) {
        boolean anotherAdmin =
                accountRepository.lockActiveAdminIds().stream()
                        .anyMatch(adminId -> !adminId.equals(accountId));
        if (!anotherAdmin) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    LAST_ADMIN_MESSAGE,
                    Map.of("field", field));
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
                .result(Map.of("accountId", targetId));
    }

    private static String blockedReason(BusinessException ex) {
        Object reason = ex.getDetails().get("reason");
        return reason == null ? ex.getMessage() : ex.getMessage() + " " + reason;
    }

    /** entity_change에 남길 컬럼 값. 비밀번호 해시는 넣지 않는다. */
    private static Map<String, Object> snapshot(Account account, boolean forInsert) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (forInsert) {
            values.put("login_id", account.getLoginId());
        }
        values.put("display_name", account.getDisplayName());
        values.put("role_id", account.getRole().getId());
        values.put("status", account.getStatus().name());
        if (forInsert) {
            values.put("must_change_password", account.isMustChangePassword());
        } else {
            values.put("failed_login_count", account.getFailedLoginCount());
        }
        values.put("row_version", account.getRowVersion());
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

    private Account findAccount(Long id) {
        return accountRepository
                .findWithRoleById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", TABLE, "id", id)));
    }

    private Role findRole(RoleCode code) {
        return roleRepository
                .findByCode(code)
                .orElseThrow(() -> new IllegalStateException("역할이 없습니다: " + code));
    }

    private static BusinessException duplicateLoginId() {
        return new BusinessException(
                CommonErrorCode.CONFLICT, DUPLICATE_LOGIN_ID_MESSAGE, Map.of("field", "loginId"));
    }

    private static void requireAdmin(LoginAccount actor, String requiredPermission) {
        if (!RoleCode.ADMIN.name().equals(actor.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", requiredPermission));
        }
    }

    /** 검색어를 LIKE 패턴으로 바꾼다. %, _, \ 는 글자 그대로 찾는다. 검색어가 없으면 모두 맞는 패턴이다. */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped =
                q.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private record UpdateResult(AccountResponse account, boolean expireSessions) {}

    private record Diff(Map<String, Object> before, Map<String, Object> after) {}
}

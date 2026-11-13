package com.axcore.platform.ontology.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.ActionDefinitionLookup;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.DefinitionEditAction;
import com.axcore.platform.ontology.repository.DefinitionSnapshotRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자가 정의 API로 직접 편집한 실행을 감사 기록(action_execution·entity_change)에 남긴다(Jira 187,
 * FN-ONT-07·FN-LOG-03).
 *
 * <p>정의 서비스(ObjectTypeService 등)는 후보 승인 반영기도 그대로 쓰므로, 기록은 서비스가 아니라 직접 편집 API(컨트롤러)가 이 기록기를 거쳐 남긴다.
 * 승인으로 바뀐 정의는 DECIDE_PROPOSAL 실행에 남는다.
 *
 * <ul>
 *   <li>성공: 편집과 같은 트랜잭션에서 SUCCEEDED 기록과 바뀐 행 하나의 전후를 남긴다. 전후는 DB 컬럼 그대로다. 수정했지만 값이 같으면 바뀐 행은 남기지
 *       않는다.
 *   <li>업무 거절(BusinessException): 편집은 롤백하고 BLOCKED 기록을 따로 커밋한다(계정 관리와 같은 방식).
 *   <li>수정의 변경 전은 서비스가 행을 잠그기 전에 읽는다. 수정 요청은 rowVersion을 함께 보내 그 사이 다른 수정이 있으면 서비스가 409로 거절하므로, 성공한
 *       수정의 변경 전은 정확하다.
 * </ul>
 */
@Slf4j
@Service
public class DefinitionEditRecorder {

    private final ActionDefinitionLookup actionDefinitions;
    private final AuditRecorder audits;
    private final DefinitionSnapshotRepository snapshots;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper json;
    private final DefinitionSideEffects sideEffects;

    public DefinitionEditRecorder(
            ActionDefinitionLookup actionDefinitions,
            AuditRecorder audits,
            DefinitionSnapshotRepository snapshots,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            JsonMapper json,
            DefinitionSideEffects sideEffects) {
        this.actionDefinitions = actionDefinitions;
        this.audits = audits;
        this.snapshots = snapshots;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.json = json;
        this.sideEffects = sideEffects;
    }

    /** 감사 기록의 입력. 경로의 대상 ID(있으면 {@code id})와 요청 본문 칸을 그대로 담는다. 정의 요청에는 비밀값이 없다. */
    public Map<String, Object> input(Long id, Object request) {
        return input("id", id, request);
    }

    /** 경로 ID의 이름이 대상 ID가 아닐 때(예: 속성 등록의 {@code objectTypeId}). */
    public Map<String, Object> input(String pathKey, Long pathId, Object request) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (pathId != null) {
            input.put(pathKey, pathId);
        }
        if (request != null) {
            input.putAll(
                    json.convertValue(
                            request, new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
        return input;
    }

    /** 등록. 만든 행의 ID는 결과에서 읽는다. */
    public <T> T create(
            LoginAccount actor,
            DefinitionEditAction action,
            Map<String, Object> input,
            Supplier<T> work,
            ToLongFunction<T> idOf) {
        requireKind(action, ChangeKind.INSERT);
        return run(actor, action, null, input, work, idOf);
    }

    /** 수정. */
    public <T> T update(
            LoginAccount actor,
            DefinitionEditAction action,
            long id,
            Map<String, Object> input,
            Supplier<T> work) {
        requireKind(action, ChangeKind.UPDATE);
        return run(actor, action, id, input, work, result -> id);
    }

    /** 삭제. 결과가 없는 삭제는 null을 돌려주는 작업으로 넘긴다. */
    public <T> T delete(
            LoginAccount actor,
            DefinitionEditAction action,
            long id,
            Map<String, Object> input,
            Supplier<T> work) {
        requireKind(action, ChangeKind.DELETE);
        return run(actor, action, id, input, work, result -> id);
    }

    private <T> T run(
            LoginAccount actor,
            DefinitionEditAction action,
            Long targetId,
            Map<String, Object> input,
            Supplier<T> work,
            ToLongFunction<T> idOf) {
        Long definitionId = actionDefinitions.idOf(action.name());
        Map<String, Object> recorded = input == null ? Map.of() : input;
        try {
            return transactionTemplate.execute(
                    tx -> edit(actor, action, definitionId, targetId, recorded, work, idOf));
        } catch (BusinessException ex) {
            recordBlocked(actor, action, definitionId, targetId, recorded, ex);
            throw ex;
        }
    }

    private <T> T edit(
            LoginAccount actor,
            DefinitionEditAction action,
            Long definitionId,
            Long targetId,
            Map<String, Object> input,
            Supplier<T> work,
            ToLongFunction<T> idOf) {
        Map<String, Object> before =
                targetId == null ? null : snapshots.find(action.table(), targetId).orElse(null);
        T result = work.get();
        entityManager.flush();
        long id = targetId != null ? targetId : idOf.applyAsLong(result);
        Map<String, Object> after =
                action.kind() == ChangeKind.DELETE
                        ? null
                        : snapshots.find(action.table(), id).orElse(null);
        Map<String, Object> rowKey = Map.of("id", id);
        List<ChangeEntry> changes =
                switch (action.kind()) {
                    case INSERT ->
                            after == null
                                    ? List.of()
                                    : List.of(ChangeEntry.insert(action.table(), rowKey, after));
                    case UPDATE ->
                            before == null || after == null || Objects.equals(before, after)
                                    ? List.of()
                                    : List.of(
                                            ChangeEntry.update(
                                                    action.table(), rowKey, before, after));
                    case DELETE ->
                            before == null
                                    ? List.of()
                                    : List.of(ChangeEntry.delete(action.table(), rowKey, before));
                };
        // 서비스가 요청 대상 밖의 정의 행을 덩달아 바꿨으면(예: 원천 정책으로 속성 민감 승격) 함께 남긴다(391).
        List<ChangeEntry> sideChanges = sideEffects.drain();
        if (!sideChanges.isEmpty()) {
            List<ChangeEntry> all = new ArrayList<>(changes);
            all.addAll(sideChanges);
            changes = all;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("targetTable", action.table());
        outcome.put("targetId", id);
        outcome.put("changedRows", changes.size());
        audits.record(
                entry(actor, definitionId, ExecutionStatus.SUCCEEDED, action.table(), id, input)
                        .result(outcome)
                        .changes(changes)
                        .build());
        return result;
    }

    private void recordBlocked(
            LoginAccount actor,
            DefinitionEditAction action,
            Long definitionId,
            Long targetId,
            Map<String, Object> input,
            BusinessException ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorCode", ex.getErrorCode().code());
        result.put("details", ex.getDetails());
        try {
            audits.recordSeparately(
                    entry(
                                    actor,
                                    definitionId,
                                    ExecutionStatus.BLOCKED,
                                    targetId == null ? null : action.table(),
                                    targetId,
                                    input)
                            .result(result)
                            .blockedReason(blockedReason(ex))
                            .build());
        } catch (RuntimeException recordFailure) {
            // 기록 실패가 원래 오류 응답을 가리지 않게 한다.
            log.error("정의 편집 차단 기록 저장 실패: action={}", action, recordFailure);
            ex.addSuppressed(recordFailure);
        }
    }

    private static AuditEntry.AuditEntryBuilder entry(
            LoginAccount actor,
            Long definitionId,
            ExecutionStatus status,
            String targetType,
            Long targetId,
            Map<String, Object> input) {
        return AuditEntry.builder()
                .eventKind(EventKind.ACTION)
                .actor(Actor.user(actor))
                .entryPoint(EntryPoint.WEB)
                .status(status)
                .actionDefinitionId(definitionId)
                .checkedPermissionCode(DefinitionEditAction.PERMISSION)
                .targetType(targetType)
                .targetId(targetId)
                .input(input);
    }

    private static String blockedReason(BusinessException ex) {
        Object reason = ex.getDetails().get("reason");
        return reason == null ? ex.getMessage() : ex.getMessage() + " " + reason;
    }

    private static void requireKind(DefinitionEditAction action, ChangeKind kind) {
        if (action.kind() != kind) {
            throw new IllegalArgumentException(action + "는 " + kind + " 동작이 아닙니다.");
        }
    }
}

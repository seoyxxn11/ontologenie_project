package com.axcore.platform.audit.service;

import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntityChange;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.repository.EntityChangeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 도메인이 쓰는 감사 기록 저장 (FN-LOG-01~03, Jira 212). api와 batch 프로필이 같은 코드로 DB에 직접 쓴다. 입력·결과·전후 내용에서
 * 비밀값을 빼고 저장한다.
 *
 * <ul>
 *   <li>{@link #record}: 업무 변경과 같은 트랜잭션. 업무가 롤백되면 기록도 없어진다. 성공 기록에 쓴다.
 *   <li>{@link #recordSeparately}: 새 트랜잭션에 바로 커밋. 업무가 롤백돼도 남는다. 차단·실패 기록에 쓴다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuditRecorder {

    private final ActionExecutionRepository actionExecutionRepository;
    private final EntityChangeRepository entityChangeRepository;
    private final Clock clock;

    /** 진행 중인 트랜잭션에 참여한다(없으면 새로 연다). 저장한 action_execution ID를 돌려준다. */
    @Transactional
    public Long record(AuditEntry entry) {
        return save(entry);
    }

    /** 진행 중인 트랜잭션과 따로 저장하고 바로 커밋한다. 저장한 action_execution ID를 돌려준다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSeparately(AuditEntry entry) {
        return save(entry);
    }

    private Long save(AuditEntry entry) {
        ActionExecution execution =
                actionExecutionRepository.save(
                        ActionExecution.of(
                                entry,
                                AuditSanitizer.clean(entry.input()),
                                AuditSanitizer.clean(entry.result()),
                                Instant.now(clock)));
        List<EntityChange> changes =
                entry.changes().stream()
                        .map(change -> toEntity(execution.getId(), change))
                        .toList();
        entityChangeRepository.saveAll(changes);
        return execution.getId();
    }

    private static EntityChange toEntity(Long executionId, ChangeEntry change) {
        return EntityChange.of(
                executionId,
                change,
                AuditSanitizer.clean(change.before()),
                AuditSanitizer.clean(change.after()));
    }
}

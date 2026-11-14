package com.axcore.platform.source.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.CdcConsumerRepository;
import com.axcore.platform.source.repository.CdcReloadRepository;
import com.axcore.platform.source.repository.CdcReloadRepository.DueResource;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다시 읽기 표시(loaded_lsn = NULL)가 붙은 CDC 테이블에 전체 읽기 실행을 넣는다(S15P31C106-330). 표시는 매핑 서비스(매핑 변경)와 CDC
 * 소비자(복구)가 붙이고, 전체 읽기가 성공해야 풀린다. 첫 적재·매핑 변경·복구를 모두 trigger_kind RECOVERY로 넣고, 넣은 이유는 감사 기록 접수
 * 줄(SYSTEM + BATCH)에 남긴다. 실제 읽기는 {@link SyncRunJob}이 한다(결정 문서 11.1).
 *
 * <p>실행 하나에 테이블 하나를 넣는다. 한 테이블의 실패가 다른 테이블의 실패 횟수를 올리지 않게 하려는 것이다. 테이블마다 트랜잭션을 나눠, 한 테이블을 넣지 못해도 다른
 * 테이블은 넣는다.
 *
 * <p>적재 계획을 만들 수 없는 테이블(원천 데이터 정책 위반, 없어진 컬럼 등, 402)은 한 번 넣어 그 사유를 수집 실패로 남긴 뒤, 같은 사유로 막혀 있는 동안 다시
 * 넣지 않는다. 매핑을 고치면 계획이 만들어지므로 재시도 대기가 지나면 넣는다. 정책 때문에 생긴 실패는 멈춤 횟수에 세지 않는다. 복구 중인 원천은 이런 테이블을 기다리지
 * 않고 푼다. 기다리면 그 원천 전체의 CDC가 멈춘다.
 */
@Slf4j
@Service
@Profile("batch")
@EnableConfigurationProperties(CdcReloadProperties.class)
public class CdcReloadService {

    /** 한 번에 넣는 테이블 수 상한. 남은 테이블은 다음 주기에 넣는다. */
    static final int MAX_PER_RUN = 20;

    /** 한 번에 살펴보는 후보 수. 막혀서 넣지 않는 테이블이 앞 번호를 차지해도 다른 테이블을 넣을 수 있게 넉넉히 본다. */
    static final int MAX_CANDIDATES = 200;

    static final String MAPPING_CHANGED_PATTERN = likePattern(SyncRunJob.MAPPING_CHANGED);

    // likePattern은 끝의 %s를 버리므로(split) 머리 뒤의 %는 직접 붙인다
    static final String POLICY_BLOCKED_PATTERN =
            likePattern(FullLoadPlanner.POLICY_BLOCKED_PREFIX) + "%";

    private final CdcReloadRepository repository;
    private final CdcConsumerRepository consumerRepository;
    private final SourceResourceRepository resourceRepository;
    private final FullLoadPlanner planner;
    private final AuditRecorder audits;
    private final CdcReloadProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CdcReloadService(
            CdcReloadRepository repository,
            CdcConsumerRepository consumerRepository,
            SourceResourceRepository resourceRepository,
            FullLoadPlanner planner,
            AuditRecorder audits,
            CdcReloadProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.consumerRepository = consumerRepository;
        this.resourceRepository = resourceRepository;
        this.planner = planner;
        this.audits = audits;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 넣은 실행 ID. */
    public List<Long> queueDueReloads() {
        Instant now = Instant.now(clock);
        List<DueResource> due =
                repository.findDue(
                        now.minus(properties.retryDelay()),
                        properties.maxFailures(),
                        MAPPING_CHANGED_PATTERN,
                        POLICY_BLOCKED_PATTERN,
                        MAX_CANDIDATES);
        List<Long> queued = new ArrayList<>();
        for (DueResource resource : due) {
            if (queued.size() >= MAX_PER_RUN) {
                break;
            }
            if (stillBlockedForSameReason(resource.resourceId())) {
                continue;
            }
            Long runId = transactionTemplate.execute(status -> queue(resource, status));
            if (runId != null) {
                queued.add(runId);
            }
        }
        releaseRecoveriesWaitingOnBlockedTables();
        return queued;
    }

    /**
     * 적재 계획을 만들 수 없고, 마지막 시도가 같은 사유로 실패했는가. 처음 막혔을 때는 넣어서 실행이 그 사유를 수집 실패로 남기게 한다(수집 상태 화면에 보인다).
     * 그다음부터는 사유가 바뀌거나 계획이 만들어질 때까지 넣지 않아, 고칠 때까지 실패 기록이 쌓이지 않고 멈춤 횟수도 오르지 않는다.
     */
    private boolean stillBlockedForSameReason(long resourceId) {
        // 마지막 시도가 실패가 아니면 계획을 만들어 볼 것 없이 넣는다(막혔으면 실행이 사유를 남긴다). 주기마다 하는 일을 줄인다
        Optional<String> lastFailure = repository.lastFullFailure(resourceId);
        if (lastFailure.isEmpty()) {
            return false;
        }
        Optional<String> blocked = planFailure(resourceId);
        return blocked.isPresent() && blocked.equals(lastFailure);
    }

    /**
     * 복구 중인 원천에 적재 계획을 만들 수 없는 CDC 테이블이 있으면, 그 테이블을 빼고 다시 읽기가 끝났는지 보고 복구를 푼다. 보통은 마지막 전체 읽기가 끝날 때
     * 수집 작업이 풀지만, 막힌 테이블은 끝나지 않으므로 여기서 푼다.
     */
    private void releaseRecoveriesWaitingOnBlockedTables() {
        for (long dataSourceId : consumerRepository.findRecoveringSourceIds()) {
            List<Long> blocked =
                    resourceRepository
                            .findByDataSourceIdAndSyncStrategy(dataSourceId, SyncStrategy.CDC)
                            .stream()
                            // 해제 조건은 다시 읽기를 기다리는 테이블만 본다
                            .filter(resource -> resource.getLoadedLsn() == null)
                            .map(SourceResource::getId)
                            .filter(id -> planFailure(id).isPresent())
                            .toList();
            if (blocked.isEmpty()) {
                continue;
            }
            // 수집 저장·복구 표시와 같은 잠금 순서(cdc_offset → source_resource)로 푼다. 그 사이 새 복구 표시가 커밋되면 그 시각을 보고
            // 판단한다.
            transactionTemplate
                    .execute(
                            status -> {
                                consumerRepository.lockSourceOffsets(dataSourceId);
                                return consumerRepository.releaseRecoveryIfReloaded(
                                        dataSourceId, blocked);
                            })
                    .ifPresent(
                            resumeAfter ->
                                    log.warn(
                                            "적재 계획을 만들 수 없는 테이블을 두고 CDC 복구를 풀었습니다"
                                                    + " dataSourceId={} blockedResourceIds={} resumeAfter={}",
                                            dataSourceId,
                                            blocked,
                                            resumeAfter));
        }
    }

    /** 적재 계획을 만들 수 없으면 그 사유. 켜진 매핑이 없는 테이블은 빈 계획이라 막힌 것이 아니다. */
    private Optional<String> planFailure(long resourceId) {
        try {
            return Optional.ofNullable(planner.tryPlan(resourceId).failure());
        } catch (RuntimeException e) {
            // 예상하지 못한 오류(깨진 변환 단계 등)는 막힌 것으로 보지 않는다. 그 테이블은 실행이 실패로 남기고, 다른 테이블의 다시 읽기·복구
            // 해제는 이어 간다
            log.warn("적재 계획을 확인하지 못했습니다 resourceId={}", resourceId, e);
            return Optional.empty();
        }
    }

    private Long queue(DueResource resource, TransactionStatus status) {
        long runId = repository.insertRecoveryRun(resource.dataSourceId());
        if (!repository.insertFullResource(runId, resource.resourceId())) {
            status.setRollbackOnly();
            return null;
        }
        audits.record(
                AuditEntry.builder()
                        .eventKind(QueuedJobType.SYNC_RUN.eventKind())
                        .actor(Actor.system())
                        .entryPoint(EntryPoint.BATCH)
                        .status(ExecutionStatus.SUCCEEDED)
                        .targetType(QueuedJobType.SYNC_RUN.table())
                        .targetId(runId)
                        .input(
                                Map.of(
                                        "dataSourceId", resource.dataSourceId(),
                                        "sourceResourceId", resource.resourceId()))
                        .result(Map.of("reason", resource.reason().name(), "status", "QUEUED"))
                        .build());
        return runId;
    }

    /** String.format 문구를 LIKE 패턴으로 바꾼다. %s 자리는 아무 문자열, 나머지는 글자 그대로다(escape 문자는 백슬래시). */
    static String likePattern(String format) {
        return Pattern.compile("%s", Pattern.LITERAL)
                .splitAsStream(format)
                .map(part -> part.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
                .collect(Collectors.joining("%"));
    }
}

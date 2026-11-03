package com.axcore.platform.batch.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.ClaimedJob;
import com.axcore.platform.batch.repository.QueuedJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 표에서 작업 하나를 가져와 끝까지 처리한다(S15P31C106-215). 트랜잭션을 세 번 나눈다.
 *
 * <ol>
 *   <li>가져오기: QUEUED → RUNNING을 바로 커밋한다. 다른 인스턴스가 같은 행을 가져가지 않는다.
 *   <li>실행: {@link QueuedJobHandler#run}을 트랜잭션 밖에서 부른다. 원천을 읽는 동안 DB 연결을 잡지 않는다.
 *   <li>마무리: 결과 저장과 감사 기록 결과 줄(SYSTEM + BATCH)을 함께 커밋한다. 실패하면 FAILED와 실패 기록을 함께 커밋한다.
 * </ol>
 *
 * <p>감사 기록의 requested_at은 배치가 작업을 시작한 시각이다. 그래서 duration_ms는 대기 시간을 뺀 실행 시간이다.
 */
@Slf4j
@Service
@Profile("batch")
public class QueuedJobRunner {

    static final String UNEXPECTED_FAILURE = "작업을 처리하지 못했습니다. 관리자에게 문의해 주세요.";

    private final QueuedJobRepository queuedJobRepository;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public QueuedJobRunner(
            QueuedJobRepository queuedJobRepository,
            AuditRecorder auditRecorder,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.queuedJobRepository = queuedJobRepository;
        this.auditRecorder = auditRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 가져올 작업이 없으면 false를 돌려준다. 작업이 실패해도 처리한 것이므로 true다. */
    public <R> boolean runNext(QueuedJobHandler<R> handler) {
        Optional<ClaimedJob> claimed =
                transactionTemplate.execute(
                        status ->
                                queuedJobRepository.claimNext(handler.type(), Instant.now(clock)));
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        ClaimedJob job = claimed.get();
        try {
            R result = handler.run(job.id());
            transactionTemplate.executeWithoutResult(status -> complete(handler, job, result));
        } catch (RuntimeException e) {
            fail(job, e);
        }
        return true;
    }

    private <R> void complete(QueuedJobHandler<R> handler, ClaimedJob job, R result) {
        handler.complete(job.id(), result);
        if (!queuedJobRepository.isSucceeded(job.type(), job.id())) {
            throw new IllegalStateException(
                    "%s가 %s %d를 SUCCEEDED로 바꾸지 않았습니다."
                            .formatted(handler.getClass().getSimpleName(), job.type(), job.id()));
        }
        auditRecorder.record(
                resultLine(job, ExecutionStatus.SUCCEEDED, handler.auditResult(result)));
    }

    private void fail(ClaimedJob job, RuntimeException cause) {
        String message;
        if (cause instanceof QueuedJobFailure) {
            message = cause.getMessage();
            log.warn("작업 실패 type={} id={}: {}", job.type(), job.id(), message);
        } else {
            message = UNEXPECTED_FAILURE;
            log.error("작업 처리 중 예상하지 못한 오류 type={} id={}", job.type(), job.id(), cause);
        }
        transactionTemplate.executeWithoutResult(
                status -> {
                    if (!queuedJobRepository.markFailed(
                            job.type(), job.id(), message, Instant.now(clock))) {
                        log.warn(
                                "RUNNING이 아니어서 FAILED로 바꾸지 않음 type={} id={}", job.type(), job.id());
                        return;
                    }
                    auditRecorder.record(
                            resultLine(
                                    job, ExecutionStatus.FAILED, Map.of("errorMessage", message)));
                });
    }

    private static AuditEntry resultLine(
            ClaimedJob job, ExecutionStatus status, Map<String, Object> result) {
        return AuditEntry.builder()
                .eventKind(job.type().eventKind())
                .actor(Actor.system())
                .entryPoint(EntryPoint.BATCH)
                .status(status)
                .targetType(job.type().table())
                .targetId(job.id())
                .result(result)
                .requestedAt(job.startedAt())
                .build();
    }
}

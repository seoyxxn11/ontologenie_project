package com.axcore.platform.source.cdc;

import com.axcore.platform.source.cdc.CdcMessage.BoundaryStatus;
import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import com.axcore.platform.source.cdc.CdcMessage.Heartbeat;
import com.axcore.platform.source.cdc.CdcMessage.TransactionBoundary;
import com.axcore.platform.source.cdc.CdcTransactionApplier.CdcSource;
import com.axcore.platform.source.cdc.CdcTransactionApplier.Result;
import com.axcore.platform.source.cdc.CdcTransactionApplier.Status;
import com.axcore.platform.source.cdc.SourceTransactionAssembler.TooLargeTransactionException;
import com.axcore.platform.source.domain.RecoveryReason;
import com.axcore.platform.source.repository.CdcConsumerRepository;
import com.axcore.platform.source.repository.CdcConsumerRepository.OffsetState;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 원천 토픽 하나({@code {data_source.code}.all}, 파티션 0)를 읽는 소비 루프(Jira 198-6, 결정 문서 10.2·11장 7). 전용 스레드
 * 하나에서 돈다({@link CdcConsumerSupervisor}). {@code KafkaConsumer}는 이 스레드만 쓴다.
 *
 * <ul>
 *   <li>시작 위치는 {@code cdc_offset.next_offset}이다(assign + seek). Kafka에 위치를 커밋하지 않는다. 처음 소비하는 원천은
 *       토픽의 첫 위치부터 읽는다. 저장된 위치가 토픽 첫 위치보다 앞이면(보관 기간 초과) 복구(OFFSET_EXPIRED)로 돌린다.
 *   <li>메시지 → 원천 트랜잭션 조립(198-2) → END가 오면 반영(198-5, 연결 재계산 198-7 포함). 조립 중인 트랜잭션은 반영하지 않는다.
 *   <li>트랜잭션 밖 heartbeat는 마지막 메시지 시각을 남기고 위치를 그 다음으로 옮긴다. 트랜잭션 중간 heartbeat는 위치를 옮기지 않는다.
 *   <li>수집 정상 여부: 메시지 시각(heartbeat 시각·변경 처리 시각)보다 {@code maxLag} 넘게 늦게 처리하거나, {@code staleAfter} 동안
 *       메시지가 없으면 비정상({@code healthy_since = NULL})이다. 확인 대기의 대기 시간은 정상 구간만 센다.
 *   <li>처리 장애: 위치를 옮기지 않고 {@code retryDelay} 뒤 저장된 위치부터 다시 읽는다(9.3). 원천 트랜잭션 반영이 같은 위치에서 {@code
 *       maxApplyFailures}번 연속 실패하면 다시 시도해도 같은 오류로 보고 복구(APPLY_FAILED)로 돌린다(364). Kafka·원천 DB·플랫폼
 *       DB의 일시 장애와 반영 중 교착 같은 일시 오류는 세지 않는다(383).
 *   <li>원천 트랜잭션이 상한을 넘거나 메시지 순서·모양이 어긋나면 복구로 돌리고 멈춘다. 복구가 필요한 원천은 반영하지 않고 {@code refreshInterval}마다
 *       표시가 풀렸는지 본다.
 *   <li>복구가 풀리면(199, 전체 읽기 저장 트랜잭션이 푼다) {@code cdc_offset.resume_after}가 있다. 저장된 위치가 아니라 그 시각 뒤에
 *       토픽에 쓰인 첫 메시지로 가서, 처음 만나는 BEGIN부터 반영하고 그 BEGIN을 다음 위치로 저장한다. 그 앞의 메시지는 버린다. 그 시각 뒤에 커밋된 원천
 *       트랜잭션은 그 뒤에 토픽에 쓰이므로 놓치지 않고, 그 앞에 쓰인 트랜잭션은 다시 읽은 스냅샷에 들어 있다. 그래서 복구를 일으킨 메시지(큰 트랜잭션, 모양이 어긋난
 *       메시지)나 원천 교체 전 옛 DB 이벤트를 다시 만나지 않는다.
 * </ul>
 */
@Slf4j
final class CdcSourceConsumer implements Runnable {

    private final CdcSource source;
    private final Consumer<String, String> consumer;
    private final TopicPartition partition;
    private final CdcMessageParser parser;
    private final CdcTransactionApplier applier;
    private final CdcConsumerRepository repository;
    private final CdcStartReference startReference;
    private final CdcConsumerProperties properties;
    private final Clock clock;

    private volatile boolean running = true;
    private boolean positioned;
    private boolean paused;

    /** 복구를 푼 뒤 이어 받을 첫 BEGIN을 찾는 중. 그 전 메시지는 반영하지 않는다. */
    private boolean seekingBegin;

    private SourceTransactionAssembler assembler;
    private Long reference;
    private Instant lastMessageAt;
    private Instant lastRecheck = Instant.EPOCH;

    /** 반영이 연속으로 실패한 위치와 횟수(364). */
    private Long failedAt;

    private int failures;

    CdcSourceConsumer(
            CdcSource source,
            Consumer<String, String> consumer,
            CdcMessageParser parser,
            CdcTransactionApplier applier,
            CdcConsumerRepository repository,
            CdcStartReference startReference,
            CdcConsumerProperties properties,
            Clock clock) {
        this.source = source;
        this.consumer = consumer;
        this.partition = new TopicPartition(source.topic(), 0);
        this.parser = parser;
        this.applier = applier;
        this.repository = repository;
        this.startReference = startReference;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run() {
        try {
            consumer.assign(List.of(partition));
            while (running) {
                try {
                    if (!positioned) {
                        position();
                    }
                    if (paused) {
                        waitForRecovery();
                    } else {
                        poll();
                    }
                } catch (WakeupException e) {
                    if (running) {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ApplyFailure e) {
                    // 원천 트랜잭션 반영이 실패했다(다시 해도 같은 오류일 수 있음). 저장된 위치부터 다시 읽고 같은 위치의 실패를 센다
                    log.warn(
                            "CDC 반영 실패, {}초 뒤 저장된 위치부터 다시 시도 topic={} error={}",
                            properties.retryDelay().toSeconds(),
                            source.topic(),
                            e.getCause().toString());
                    positioned = false;
                    countFailure(e.getCause());
                    sleep(properties.retryDelay());
                } catch (RuntimeException e) {
                    // 일시 장애(Kafka·원천 DB·플랫폼 DB 연결, 반영 중 교착 등): 세지 않고 저장된 위치부터 다시 읽는다(383)
                    log.warn(
                            "CDC 처리 장애, {}초 뒤 저장된 위치부터 다시 시도 topic={} error={}",
                            properties.retryDelay().toSeconds(),
                            source.topic(),
                            e.toString());
                    positioned = false;
                    sleep(properties.retryDelay());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            consumer.close();
        }
    }

    /**
     * 같은 위치에서 반영이 연속으로 실패한 횟수를 센다(364). 반영의 결정적 실패({@link ApplyFailure})만 들어온다. 일시 장애는 루프가 세지 않고,
     * 사이에 끼어도 횟수를 초기화하지 않는다(383). 저장된 위치를 다시 읽지 못하면(플랫폼 DB에 닿지 못함) 이번 실패도 세지 않는다. 같은 위치에서 {@code
     * maxApplyFailures}번이 되면 다시 시도해도 같은 오류로 보고 복구(APPLY_FAILED)로 돌린다. 위치를 옮기지 않으므로, 복구가 풀릴 때 다시 읽은
     * 스냅샷 뒤(resume_after)부터 이어 받아 이 트랜잭션을 건너뛴다.
     */
    private void countFailure(Throwable cause) {
        Long position;
        try {
            position =
                    repository.findState(source.topic()).map(OffsetState::nextOffset).orElse(null);
        } catch (RuntimeException e) {
            return;
        }
        if (position == null) {
            failedAt = null;
            failures = 0;
            return;
        }
        failures = position.equals(failedAt) ? failures + 1 : 1;
        failedAt = position;
        if (failures < properties.maxApplyFailures()) {
            return;
        }
        log.error(
                "CDC 반영이 같은 위치에서 {}번 연속 실패해 복구로 돌림 topic={} offset={} error={}",
                failures,
                source.topic(),
                position,
                cause.toString());
        try {
            applier.markRecovery(source, RecoveryReason.APPLY_FAILED);
        } catch (RuntimeException e) {
            // 표시하지 못했으면 다음 실패 때 다시 표시한다
            log.warn("CDC 복구 표시 실패 topic={} error={}", source.topic(), e.toString());
            return;
        }
        failedAt = null;
        failures = 0;
    }

    /** 다른 스레드에서 부른다. 진행 중인 반영은 끝까지 하고 멈춘다. */
    void stop() {
        running = false;
        consumer.wakeup();
    }

    /** 저장된 위치로 돌아가 새로 조립한다. */
    private void position() {
        long begin = consumer.beginningOffsets(List.of(partition)).get(partition);
        Optional<OffsetState> found = repository.findState(source.topic());
        if (found.isEmpty()) {
            repository.ensureOffset(source.dataSourceId(), source.topic(), begin);
            found = repository.findState(source.topic());
        }
        OffsetState state = found.orElseThrow();
        assembler = new SourceTransactionAssembler(properties.maxEvents());
        lastMessageAt = clock.instant();
        if (state.recoveryReason() == null && state.resumeAfter() != null) {
            resumeAfterRecovery(state);
            return;
        }
        seekingBegin = false;
        if (state.recoveryReason() == null && state.nextOffset() < begin) {
            log.warn(
                    "저장된 소비 위치가 토픽 보관 기간을 넘어 복구로 돌림 topic={} next={} begin={}",
                    source.topic(),
                    state.nextOffset(),
                    begin);
            applier.markRecovery(source, RecoveryReason.OFFSET_EXPIRED);
            state = repository.findState(source.topic()).orElseThrow();
        }
        paused = state.recoveryReason() != null;
        reference =
                state.lastTxid() == null && !paused
                        ? startReference.read(source.dataSourceId())
                        : null;
        consumer.seek(partition, state.nextOffset());
        positioned = true;
    }

    /** 복구가 풀린 뒤 기준 시각 뒤의 첫 메시지로 간다. 기준 시각 뒤 메시지가 없으면 토픽 끝에서 기다린다. epoch 기준값은 해제 때 비웠으므로 새로 읽는다. */
    private void resumeAfterRecovery(OffsetState state) {
        OffsetAndTimestamp found =
                consumer.offsetsForTimes(Map.of(partition, state.resumeAfter().toEpochMilli()))
                        .get(partition);
        long offset =
                found != null
                        ? found.offset()
                        : consumer.endOffsets(List.of(partition)).get(partition);
        log.info(
                "CDC 복구가 풀려 기준 시각 뒤부터 이어 받음 topic={} resumeAfter={} offset={}",
                source.topic(),
                state.resumeAfter(),
                offset);
        paused = false;
        reference = state.lastTxid() == null ? startReference.read(source.dataSourceId()) : null;
        consumer.seek(partition, offset);
        seekingBegin = true;
        positioned = true;
    }

    private void poll() {
        ConsumerRecords<String, String> records = consumer.poll(properties.pollTimeout());
        for (ConsumerRecord<String, String> record : records.records(partition)) {
            handle(record);
            if (paused || !positioned) {
                // 남은 메시지는 버린다. 다시 시작할 때 저장된 위치부터 읽는다
                return;
            }
        }
        Instant now = clock.instant();
        if (Duration.between(lastMessageAt, now).compareTo(properties.staleAfter()) > 0) {
            repository.markUnhealthy(source.topic());
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        Instant receivedAt = clock.instant();
        lastMessageAt = receivedAt;
        CdcMessage message;
        try {
            message = parser.parse(record.value());
        } catch (CdcProtocolException e) {
            recover(RecoveryReason.PROTOCOL_ERROR, record.offset(), e);
            return;
        }
        if (seekingBegin) {
            if (message instanceof Heartbeat heartbeat) {
                // 트랜잭션 중간일 수 있어 위치는 옮기지 않는다
                repository.recordMessage(
                        source.topic(), receivedAt, null, healthy(receivedAt, heartbeat.timeMs()));
                return;
            }
            if (!(message instanceof TransactionBoundary boundary)
                    || boundary.status() != BoundaryStatus.BEGIN) {
                // 기준 시각 앞에 시작한 트랜잭션의 나머지다. 다시 읽은 스냅샷에 들어 있다
                return;
            }
            if (!repository.resumeAt(source.topic(), record.offset())) {
                // 그 사이 다시 복구가 필요해졌다. 상태를 다시 읽는다
                positioned = false;
                return;
            }
            seekingBegin = false;
        }
        if (message instanceof Heartbeat heartbeat) {
            Long advance = assembler.inTransaction() ? null : record.offset() + 1;
            repository.recordMessage(
                    source.topic(), receivedAt, advance, healthy(receivedAt, heartbeat.timeMs()));
            return;
        }
        Optional<SourceTransaction> completed;
        try {
            completed = assembler.accept(message, record.offset());
        } catch (TooLargeTransactionException e) {
            recover(RecoveryReason.TRANSACTION_TOO_LARGE, record.offset(), e);
            return;
        } catch (CdcProtocolException e) {
            recover(RecoveryReason.PROTOCOL_ERROR, record.offset(), e);
            return;
        }
        if (completed.isEmpty()) {
            return;
        }
        SourceTransaction tx = completed.get();
        Result result;
        try {
            result = applier.apply(source, tx, reference, receivedAt);
        } catch (RuntimeException e) {
            // 반영 중 일시 장애는 그대로 던져 세지 않고, 그 밖의 실패만 같은 위치 실패로 센다(383)
            throw transientFailure(e) ? e : new ApplyFailure(e);
        }
        if (result.status() == Status.RECOVERY_NEEDED
                || result.status() == Status.RECOVERY_PENDING) {
            log.warn(
                    "CDC 반영을 멈춤(복구 필요) topic={} reason={}",
                    source.topic(),
                    result.recoveryReason());
            paused = true;
            return;
        }
        // 기준값은 이제 cdc_offset.last_txid에 있다
        reference = null;
        long processed =
                tx.events().stream().mapToLong(ChangeEvent::processedTimeMs).max().orElse(-1);
        repository.recordMessage(
                source.topic(), receivedAt, null, processed < 0 || healthy(receivedAt, processed));
    }

    /**
     * 다시 하면 풀릴 수 있는 장애인지(383). 플랫폼 DB에 닿지 못함, 트랜잭션을 열지 못함, 교착·잠금 대기·질의 시간 초과처럼 Spring이 일시 오류로 분류한
     * 것과 JDBC의 일시 오류다. 원인 사슬을 따라가며 본다.
     */
    static boolean transientFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof TransactionTimedOutException
                    || t instanceof SQLTransientException) {
                return true;
            }
        }
        return false;
    }

    /** 원천 트랜잭션 반영의 결정적 실패. 이것만 같은 위치 실패로 센다(383). */
    private static final class ApplyFailure extends RuntimeException {

        ApplyFailure(RuntimeException cause) {
            super(cause);
        }
    }

    private void recover(RecoveryReason reason, long offset, RuntimeException cause) {
        log.warn(
                "CDC 반영을 이어 갈 수 없어 복구로 돌림 topic={} offset={} reason={} error={}",
                source.topic(),
                offset,
                reason,
                cause.getMessage());
        applier.markRecovery(source, reason);
        paused = true;
    }

    /** 복구가 필요한 동안은 읽지 않는다. 표시가 풀리면 저장된 위치부터 다시 읽는다. */
    private void waitForRecovery() throws InterruptedException {
        Instant now = clock.instant();
        if (Duration.between(lastRecheck, now).compareTo(properties.refreshInterval()) >= 0) {
            lastRecheck = now;
            boolean cleared =
                    repository
                            .findState(source.topic())
                            .map(state -> state.recoveryReason() == null)
                            .orElse(true);
            if (cleared) {
                log.info("CDC 복구 표시가 풀려 다시 읽음 topic={}", source.topic());
                paused = false;
                positioned = false;
                return;
            }
        }
        sleep(properties.pollTimeout());
    }

    private boolean healthy(Instant receivedAt, long messageTimeMs) {
        Duration lag = Duration.between(Instant.ofEpochMilli(messageTimeMs), receivedAt);
        return lag.compareTo(properties.maxLag()) <= 0;
    }

    private void sleep(Duration duration) throws InterruptedException {
        if (running) {
            Thread.sleep(duration.toMillis());
        }
    }
}

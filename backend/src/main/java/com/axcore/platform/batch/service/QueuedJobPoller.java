package com.axcore.platform.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 작업 표를 주기적으로 확인한다. 등록된 {@link QueuedJobHandler}마다 QUEUED 작업을 최대 {@value
 * #MAX_JOBS_PER_POLL}건씩 처리한다. 한 종류가 몰려도 다른 종류가 오래 밀리지 않게 하려는 상한이다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class QueuedJobPoller {

    static final int MAX_JOBS_PER_POLL = 20;

    private final ObjectProvider<QueuedJobHandler<?>> handlers;
    private final QueuedJobRunner queuedJobRunner;

    @Scheduled(fixedDelayString = "${axcore.batch.poll-delay}")
    public void poll() {
        handlers.orderedStream().forEach(this::drain);
    }

    private void drain(QueuedJobHandler<?> handler) {
        try {
            int processed = 0;
            while (processed < MAX_JOBS_PER_POLL && queuedJobRunner.runNext(handler)) {
                processed++;
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("작업 표 확인 실패 type={}", handler.type(), e);
        }
    }
}

package com.axcore.platform.analysis.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 배치 컨테이너에서 멈춘 분석 실행을 주기적으로 정리한다. 여러 배치 인스턴스가 돌아도 한 문장 UPDATE라 같은 실행을 두 번 바꾸지 않는다. */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class AnalysisTimeoutJob {

    private final AnalysisTimeoutService analysisTimeoutService;

    @Scheduled(fixedDelayString = "${axcore.analysis.timeout.check-delay}")
    public void run() {
        try {
            List<Long> failed = analysisTimeoutService.failStaleRuns();
            if (!failed.isEmpty()) {
                log.warn("시간 초과로 분석 실행을 FAILED 처리했습니다 analysisRunIds={}", failed);
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("멈춘 분석 실행 정리 실패", e);
        }
    }
}

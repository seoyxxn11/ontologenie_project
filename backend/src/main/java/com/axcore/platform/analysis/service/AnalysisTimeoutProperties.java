package com.axcore.platform.analysis.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멈춘 분석 실행을 실패로 정리하는 기준(S15P31C106-273). 시작 알림은 요청 직후 가므로 QUEUED가 오래 남으면 멈춘 것으로 본다. RUNNING은 AI 분석
 * 최대 소요 시간보다 길게 잡는다. 기본값은 소요 시간 측정(S15P31C106-55) 전 임시값이다(2026-11-05 결정).
 */
@ConfigurationProperties("axcore.analysis.timeout")
public record AnalysisTimeoutProperties(Duration queued, Duration running) {

    public AnalysisTimeoutProperties {
        queued = queued == null ? Duration.ofMinutes(10) : queued;
        running = running == null ? Duration.ofMinutes(60) : running;
        if (queued.isNegative() || queued.isZero() || running.isNegative() || running.isZero()) {
            throw new IllegalArgumentException("분석 시간 초과 기준은 0보다 커야 합니다.");
        }
    }
}

package com.axcore.platform.analysis.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** FastAPI 분석 서비스 주소와 시간 제한(API 018 하단 시작 알림). 주소는 환경변수 AXCORE_AI_BASE_URL로 넣는다. */
@ConfigurationProperties("axcore.ai")
public record AiAnalysisProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public AiAnalysisProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}

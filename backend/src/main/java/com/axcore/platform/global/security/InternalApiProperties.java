package com.axcore.platform.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 API(/api/v1/internal/**)를 부르는 서비스와 나눠 가진 공유 비밀. 환경변수 AXCORE_INTERNAL_API_TOKEN으로 넣고 저장소에 두지
 * 않는다. 비어 있으면 내부 API를 모두 막는다.
 */
@ConfigurationProperties("axcore.internal-api")
public record InternalApiProperties(String token) {

    boolean isConfigured() {
        return token != null && !token.isBlank();
    }
}

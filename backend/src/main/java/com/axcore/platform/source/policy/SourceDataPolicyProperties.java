package com.axcore.platform.source.policy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 원천 데이터 정책 목록(application.yml {@code axcore.source-data-policy}, S15P31C106-384·391). 항목은 {@code
 * 스키마.표.컬럼} 이고, 표 전체는 {@code 스키마.표.*}로 쓴다. 원천 계정 권한 스크립트(infra/source-db)와 같은 목록을 유지한다.
 *
 * @param forbidden 들이지 않는 표·컬럼
 * @param restricted 들이지만 민감 속성으로만 담는 컬럼
 */
@ConfigurationProperties("axcore.source-data-policy")
public record SourceDataPolicyProperties(List<String> forbidden, List<String> restricted) {

    public SourceDataPolicyProperties {
        forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        restricted = restricted == null ? List.of() : List.copyOf(restricted);
    }
}

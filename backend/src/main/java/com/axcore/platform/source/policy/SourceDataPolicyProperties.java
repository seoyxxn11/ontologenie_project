package com.axcore.platform.source.policy;

import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 원천 데이터 정책 목록(application.yml {@code axcore.source-data-policy}, S15P31C106-384·391). 항목은 {@code
 * 스키마.표.컬럼} 이고, 표 전체는 {@code 스키마.표.*}로 쓴다. 원천 계정 권한 스크립트(infra/source-db)와 같은 목록을 유지한다.
 *
 * <p>금지 목록이 비면 기동하지 않는다(405). 민감 목록만 남아도 금지 데이터가 열리기 때문이다. 정책 없이 띄워야 하는 경우에만 {@code allow-empty:
 * true}로 명시한다.
 *
 * @param forbidden 들이지 않는 표·컬럼
 * @param restricted 들이지만 민감 속성으로만 담는 컬럼
 * @param allowEmpty 금지 목록이 비어도 기동한다. 기본은 false다.
 */
@ConfigurationProperties("axcore.source-data-policy")
public record SourceDataPolicyProperties(
        List<String> forbidden, List<String> restricted, boolean allowEmpty) {

    @ConstructorBinding
    public SourceDataPolicyProperties {
        // 빈 항목(null)도 그대로 둔다. 형식 검사(SourceColumnPolicy)가 잘못된 항목으로 알린다.
        forbidden = forbidden == null ? List.of() : unmodifiableList(new ArrayList<>(forbidden));
        restricted = restricted == null ? List.of() : unmodifiableList(new ArrayList<>(restricted));
    }

    /** 빈 목록을 허용하지 않는 정책. 단위 테스트가 쓴다. */
    public SourceDataPolicyProperties(List<String> forbidden, List<String> restricted) {
        this(forbidden, restricted, false);
    }
}

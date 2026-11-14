package com.axcore.platform.source.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * 단위 테스트용 원천 데이터 정책. 목록을 복사하지 않고 운영 설정(main의 application.yml)의 {@code axcore.source-data-policy}를
 * 그대로 읽는다(405). 그 목록이 결정과 같은지는 {@link SourceDataPolicyDecisionTests}가 본다.
 */
public final class TestSourcePolicy {

    public static final SourceDataPolicyProperties PROPERTIES = load();

    public static final SourceColumnPolicy DEFAULT = new SourceColumnPolicy(PROPERTIES);

    private TestSourcePolicy() {}

    private static SourceDataPolicyProperties load() {
        try {
            var sources =
                    new YamlPropertySourceLoader()
                            .load("application.yml", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("axcore.source-data-policy", SourceDataPolicyProperties.class)
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "application.yml에 axcore.source-data-policy가 없습니다."));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

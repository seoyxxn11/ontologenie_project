package com.axcore.platform.action.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriUtils;

/**
 * Handler 구현 요청을 보낼 Mattermost Incoming Webhook(Action 정의서 4-3). 주소는 비밀값이라 환경 변수
 * AXCORE_ACTION_NOTIFY_WEBHOOK_URL로만 넣고 toString·로그에 남기지 않는다.
 *
 * <p>screenBaseUrl은 알림에 넣을 Action 정의 화면의 서비스 주소(예: https://ontologenie.com)다. 비밀값이 아닌 일반 설정
 * AXCORE_ACTION_NOTIFY_SCREEN_BASE_URL로 넣는다. 비었거나 http·https 주소가 아니면 링크 없이 보낸다(Jira 317).
 */
@ConfigurationProperties("axcore.action-notify")
public record ActionNotifyProperties(
        String webhookUrl, String screenBaseUrl, Duration connectTimeout, Duration readTimeout) {

    /** Action 정의 상세 화면 경로(프론트 S15P31C106-363). 뒤에 URL 인코딩한 Action 코드를 붙인다. */
    static final String DEFINITION_PATH = "/admin/actions/";

    public ActionNotifyProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 정의 화면 링크. 기준 주소가 비었거나 쓸 수 없는 주소면 비어 있다. */
    Optional<String> definitionLink(String actionCode) {
        return screenBase()
                .map(
                        base ->
                                base
                                        + DEFINITION_PATH
                                        + UriUtils.encodePathSegment(
                                                actionCode, StandardCharsets.UTF_8));
    }

    boolean hasScreenBaseUrl() {
        return screenBase().isPresent();
    }

    private Optional<String> screenBase() {
        if (screenBaseUrl == null || screenBaseUrl.isBlank()) {
            return Optional.empty();
        }
        String base = screenBaseUrl.strip();
        try {
            URI uri = new URI(base);
            boolean web =
                    "http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme());
            if (!web
                    || uri.getHost() == null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return Optional.empty();
            }
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return Optional.of(base.substring(0, end));
    }

    @Override
    public String toString() {
        return "ActionNotifyProperties[configured=%s, screenBaseUrl=%s, connectTimeout=%s, readTimeout=%s]"
                .formatted(isConfigured(), screenBaseUrl, connectTimeout, readTimeout);
    }
}

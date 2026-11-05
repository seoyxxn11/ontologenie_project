package com.axcore.platform.audit.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 감사 기록에서 비밀값을 뺀다. 이름에 아래 단어가 들어간 키는 값과 함께 지운다. 대소문자와 {@code _}·{@code -}는 구분하지 않고, 안쪽 객체와 목록까지 본다.
 * 예: password, newPassword, credentials, apiKey, X-Internal-Token.
 */
public final class AuditSanitizer {

    private static final List<String> SECRET_WORDS =
            List.of(
                    "password",
                    "passwd",
                    "secret",
                    "token",
                    "credential",
                    "apikey",
                    "privatekey",
                    "authorization",
                    "cookie",
                    "sessionid");

    private AuditSanitizer() {}

    /** 감사 기록에 남는 모양. 같은 요청인지 기록과 비교할 때도 쓴다(API 091 requestId 재전송). */
    public static Map<String, Object> clean(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (!isSecret(key)) {
                        cleaned.put(key, cleanValue(value));
                    }
                });
        return Collections.unmodifiableMap(cleaned);
    }

    static boolean isSecret(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SECRET_WORDS.stream().anyMatch(normalized::contains);
    }

    @SuppressWarnings("unchecked")
    private static Object cleanValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return clean((Map<String, ?>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> cleaned = new ArrayList<>(list.size());
            list.forEach(item -> cleaned.add(cleanValue(item)));
            return Collections.unmodifiableList(cleaned);
        }
        return value;
    }
}

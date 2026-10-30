package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditSanitizerTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "newPassword",
                "password_hash",
                "credentials",
                "apiKey",
                "api_key",
                "X-Internal-Token",
                "clientSecret",
                "privateKey",
                "Authorization",
                "Cookie",
                "sessionId"
            })
    void treatsSecretKeys(String key) {
        assertThat(AuditSanitizer.isSecret(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"loginId", "name", "status", "host", "requestId", "accountId"})
    void keepsOrdinaryKeys(String key) {
        assertThat(AuditSanitizer.isSecret(key)).isFalse();
    }

    @Test
    void removesSecretsInNestedValues() {
        Map<String, Object> cleaned =
                AuditSanitizer.clean(
                        Map.of(
                                "name", "MES",
                                "credentials", Map.of("username", "mes_sync", "password", "p"),
                                "sources",
                                        List.of(
                                                Map.of(
                                                        "host",
                                                        "db",
                                                        "connection",
                                                        Map.of(
                                                                "user", "ai",
                                                                "password", "q")))));

        assertThat(cleaned).containsOnlyKeys("name", "sources");
        assertThat(cleaned.toString())
                .contains("MES", "db", "ai")
                .doesNotContain("password", "mes_sync");
    }

    @Test
    void keepsNullValuesAndNullMap() {
        Map<String, Object> values = new HashMap<>();
        values.put("description", null);

        assertThat(AuditSanitizer.clean(values)).containsEntry("description", null);
        assertThat(AuditSanitizer.clean(null)).isNull();
    }
}

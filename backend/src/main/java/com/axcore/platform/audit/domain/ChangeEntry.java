package com.axcore.platform.audit.domain;

import java.util.Map;
import java.util.Objects;

/**
 * 실행이 바꾼 행 하나의 전후 내용 (FN-LOG-03, entity_change). 행은 테이블 이름과 PK(예: {@code Map.of("id", 12)})로 가리킨다.
 * 비밀값은 기록할 때 빠진다.
 */
public record ChangeEntry(
        String tableName,
        Map<String, Object> rowKey,
        ChangeKind kind,
        Map<String, Object> before,
        Map<String, Object> after) {

    public ChangeEntry {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(kind, "kind");
        if (rowKey == null || rowKey.isEmpty()) {
            throw new IllegalArgumentException("rowKey가 비어 있습니다.");
        }
        if ((kind == ChangeKind.INSERT) != (before == null)) {
            throw new IllegalArgumentException("before는 INSERT일 때만 비어 있습니다.");
        }
        if ((kind == ChangeKind.DELETE) != (after == null)) {
            throw new IllegalArgumentException("after는 DELETE일 때만 비어 있습니다.");
        }
    }

    public static ChangeEntry insert(
            String tableName, Map<String, Object> rowKey, Map<String, Object> after) {
        return new ChangeEntry(tableName, rowKey, ChangeKind.INSERT, null, after);
    }

    public static ChangeEntry update(
            String tableName,
            Map<String, Object> rowKey,
            Map<String, Object> before,
            Map<String, Object> after) {
        return new ChangeEntry(tableName, rowKey, ChangeKind.UPDATE, before, after);
    }

    public static ChangeEntry delete(
            String tableName, Map<String, Object> rowKey, Map<String, Object> before) {
        return new ChangeEntry(tableName, rowKey, ChangeKind.DELETE, before, null);
    }
}

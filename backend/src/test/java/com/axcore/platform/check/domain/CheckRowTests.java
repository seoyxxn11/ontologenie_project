package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 원천 키, 결측값과 중첩된 원본 값 보존을 검증한다. */
class CheckRowTests {

    @Test
    void preservesSourceKeyAndRawValues() {
        var key = new HashMap<String, Object>(Map.of("id", 31));
        var values = new HashMap<String, Object>(Map.of("WORKER.id", 31));
        values.put("WORKER.name", null);
        var row = new CheckRow(11L, key, 101L, values);
        key.clear();
        values.clear();

        assertThat(row.sourceKey()).containsEntry("id", 31);
        assertThat(row.values()).containsEntry("WORKER.id", 31).containsEntry("WORKER.name", null);
        assertThrows(UnsupportedOperationException.class, row.sourceKey()::clear);
        assertThrows(UnsupportedOperationException.class, row.values()::clear);
    }

    @Test
    void preservesNestedJson() {
        var tags = new ArrayList<Object>(Arrays.asList("A", null));
        var details = new HashMap<String, Object>(Map.of("tags", tags));
        var row = new CheckRow(11L, Map.of("id", 31), null, Map.of("WORKER.details", details));
        tags.add("B");
        details.put("changed", true);

        var saved = (Map<?, ?>) row.values().get("WORKER.details");
        assertThat(saved).isEqualTo(Map.of("tags", Arrays.asList("A", null)));
        assertThrows(UnsupportedOperationException.class, saved::clear);
        assertThrows(
                UnsupportedOperationException.class, () -> ((List<?>) saved.get("tags")).clear());
    }

    @Test
    void rejectsInvalidRow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckRow(0L, Map.of("id", 31), null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckRow(11L, Map.of("id", 31), 0L, Map.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new CheckRow(11L, Map.of(), null, Map.of()));
        assertThrows(NullPointerException.class, () -> new CheckRow(11L, null, null, Map.of()));
        assertThrows(
                NullPointerException.class, () -> new CheckRow(11L, Map.of("id", 31), null, null));
    }
}

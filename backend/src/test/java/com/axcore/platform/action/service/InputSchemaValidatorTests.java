package com.axcore.platform.action.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** inputSchema 검사기(라이브러리 없이 지원 키워드만 구현)의 판정. 스키마는 V10의 재배정 계획 inputSchema와 같은 모양이다. */
class InputSchemaValidatorTests {

    private final InputSchemaValidator validator = new InputSchemaValidator();

    private final Map<String, Object> schema =
            Map.of(
                    "type", "object",
                    "required", List.of("fromEquipmentId", "items", "dataAsOf"),
                    "properties",
                            Map.of(
                                    "fromEquipmentId", Map.of("type", "integer", "minimum", 1),
                                    "items",
                                            Map.of(
                                                    "type",
                                                    "array",
                                                    "minItems",
                                                    1,
                                                    "items",
                                                    Map.of(
                                                            "type", "object",
                                                            "required",
                                                                    List.of(
                                                                            "workOrderId",
                                                                            "toEquipmentId"),
                                                            "properties",
                                                                    Map.of(
                                                                            "workOrderId",
                                                                            Map.of(
                                                                                    "type",
                                                                                    "integer"),
                                                                            "operationCode",
                                                                            Map.of(
                                                                                    "type",
                                                                                    List.of(
                                                                                            "string",
                                                                                            "null"),
                                                                                    "maxLength",
                                                                                    50),
                                                                            "toEquipmentId",
                                                                            Map.of(
                                                                                    "type",
                                                                                    "integer")))),
                                    "dataAsOf", Map.of("type", "string", "format", "date-time")));

    @Test
    void acceptsValidInputWithNullableOperation() {
        Map<String, Object> item = new HashMap<>();
        item.put("workOrderId", 7);
        item.put("operationCode", null);
        item.put("toEquipmentId", 9);

        assertThat(
                        validator.firstViolation(
                                schema, input(1, List.of(item), "2026-10-30T14:00:00+09:00")))
                .isEmpty();
    }

    @Test
    void reportsFirstViolationWithPath() {
        assertThat(validator.firstViolation(schema, Map.of("items", List.of())))
                .contains("input.fromEquipmentId: 필수 값이 없습니다.");
        assertThat(
                        validator.firstViolation(
                                schema, input(0, List.of(item(1, 2)), "2026-10-30T14:00:00Z")))
                .contains("input.fromEquipmentId: 1 이상이어야 합니다.");
        assertThat(validator.firstViolation(schema, input(1, List.of(), "2026-10-30T14:00:00Z")))
                .contains("input.items: 항목이 1개 이상이어야 합니다.");
        assertThat(
                        validator.firstViolation(
                                schema,
                                input(
                                        1,
                                        List.of(Map.of("workOrderId", "7", "toEquipmentId", 2)),
                                        "2026-10-30T14:00:00Z")))
                .contains("input.items[0].workOrderId: 형식이 integer이어야 합니다.");
        assertThat(
                        validator.firstViolation(
                                schema,
                                input(
                                        1,
                                        List.of(Map.of("workOrderId", 7)),
                                        "2026-10-30T14:00:00Z")))
                .contains("input.items[0].toEquipmentId: 필수 값이 없습니다.");
        assertThat(
                        validator.firstViolation(
                                schema, input(1, List.of(item(1, 2)), "2026-10-30 14:00")))
                .contains("input.dataAsOf: 시간대가 있는 ISO 8601 시각이어야 합니다.");
        assertThat(
                        validator.firstViolation(
                                schema,
                                input(
                                        1,
                                        List.of(
                                                Map.of(
                                                        "workOrderId", 1,
                                                        "operationCode", "X".repeat(51),
                                                        "toEquipmentId", 2)),
                                        "2026-10-30T14:00:00Z")))
                .contains("input.items[0].operationCode: 50자 이하여야 합니다.");
    }

    @Test
    void treatsWholeNumberDecimalAsInteger() {
        assertThat(validator.firstViolation(Map.of("type", "integer"), 3.0)).isEmpty();
        assertThat(validator.firstViolation(Map.of("type", "integer"), 3.5)).isPresent();
    }

    @Test
    void listsKeywordsItCannotCheck() {
        assertThat(validator.unsupported(schema)).isEmpty();
        assertThat(
                        validator.unsupported(
                                Map.of(
                                        "type", "object",
                                        "description", "설명은 허용",
                                        "properties",
                                                Map.of(
                                                        "code",
                                                        Map.of(
                                                                "type", "string", "pattern",
                                                                "[A-Z]+")))))
                .containsExactly("inputSchema.properties.code.pattern: 지원하지 않는 키워드입니다.");
        assertThat(validator.unsupported(Map.of("type", "text")))
                .containsExactly("inputSchema.type: 알 수 없는 type입니다.");
        assertThat(validator.unsupported(Map.of("format", "email")))
                .containsExactly("inputSchema.format: format은 date-time만 지원합니다.");
    }

    private static Map<String, Object> input(long from, List<?> items, String dataAsOf) {
        return Map.of("fromEquipmentId", from, "items", items, "dataAsOf", dataAsOf);
    }

    private static Map<String, Object> item(long workOrderId, long toEquipmentId) {
        return Map.of("workOrderId", workOrderId, "toEquipmentId", toEquipmentId);
    }
}

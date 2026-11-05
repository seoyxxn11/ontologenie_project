package com.axcore.platform.action.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Action 정의의 inputSchema(JSON Schema)로 실행 입력을 검사한다(API 091, 2026-11-04 결정: 라이브러리 없이 필요한 키워드만 직접
 * 구현).
 *
 * <p>지원 키워드: type(문자열 또는 목록), required, properties, items, minItems, maxItems, minimum, maximum,
 * minLength, maxLength, format(date-time만). 설명용 키워드(title, description, $comment, examples,
 * default)는 검사에 쓰지 않는다. properties에 없는 입력 키는 막지 않는다. 그 밖의 키워드는 정의 등록·수정(088·090)에서 거절해 검사되지 않는 규칙이
 * 생기지 않게 한다.
 */
@Component
public class InputSchemaValidator {

    private static final Set<String> CHECKED =
            Set.of(
                    "type",
                    "required",
                    "properties",
                    "items",
                    "minItems",
                    "maxItems",
                    "minimum",
                    "maximum",
                    "minLength",
                    "maxLength",
                    "format");
    private static final Set<String> ANNOTATIONS =
            Set.of("title", "description", "$comment", "examples", "default");
    private static final Set<String> TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean", "null");

    /** 입력이 스키마에 맞지 않으면 첫 위반을 "경로: 이유" 형식으로 돌려준다. 경로는 input부터 쓴다(예: input.items[0].workOrderId). */
    public Optional<String> firstViolation(Map<String, Object> schema, Object input) {
        List<String> violations = new ArrayList<>();
        check(schema, input, "input", violations);
        return violations.stream().findFirst();
    }

    /** 이 검사기가 처리하지 못하는 키워드·값. 비어 있으면 쓸 수 있는 스키마다. 경로는 inputSchema부터 쓴다. */
    public List<String> unsupported(Map<String, Object> schema) {
        List<String> problems = new ArrayList<>();
        collectUnsupported(schema, "inputSchema", problems);
        return problems;
    }

    private void collectUnsupported(Object node, String path, List<String> problems) {
        if (!(node instanceof Map<?, ?> schema)) {
            problems.add(path + ": 스키마는 JSON 객체여야 합니다.");
            return;
        }
        schema.forEach(
                (key, value) -> {
                    String keyword = String.valueOf(key);
                    String keyPath = path + "." + keyword;
                    if (ANNOTATIONS.contains(keyword)) {
                        return;
                    }
                    if (!CHECKED.contains(keyword)) {
                        problems.add(keyPath + ": 지원하지 않는 키워드입니다.");
                        return;
                    }
                    switch (keyword) {
                        case "type" -> {
                            List<?> types = value instanceof List<?> list ? list : List.of(value);
                            if (types.isEmpty() || !types.stream().allMatch(TYPES::contains)) {
                                problems.add(keyPath + ": 알 수 없는 type입니다.");
                            }
                        }
                        case "required" -> {
                            if (!(value instanceof List<?> list)
                                    || !list.stream().allMatch(String.class::isInstance)) {
                                problems.add(keyPath + ": 문자열 목록이어야 합니다.");
                            }
                        }
                        case "properties" -> {
                            if (value instanceof Map<?, ?> properties) {
                                properties.forEach(
                                        (name, child) ->
                                                collectUnsupported(
                                                        child, keyPath + "." + name, problems));
                            } else {
                                problems.add(keyPath + ": JSON 객체여야 합니다.");
                            }
                        }
                        case "items" -> collectUnsupported(value, keyPath, problems);
                        case "format" -> {
                            if (!"date-time".equals(value)) {
                                problems.add(keyPath + ": format은 date-time만 지원합니다.");
                            }
                        }
                        default -> {
                            if (!(value instanceof Number)) {
                                problems.add(keyPath + ": 숫자여야 합니다.");
                            }
                        }
                    }
                });
    }

    private void check(Object node, Object value, String path, List<String> violations) {
        if (!violations.isEmpty() || !(node instanceof Map<?, ?> schema)) {
            return;
        }
        Object type = schema.get("type");
        if (type != null && !matchesType(type, value)) {
            violations.add(path + ": 형식이 " + typeLabel(type) + "이어야 합니다.");
            return;
        }
        if (value instanceof Map<?, ?> object) {
            checkObject(schema, object, path, violations);
        } else if (value instanceof List<?> list) {
            checkArray(schema, list, path, violations);
        } else if (value instanceof String text) {
            checkString(schema, text, path, violations);
        } else if (value instanceof Number number) {
            checkNumber(schema, number, path, violations);
        }
    }

    private void checkObject(
            Map<?, ?> schema, Map<?, ?> object, String path, List<String> violations) {
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (!object.containsKey(name)) {
                    violations.add(path + "." + name + ": 필수 값이 없습니다.");
                    return;
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                if (object.containsKey(property.getKey())) {
                    check(
                            property.getValue(),
                            object.get(property.getKey()),
                            path + "." + property.getKey(),
                            violations);
                }
            }
        }
    }

    private void checkArray(Map<?, ?> schema, List<?> list, String path, List<String> violations) {
        if (schema.get("minItems") instanceof Number min && list.size() < min.longValue()) {
            violations.add(path + ": 항목이 " + min + "개 이상이어야 합니다.");
            return;
        }
        if (schema.get("maxItems") instanceof Number max && list.size() > max.longValue()) {
            violations.add(path + ": 항목이 " + max + "개 이하여야 합니다.");
            return;
        }
        if (schema.containsKey("items")) {
            for (int i = 0; i < list.size(); i++) {
                check(schema.get("items"), list.get(i), path + "[" + i + "]", violations);
            }
        }
    }

    private static void checkString(
            Map<?, ?> schema, String text, String path, List<String> violations) {
        int length = text.codePointCount(0, text.length());
        if (schema.get("minLength") instanceof Number min && length < min.longValue()) {
            violations.add(path + ": " + min + "자 이상이어야 합니다.");
        } else if (schema.get("maxLength") instanceof Number max && length > max.longValue()) {
            violations.add(path + ": " + max + "자 이하여야 합니다.");
        } else if ("date-time".equals(schema.get("format")) && !isDateTime(text)) {
            violations.add(path + ": 시간대가 있는 ISO 8601 시각이어야 합니다.");
        }
    }

    private static void checkNumber(
            Map<?, ?> schema, Number number, String path, List<String> violations) {
        BigDecimal value = decimal(number);
        if (schema.get("minimum") instanceof Number min && value.compareTo(decimal(min)) < 0) {
            violations.add(path + ": " + min + " 이상이어야 합니다.");
        } else if (schema.get("maximum") instanceof Number max
                && value.compareTo(decimal(max)) > 0) {
            violations.add(path + ": " + max + " 이하여야 합니다.");
        }
    }

    private static boolean matchesType(Object type, Object value) {
        List<?> types = type instanceof List<?> list ? list : List.of(type);
        return types.stream().anyMatch(name -> matchesOne(String.valueOf(name), value));
    }

    private static boolean matchesOne(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number number && isInteger(number);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
    }

    private static boolean isInteger(Number number) {
        if (number instanceof Integer
                || number instanceof Long
                || number instanceof Short
                || number instanceof Byte
                || number instanceof BigInteger) {
            return true;
        }
        BigDecimal value = decimal(number);
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal value ? value : new BigDecimal(number.toString());
    }

    private static boolean isDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String typeLabel(Object type) {
        return type instanceof List<?> list
                ? String.join("·", list.stream().map(String::valueOf).toList())
                : String.valueOf(type);
    }
}

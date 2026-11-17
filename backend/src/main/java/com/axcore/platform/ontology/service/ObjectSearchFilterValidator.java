package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.PeriodFilter;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.PropertyComparison;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.PropertyFilter;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.SearchFilters;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.SortOrder;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 076의 선택 조건을 유형의 실제 속성 정의와 사용자 조회 범위로 검증한다. */
@Component
@RequiredArgsConstructor
public class ObjectSearchFilterValidator {
    private static final String UNKNOWN_PROPERTY = "선택한 객체 유형에 정의된 속성 코드를 지정해 주세요.";

    private final PropertyDefinitionRepository properties;

    public SearchFilters validate(ObjectSearchRequest request, DataAccess access) {
        if (!request.hasFilters()) {
            return new SearchFilters(List.of(), null);
        }
        return validate(
                request.objectTypeId(),
                request.propertyValues(),
                request.dateProperty(),
                request.from(),
                request.to(),
                access);
    }

    /**
     * typeId 유형의 속성 조건을 076과 같은 규칙으로 검증한다. Search Around(379)는 상대 유형을 관계 유형의 끝에서 정해 이 메서드를 부른다.
     * 호출자가 typeId를 볼 수 있는지 먼저 확인해야 한다. 확인하지 않으면 숨긴 유형의 속성 구성이 오류 차이로 드러나므로, 그 확인을 하는 같은 패키지의 조회
     * 서비스만 부르게 공개 범위를 좁힌다. 조건이 없으면 빈 조건을 돌려준다.
     */
    SearchFilters validate(
            long typeId,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to,
            DataAccess access) {
        Map<String, List<String>> single = new LinkedHashMap<>();
        propertyValues.forEach((code, value) -> single.put(code, Collections.singletonList(value)));
        return validateAny(typeId, single, dateProperty, from, to, access);
    }

    /**
     * {@link #validate(long, Map, String, String, String, DataAccess)}와 같고 속성마다 값을 여럿 받는다. 값이 여럿이면
     * 그중 하나와 같으면 된다. Agent 조회 도구(객체 목록·객체 집합·목표 유형 찾기)가 값 여러 개(예: 끝나지 않은 상태 값들)를 한 번에 걸 때 쓴다.
     */
    SearchFilters validateAny(
            long typeId,
            Map<String, List<String>> propertyValues,
            String dateProperty,
            String from,
            String to,
            DataAccess access) {
        if (propertyValues.isEmpty() && dateProperty == null && from == null && to == null) {
            return new SearchFilters(List.of(), null);
        }
        List<PropertyDefinition> defined = properties.findByObjectTypeIdOrderById(typeId);
        Map<String, PropertyDefinition> definitions =
                defined.stream()
                        .collect(
                                Collectors.toMap(PropertyDefinition::getCode, Function.identity()));
        var values = new ArrayList<PropertyFilter>();
        propertyValues.forEach(
                (code, given) -> {
                    String field = "propertyValues[" + code + "]";
                    PropertyDefinition property =
                            requireProperty(
                                    definitions, code, typeId, access, field, UNKNOWN_PROPERTY);
                    if (given == null || given.isEmpty()) {
                        throw BusinessException.invalidField(field, "값을 하나 이상 넣어 주세요.");
                    }
                    List<String> normalized = new ArrayList<>();
                    for (String value : given) {
                        normalized.add(normalize(property.getDataType(), value, field));
                    }
                    values.add(new PropertyFilter(code, property.getDataType(), normalized));
                });

        PeriodFilter period = null;
        if (dateProperty != null || from != null || to != null) {
            String hint = periodPropertiesHint(defined, typeId, access);
            PropertyDefinition property =
                    requireProperty(
                            definitions,
                            dateProperty,
                            typeId,
                            access,
                            "dateProperty",
                            UNKNOWN_PROPERTY + hint);
            PropertyDataType type = property.getDataType();
            if (!isPeriodType(type)) {
                throw BusinessException.invalidField(
                        "dateProperty", "DATE 또는 DATETIME 속성만 기간으로 검색할 수 있습니다." + hint);
            }
            if (from == null && to == null) {
                throw BusinessException.invalidField("from", "기간의 시작 또는 끝을 지정해 주세요.");
            }
            String start = from == null ? null : normalize(type, from, "from");
            String end = to == null ? null : normalize(type, to, "to");
            if (start != null && end != null && after(type, start, end)) {
                throw BusinessException.invalidField("to", "기간의 끝은 시작보다 빠를 수 없습니다.");
            }
            period = new PeriodFilter(property.getCode(), type, start, end);
        }
        return new SearchFilters(List.copyOf(values), period);
    }

    /**
     * Agent 객체 목록의 속성 비교 조건(418). 두 속성 모두 그 유형에 정의돼 있고 볼 수 있어야 한다(민감 속성이면 값 조건과 같이 FORBIDDEN).
     * 숫자끼리나 날짜·시각끼리만 비교한다. 호출자가 typeId를 볼 수 있는지 먼저 확인해야 한다(validate와 같다).
     */
    /** 속성 비교 조건 수 상한(418). 값 목록 상한(20)처럼 SQL 조건이 끝없이 늘지 않게 한다. */
    static final int MAX_COMPARISONS = 5;

    List<PropertyComparison> validateComparisons(
            long typeId, List<ObjectSearchQuery.Comparison> given, DataAccess access) {
        if (given == null || given.isEmpty()) {
            return List.of();
        }
        if (given.size() > MAX_COMPARISONS) {
            throw BusinessException.invalidField(
                    "propertyComparisons", "속성 비교는 " + MAX_COMPARISONS + "개까지 쓸 수 있습니다.");
        }
        Map<String, PropertyDefinition> definitions = definitions(typeId);
        List<PropertyComparison> comparisons = new ArrayList<>();
        for (int index = 0; index < given.size(); index++) {
            ObjectSearchQuery.Comparison comparison = given.get(index);
            String field = "propertyComparisons[" + index + "]";
            if (comparison.operator() == null
                    || !PropertyComparison.OPERATORS.contains(comparison.operator())) {
                throw BusinessException.invalidField(
                        field, "비교 연산자는 " + PropertyComparison.OPERATORS + " 중 하나여야 합니다.");
            }
            PropertyDefinition left =
                    requireProperty(
                            definitions,
                            comparison.left(),
                            typeId,
                            access,
                            field,
                            UNKNOWN_PROPERTY);
            PropertyDefinition right =
                    requireProperty(
                            definitions,
                            comparison.right(),
                            typeId,
                            access,
                            field,
                            UNKNOWN_PROPERTY);
            String leftFamily = comparableFamily(left.getDataType());
            if (leftFamily == null || !leftFamily.equals(comparableFamily(right.getDataType()))) {
                throw BusinessException.invalidField(field, "숫자끼리나 날짜·시각끼리만 비교할 수 있습니다.");
            }
            comparisons.add(
                    new PropertyComparison(
                            left.getCode(),
                            left.getDataType(),
                            comparison.operator(),
                            right.getCode(),
                            right.getDataType()));
        }
        return List.copyOf(comparisons);
    }

    /** Agent 객체 목록의 정렬 속성(418). 볼 수 없는 민감 속성으로는 정렬하지 않는다(순서로 값이 드러난다). */
    SortOrder validateSort(long typeId, ObjectSearchQuery.Sort sort, DataAccess access) {
        if (sort == null) {
            return null;
        }
        PropertyDefinition property =
                requireProperty(
                        definitions(typeId),
                        sort.property(),
                        typeId,
                        access,
                        "sortBy",
                        UNKNOWN_PROPERTY);
        if (property.getDataType() == PropertyDataType.JSON) {
            throw BusinessException.invalidField("sortBy", "JSON 속성으로는 정렬할 수 없습니다.");
        }
        return new SortOrder(property.getCode(), property.getDataType(), sort.descending());
    }

    private Map<String, PropertyDefinition> definitions(long typeId) {
        return properties.findByObjectTypeIdOrderById(typeId).stream()
                .collect(Collectors.toMap(PropertyDefinition::getCode, Function.identity()));
    }

    private static String comparableFamily(PropertyDataType type) {
        return switch (type) {
            case INTEGER, DECIMAL -> "number";
            case DATE, DATETIME -> "date";
            default -> null;
        };
    }

    /**
     * 기간 속성을 잘못 고른 오류에 그 유형에서 고를 수 있는 날짜·시각 속성을 붙인다(378). Agent가 오류만 보고 다시 고를 수 있게 하기 위해서다. 볼 수 없는
     * 민감 속성은 넣지 않는다.
     */
    private static String periodPropertiesHint(
            List<PropertyDefinition> defined, long typeId, DataAccess access) {
        List<String> candidates =
                defined.stream()
                        .filter(property -> isPeriodType(property.getDataType()))
                        .filter(
                                property ->
                                        property.getSensitivity() != PropertySensitivity.RESTRICTED
                                                || access.canViewRestricted(typeId))
                        .map(property -> property.getCode() + "(" + property.getName() + ")")
                        .toList();
        return candidates.isEmpty()
                ? " 이 유형에는 기간으로 검색할 수 있는 속성이 없습니다."
                : " 기간으로 검색할 수 있는 속성: " + String.join(", ", candidates);
    }

    private static boolean isPeriodType(PropertyDataType type) {
        return type == PropertyDataType.DATE || type == PropertyDataType.DATETIME;
    }

    private static PropertyDefinition requireProperty(
            Map<String, PropertyDefinition> definitions,
            String code,
            long typeId,
            DataAccess access,
            String field,
            String unknownReason) {
        PropertyDefinition property = definitions.get(code);
        if (code == null || code.isBlank() || property == null) {
            throw BusinessException.invalidField(field, unknownReason);
        }
        if (property.getSensitivity() == PropertySensitivity.RESTRICTED
                && !access.canViewRestricted(typeId)) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, "이 속성으로 검색할 권한이 없습니다.", Map.of("field", field));
        }
        return property;
    }

    private static String normalize(PropertyDataType type, String value, String field) {
        if (value == null) {
            throw BusinessException.invalidField(field, "검색할 값을 지정해 주세요.");
        }
        if (type == PropertyDataType.JSON) {
            throw BusinessException.invalidField(field, "JSON 속성의 같은 값 검색은 지원하지 않습니다.");
        }
        try {
            return switch (type) {
                case STRING -> value;
                case INTEGER -> new BigInteger(value).toString();
                case DECIMAL -> decimal(value);
                case DATE -> date(LocalDate.parse(value));
                case TIME -> LocalTime.parse(value).toString();
                case DATETIME -> {
                    Instant instant = OffsetDateTime.parse(value).toInstant();
                    date(instant.atOffset(ZoneOffset.UTC).toLocalDate());
                    yield instant.toString();
                }
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false"))
                        throw new IllegalArgumentException();
                    yield value;
                }
                case JSON -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw BusinessException.invalidField(field, type + " 자료형에 맞는 값을 지정해 주세요.");
        }
    }

    private static String date(LocalDate value) {
        if (value.getYear() < 1 || value.getYear() > 9999) {
            throw new IllegalArgumentException("검색 날짜는 0001~9999년 범위여야 합니다.");
        }
        return value.toString();
    }

    private static String decimal(String value) {
        BigDecimal number = new BigDecimal(value);
        if (number.precision() - (long) number.scale() > 131072 || number.scale() > 16383) {
            throw new IllegalArgumentException("PostgreSQL numeric 범위를 벗어났습니다.");
        }
        return number.toString();
    }

    private static boolean after(PropertyDataType type, String from, String to) {
        return type == PropertyDataType.DATE
                ? LocalDate.parse(from).isAfter(LocalDate.parse(to))
                : Instant.parse(from).isAfter(Instant.parse(to));
    }
}

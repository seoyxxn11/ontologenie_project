package com.axcore.platform.ontology.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 속성 값의 자료형. 객체 값(object_instance.properties)에는 JSON으로 이렇게 들어간다: STRING 문자열, INTEGER 정수, DECIMAL 숫자
 * 또는 숫자 문자열(정밀도를 지키려고 문자열로 넣는다, ERD 3장), DATE·TIME·DATETIME ISO 8601 문자열(DATETIME은 시간대 포함), BOOLEAN
 * 참거짓, JSON 아무 값.
 */
public enum PropertyDataType {
    STRING,
    INTEGER,
    DECIMAL,
    DATE,
    TIME,
    DATETIME,
    BOOLEAN,
    JSON;

    /** 기준 시점 역할(AS_OF·EVENT_TIME)은 날짜·시각 속성에만 줄 수 있다. */
    public boolean isTemporal() {
        return this == DATE || this == TIME || this == DATETIME;
    }

    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL;
    }

    /** JSON에서 읽은 값(String, Number, Boolean, List, Map)이 이 자료형의 값인지. null은 값이 없는 것이라 따로 다룬다. */
    public boolean accepts(Object value) {
        return switch (this) {
            case STRING -> value instanceof String;
            case INTEGER -> isInteger(value);
            case DECIMAL ->
                    (value instanceof Number number && isFinite(number))
                            || (value instanceof String text && isDecimal(text));
            case DATE -> value instanceof String text && parses(() -> LocalDate.parse(text));
            case TIME -> value instanceof String text && parses(() -> LocalTime.parse(text));
            case DATETIME ->
                    value instanceof String text && parses(() -> OffsetDateTime.parse(text));
            case BOOLEAN -> value instanceof Boolean;
            case JSON -> true;
        };
    }

    private static boolean isInteger(Object value) {
        return switch (value) {
            case Integer ignored -> true;
            case Long ignored -> true;
            case Short ignored -> true;
            case BigInteger ignored -> true;
            case BigDecimal decimal -> decimal.stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }

    private static boolean isFinite(Number number) {
        return !(number instanceof Double d && (d.isNaN() || d.isInfinite()))
                && !(number instanceof Float f && (f.isNaN() || f.isInfinite()));
    }

    private static boolean isDecimal(String text) {
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

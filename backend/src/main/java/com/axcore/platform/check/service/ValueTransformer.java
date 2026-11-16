package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 규칙에 지정된 변환을 순서대로 적용해 비교용 문자열을 만든다. */
@Component
public class ValueTransformer {

    private static final Pattern NON_NUMBER = Pattern.compile("[^\\p{Nd}.]");

    private static final List<DateTimeFormatter> DATE_FORMATTERS =
            List.of("uuuu-MM-dd", "uuuu.MM.dd", "uuuu/MM/dd", "uuuuMMdd").stream()
                    .map(
                            pattern ->
                                    DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                                            .withResolverStyle(ResolverStyle.STRICT))
                    .toList();

    public String transform(String value, List<TransformStep> steps) {
        Objects.requireNonNull(steps, "steps");

        // 결측값에는 접두어 추가 등의 변환을 적용하지 않는다.
        if (value == null) {
            return null;
        }

        String result = value;
        for (TransformStep step : steps) {
            Objects.requireNonNull(step, "step");
            result = apply(result, step);
            // 대응표에 없는 값을 비운 뒤(MAP_KNOWN)에는 뒤 단계를 적용하지 않는다.
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private String apply(String value, TransformStep step) {
        try {
            return switch (step.op()) {
                case STRIP_PREFIX -> {
                    String prefix = (String) step.arg();
                    yield value.startsWith(prefix) ? value.substring(prefix.length()) : value;
                }
                case ADD_PREFIX -> (String) step.arg() + value;
                case PAD_ZEROS -> padZeros(value, (Integer) step.arg());
                case UPPER -> value.toUpperCase(Locale.ROOT);
                case TRIM -> value.strip();
                case EXTRACT_NUMBER -> extractNumber(value);
                case TO_INTEGER -> new BigInteger(value.strip()).toString();
                case TO_DECIMAL -> new BigDecimal(value.strip()).toString();
                case TO_DATE -> toDate(value);
                case MAP_VALUE -> mapValue(value, (Map<?, ?>) step.arg());
                case MAP_KNOWN -> (String) ((Map<?, ?>) step.arg()).get(value);
            };
        } catch (NumberFormatException e) {
            // 원본 값이 예외 메시지나 로그에 노출되지 않도록 사유만 전달한다.
            throw new TransformException(step.op(), step.op() + " 변환에 실패했습니다.");
        }
    }

    private String padZeros(String value, int width) {
        // 기존 AI 코드처럼 숫자로만 구성된 값에 0 채우기를 적용한다.
        if (value.isEmpty() || !value.codePoints().allMatch(Character::isDigit)) {
            return value;
        }

        int length = value.codePointCount(0, value.length());
        return "0".repeat(Math.max(0, width - length)) + value;
    }

    private String extractNumber(String value) {
        String number = NON_NUMBER.matcher(value).replaceAll("");

        if (number.codePoints().noneMatch(Character::isDigit)) {
            throw new TransformException(TransformOperation.EXTRACT_NUMBER, "추출할 숫자가 없습니다.");
        }

        return number;
    }

    private String toDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value.strip(), formatter).toString();
            } catch (DateTimeParseException e) {
                // 다음 허용 형식으로 확인한다.
            }
        }

        throw new TransformException(TransformOperation.TO_DATE, "지원하는 날짜 형식이 아니거나 유효하지 않은 날짜입니다.");
    }

    private String mapValue(String value, Map<?, ?> mapping) {
        if (!mapping.containsKey(value)) {
            throw new TransformException(TransformOperation.MAP_VALUE, "대응표에 없는 값입니다.");
        }

        return (String) mapping.get(value);
    }

    /** 행별 판정에서 변환 실패를 구분하기 위한 예외. */
    public static final class TransformException extends RuntimeException {

        private final TransformOperation operation;

        public TransformException(TransformOperation operation, String message) {
            super(message);
            this.operation = operation;
        }

        public TransformOperation operation() {
            return operation;
        }
    }
}

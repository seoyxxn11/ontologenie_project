package com.axcore.platform.mapping.domain;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 매핑과 검사에서 쓸 수 있는 값 변환 함수 하나의 설명(API 041, FN-MAP-05). 실행은 {@code check.service.ValueTransformer}가
 * 하고, 여기는 화면에 보여 줄 이름·인자·자료형·예시만 둔다.
 *
 * @param argument 인자가 없으면 null
 * @param exampleArg 예시 변환 단계의 인자. 인자가 없으면 null
 */
public record TransformFunction(
        TransformOperation op,
        String label,
        String description,
        TransformArgument argument,
        List<PropertyDataType> inputTypes,
        PropertyDataType outputType,
        Object exampleArg,
        String exampleInput,
        String exampleOutput) {

    private static final List<PropertyDataType> TEXT = List.of(PropertyDataType.STRING);
    private static final List<PropertyDataType> TEXT_OR_INTEGER =
            List.of(PropertyDataType.STRING, PropertyDataType.INTEGER);

    /** 실행 엔진이 지원하는 모든 변환. 변환 종류를 추가하면 아래 switch가 컴파일되지 않아 설명을 빠뜨릴 수 없다. */
    public static final List<TransformFunction> ALL =
            Arrays.stream(TransformOperation.values()).map(TransformFunction::describe).toList();

    private static TransformFunction describe(TransformOperation op) {
        return switch (op) {
            case STRIP_PREFIX ->
                    new TransformFunction(
                            op,
                            "접두어 빼기",
                            "값 앞의 지정한 문자열을 뺍니다. 그 문자열로 시작하지 않으면 그대로 둡니다.",
                            new TransformArgument("prefix", "string", "뺄 접두어"),
                            TEXT,
                            PropertyDataType.STRING,
                            "W",
                            "W23",
                            "23");
            case ADD_PREFIX ->
                    new TransformFunction(
                            op,
                            "접두어 붙이기",
                            "값 앞에 지정한 문자열을 붙입니다.",
                            new TransformArgument("prefix", "string", "붙일 접두어"),
                            TEXT,
                            PropertyDataType.STRING,
                            "GP",
                            "0031",
                            "GP0031");
            case PAD_ZEROS ->
                    new TransformFunction(
                            op,
                            "앞에 0 채우기",
                            "숫자로만 된 값의 앞을 0으로 채워 지정한 자릿수로 맞춥니다. 숫자가 아닌 글자가 있거나 이미 그 자릿수 이상이면 그대로 둡니다.",
                            new TransformArgument("width", "integer", "자릿수"),
                            TEXT_OR_INTEGER,
                            PropertyDataType.STRING,
                            4,
                            "31",
                            "0031");
            case UPPER ->
                    new TransformFunction(
                            op,
                            "대문자로",
                            "영문자를 대문자로 바꿉니다.",
                            null,
                            TEXT,
                            PropertyDataType.STRING,
                            null,
                            "p101",
                            "P101");
            case TRIM ->
                    new TransformFunction(
                            op,
                            "앞뒤 공백 제거",
                            "값 앞뒤의 공백을 뺍니다.",
                            null,
                            TEXT,
                            PropertyDataType.STRING,
                            null,
                            " P101 ",
                            "P101");
            case EXTRACT_NUMBER ->
                    new TransformFunction(
                            op,
                            "숫자만 남기기",
                            "숫자와 소수점만 남깁니다. 숫자가 하나도 없으면 실패합니다.",
                            null,
                            TEXT,
                            PropertyDataType.STRING,
                            null,
                            "OD-2608-012",
                            "2608012");
            case TO_INTEGER ->
                    new TransformFunction(
                            op,
                            "정수로",
                            "앞뒤 공백을 빼고 정수로 바꿉니다. 정수가 아니면 실패합니다.",
                            null,
                            TEXT,
                            PropertyDataType.INTEGER,
                            null,
                            " 0031 ",
                            "31");
            case TO_DECIMAL ->
                    new TransformFunction(
                            op,
                            "소수로",
                            "앞뒤 공백을 빼고 소수를 포함한 숫자로 바꿉니다. 숫자가 아니면 실패합니다.",
                            null,
                            TEXT_OR_INTEGER,
                            PropertyDataType.DECIMAL,
                            null,
                            "0012.50",
                            "12.50");
            case TO_DATE ->
                    new TransformFunction(
                            op,
                            "날짜로",
                            "2026-10-31, 2026.10.31, 2026/10/31, 20261031 형식을 2026-10-31로 바꿉니다. 월·일은 두 자리여야 하고, 없는 날짜면 실패합니다.",
                            null,
                            TEXT,
                            PropertyDataType.DATE,
                            null,
                            "2026/10/31",
                            "2026-10-31");
            case MAP_VALUE ->
                    new TransformFunction(
                            op,
                            "대응표로 바꾸기",
                            "대응표에 있는 값을 짝지은 값으로 바꿉니다. 대응표에 없는 값이면 실패합니다.",
                            new TransformArgument("mapping", "object", "대응표(원래 값 → 바꿀 값)"),
                            TEXT,
                            PropertyDataType.STRING,
                            Map.of("RUN", "가동"),
                            "RUN",
                            "가동");
        };
    }
}

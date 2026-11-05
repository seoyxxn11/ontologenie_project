package com.axcore.platform.mapping.service;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.TransformFunction;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 매핑의 변환 단계를 041 목록과 맞춰 본다. 형식(함수 이름·인자)이 틀리면 400, 앞 단계 결과를 다음 단계가 받을 수 없거나 마지막 결과를 속성에 넣을 수 없으면
 * 422다.
 *
 * <p>원천 컬럼 자체의 형식(varchar 등)은 보지 않는다. 원천 값은 문자열로 읽히고, 적재가 속성 자료형으로 바꾼다.
 */
public final class TransformStepsValidator {

    private static final Map<TransformOperation, TransformFunction> FUNCTIONS =
            new EnumMap<>(TransformOperation.class);

    static {
        TransformFunction.ALL.forEach(function -> FUNCTIONS.put(function.op(), function));
    }

    private TransformStepsValidator() {}

    /** 함수 이름과 인자를 확인한다. null이면 빈 목록이다. */
    public static List<TransformStep> parse(List<TransformStepPayload> payloads) {
        if (payloads == null) {
            return List.of();
        }
        List<TransformStep> steps = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            TransformStepPayload payload = payloads.get(i);
            String field = "transformSteps[" + i + "]";
            TransformOperation op;
            try {
                op = TransformOperation.valueOf(String.valueOf(payload.op()));
            } catch (IllegalArgumentException ex) {
                throw BusinessException.invalidField(field + ".op", "허용 변환 함수 목록(041)에 없는 함수입니다.");
            }
            try {
                steps.add(new TransformStep(op, payload.arg()));
            } catch (IllegalArgumentException ex) {
                throw BusinessException.invalidField(field + ".arg", ex.getMessage());
            }
        }
        return steps;
    }

    /**
     * 앞 단계의 결과 형식을 다음 단계가 받을 수 있는지, 마지막 결과를 속성에 넣을 수 있는지 본다. 문자열 결과는 적재가 속성 자료형으로 바꾸므로 어느 속성에나 넣을 수
     * 있고, 정수는 소수 속성에도 넣을 수 있다.
     */
    public static void requireCompatible(List<TransformStep> steps, PropertyDataType target) {
        PropertyDataType previous = null;
        for (int i = 0; i < steps.size(); i++) {
            TransformFunction function = FUNCTIONS.get(steps.get(i).op());
            if (previous != null && !function.inputTypes().contains(previous)) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "앞 단계의 결과를 이 변환 함수가 받을 수 없습니다.",
                        Map.of(
                                "field", "transformSteps[" + i + "].op",
                                "inputType", previous.name(),
                                "allowedTypes",
                                        function.inputTypes().stream()
                                                .map(PropertyDataType::name)
                                                .toList()));
            }
            previous = function.outputType();
        }
        if (previous != null && !fits(previous, target)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "마지막 변환 결과를 이 속성의 자료형에 넣을 수 없습니다.",
                    Map.of(
                            "field", "transformSteps",
                            "outputType", previous.name(),
                            "propertyDataType", target.name()));
        }
    }

    private static boolean fits(PropertyDataType output, PropertyDataType target) {
        return output == target
                || output == PropertyDataType.STRING
                || target == PropertyDataType.STRING
                || (output == PropertyDataType.INTEGER && target == PropertyDataType.DECIMAL);
    }
}

package com.axcore.platform.mapping.dto;

import com.axcore.platform.mapping.domain.TransformArgument;
import com.axcore.platform.mapping.domain.TransformFunction;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 041 허용 변환 함수 한 건. 인자가 없는 함수도 argument·example.step.arg를 null로 내보내 모양을 같게 한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransformFunctionResponse(
        String op,
        String label,
        String description,
        TransformArgument argument,
        List<String> inputTypes,
        String outputType,
        Example example) {

    public static TransformFunctionResponse from(TransformFunction function) {
        String op = function.op().name();
        return new TransformFunctionResponse(
                op,
                function.label(),
                function.description(),
                function.argument(),
                function.inputTypes().stream().map(PropertyDataType::name).toList(),
                function.outputType().name(),
                new Example(
                        new Step(op, function.exampleArg()),
                        function.exampleInput(),
                        function.exampleOutput()));
    }

    /** 이 함수를 쓰는 변환 단계 하나와 그 입력·출력. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Example(Step step, String input, String output) {}

    /** 매핑·규칙의 변환 단계 형식 그대로: {@code {"op": ..., "arg": ...}}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Step(String op, Object arg) {}
}

package com.axcore.platform.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.service.ValueTransformer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 041 함수 목록이 실행 엔진과 어긋나지 않는지 확인한다. */
class TransformFunctionTests {

    private final ValueTransformer transformer = new ValueTransformer();

    @Test
    void describesEveryOperationOnce() {
        assertThat(TransformFunction.ALL)
                .extracting(TransformFunction::op)
                .containsExactly(TransformOperation.values());
    }

    @Test
    void examplesMatchTransformer() {
        for (TransformFunction function : TransformFunction.ALL) {
            // 예시 인자가 실제 변환 단계로 만들어지는지(인자 종류 검증)도 함께 확인된다.
            var step = new TransformStep(function.op(), function.exampleArg());

            assertThat(transformer.transform(function.exampleInput(), List.of(step)))
                    .as(function.op().name())
                    .isEqualTo(function.exampleOutput());
        }
    }

    @Test
    void argumentDescriptionFollowsExample() {
        for (TransformFunction function : TransformFunction.ALL) {
            assertThat(function.argument() == null)
                    .as(function.op().name())
                    .isEqualTo(function.exampleArg() == null);
            assertThat(function.label()).isNotBlank();
            assertThat(function.description()).isNotBlank();
            assertThat(function.inputTypes()).isNotEmpty();
        }
    }
}

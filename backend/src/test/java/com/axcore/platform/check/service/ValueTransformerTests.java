package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 값 변환 10종과 순서·결측·실패 처리를 검증한다. */
class ValueTransformerTests {

    private final ValueTransformer transformer = new ValueTransformer();

    @Test
    void appliesSupportedTransforms() {
        check(TransformOperation.STRIP_PREFIX, "W", "W23", "23");
        check(TransformOperation.ADD_PREFIX, "GP", "0031", "GP0031");
        check(TransformOperation.PAD_ZEROS, 4, "31", "0031");
        check(TransformOperation.UPPER, null, "p101", "P101");
        check(TransformOperation.TRIM, null, " P101 ", "P101");
        check(TransformOperation.EXTRACT_NUMBER, null, "OD-2608-012", "2608012");
        check(TransformOperation.TO_INTEGER, null, " 0031 ", "31");
        check(TransformOperation.TO_DECIMAL, null, "0012.50", "12.50");
        check(TransformOperation.TO_DATE, null, "2026/10/31", "2026-10-31");
        check(TransformOperation.MAP_VALUE, Map.of("RUN", "가동"), "RUN", "가동");

        // 기존 AI 변환 코드의 경계 동작도 유지한다.
        check(TransformOperation.STRIP_PREFIX, "W", "GP0031", "GP0031");
        check(TransformOperation.PAD_ZEROS, 4, "A31", "A31");
        check(TransformOperation.EXTRACT_NUMBER, null, "약 12.5톤", "12.5");
        check(TransformOperation.TO_DATE, null, "2026-10-31", "2026-10-31");
        check(TransformOperation.TO_DATE, null, "2026.10.31", "2026-10-31");
        check(TransformOperation.TO_DATE, null, "20261031", "2026-10-31");
    }

    @Test
    void preservesOrderAndNull() {
        var steps =
                List.of(
                        new TransformStep(TransformOperation.PAD_ZEROS, 4),
                        new TransformStep(TransformOperation.ADD_PREFIX, "GP"));

        assertThat(transformer.transform("31", steps)).isEqualTo("GP0031");
        assertThat(transformer.transform(null, steps)).isNull();
        assertThat(transformer.transform(" 31 ", List.of())).isEqualTo(" 31 ");
    }

    @Test
    void rejectsInvalidValues() {
        fails(TransformOperation.EXTRACT_NUMBER, null, "숫자 없음");
        fails(TransformOperation.TO_INTEGER, null, "31x");
        fails(TransformOperation.TO_DECIMAL, null, "abc");
        fails(TransformOperation.TO_DATE, null, "2026-02-30");
        fails(TransformOperation.MAP_VALUE, Map.of("RUN", "가동"), "STOP");

        // 중간 단계가 실패하면 이후 변환을 진행하지 않는다.
        assertThrows(
                ValueTransformer.TransformException.class,
                () ->
                        transformer.transform(
                                "abc",
                                List.of(
                                        new TransformStep(TransformOperation.TO_INTEGER, null),
                                        new TransformStep(TransformOperation.ADD_PREFIX, "GP"))));
    }

    private void check(TransformOperation op, Object arg, String input, String expected) {
        var steps = List.of(new TransformStep(op, arg));
        assertThat(transformer.transform(input, steps)).as("%s", op).isEqualTo(expected);
    }

    private void fails(TransformOperation op, Object arg, String input) {
        assertThrows(
                ValueTransformer.TransformException.class,
                () -> transformer.transform(input, List.of(new TransformStep(op, arg))));
    }
}

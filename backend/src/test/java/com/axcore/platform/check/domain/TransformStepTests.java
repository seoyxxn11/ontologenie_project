package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 변환별 인자 규칙과 대응표 보존을 검증한다. */
class TransformStepTests {

    @Test
    void validatesSupportedArguments() {
        assertThat(new TransformStep(TransformOperation.ADD_PREFIX, "GP").arg()).isEqualTo("GP");
        assertThat(new TransformStep(TransformOperation.STRIP_PREFIX, "W").arg()).isEqualTo("W");
        assertThat(new TransformStep(TransformOperation.PAD_ZEROS, 4).arg()).isEqualTo(4);
        for (var op :
                List.of(
                        TransformOperation.UPPER,
                        TransformOperation.TRIM,
                        TransformOperation.EXTRACT_NUMBER,
                        TransformOperation.TO_INTEGER,
                        TransformOperation.TO_DECIMAL,
                        TransformOperation.TO_DATE)) {
            assertThat(new TransformStep(op, null).arg()).isNull();
            reject(op, "unexpected");
        }
    }

    @Test
    void rejectsInvalidArguments() {
        for (var op : List.of(TransformOperation.ADD_PREFIX, TransformOperation.STRIP_PREFIX)) {
            for (Object arg : Arrays.asList(null, "", 10)) {
                reject(op, arg);
            }
        }
        for (Object arg : Arrays.asList(null, 0, -1, 4.5, "4")) {
            reject(TransformOperation.PAD_ZEROS, arg);
        }
        for (Object arg : Arrays.asList(null, Map.of(), Map.of("RUN", 1), Map.of(1, "가동"))) {
            reject(TransformOperation.MAP_VALUE, arg);
            reject(TransformOperation.MAP_KNOWN, arg);
        }
        assertThrows(NullPointerException.class, () -> new TransformStep(null, null));
    }

    @Test
    void preservesMapping() {
        var mapping = new HashMap<>(Map.of("RUN", "가동"));
        var step = new TransformStep(TransformOperation.MAP_VALUE, mapping);
        mapping.put("RUN", "변경된 값");

        assertThat(step.arg()).isEqualTo(Map.of("RUN", "가동"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) step.arg()).clear());
    }

    private void reject(TransformOperation op, Object arg) {
        assertThrows(IllegalArgumentException.class, () -> new TransformStep(op, arg));
    }
}

package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 체크포인트의 정렬, 결측 구분과 검사 기준 보존을 검증한다. */
class CheckDataBasisTests {

    @Test
    void sortsAndPreservesCheckpoints() {
        var first = new ResourceCheckpoint(11L, 120L);
        var second = new ResourceCheckpoint(12L, null);
        var third = new ResourceCheckpoint(13L, 1L);
        var resources = new ArrayList<>(List.of(third, second, first));
        var basis = new CheckDataBasis(resources);
        resources.clear();

        assertThat(basis.resources()).containsExactly(first, second, third);
        assertThat(basis.resources().get(1).lastSuccessfulSyncRunId()).isNull();
        assertThat(basis.resources().get(2).lastSuccessfulSyncRunId()).isEqualTo(1L);
        assertThrows(UnsupportedOperationException.class, basis.resources()::clear);
    }

    @Test
    void rejectsAmbiguousOrInvalidCheckpoints() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CheckDataBasis(
                                List.of(
                                        new ResourceCheckpoint(11L, 120L),
                                        new ResourceCheckpoint(11L, 121L))));
        assertThrows(IllegalArgumentException.class, () -> new ResourceCheckpoint(0L, 120L));
        assertThrows(IllegalArgumentException.class, () -> new ResourceCheckpoint(11L, -1L));
        assertThrows(IllegalArgumentException.class, () -> new ResourceCheckpoint(11L, 0L));
    }
}

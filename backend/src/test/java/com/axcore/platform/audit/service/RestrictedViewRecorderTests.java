package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.policy.TestSourcePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RestrictedViewRecorderTests {

    private final AuditRecorder audits = mock(AuditRecorder.class);
    private final RestrictedViewRecorder recorder =
            new RestrictedViewRecorder(audits, TestSourcePolicy.DEFAULT);
    private final LoginAccount planner = new LoginAccount(7L, "planner", "PLANNER", false);

    @Test
    void recordsNothingWhenNoRestrictedValueWasShown() {
        recorder.record(planner, "OBJECT_SEARCH", List.of(Seen.object(1L, List.of())));

        verifyNoInteractions(audits);
    }

    @Test
    void recordsShownTargetsSeparatelyFromTheResponse() {
        recorder.record(
                planner,
                "OBJECT_SEARCH",
                List.of(Seen.object(1L, List.of("technician")), Seen.object(2L, List.of())));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audits).recordSeparately(entry.capture());
        assertThat(entry.getValue().eventKind()).isEqualTo(EventKind.RESTRICTED_VIEW);
        assertThat(entry.getValue().targetId()).isEqualTo(1L);
        assertThat(entry.getValue().result())
                .containsEntry("view", "OBJECT_SEARCH")
                .containsEntry("policyVersion", TestSourcePolicy.DEFAULT.fingerprint());
    }

    /** 기록이 실패하면 응답도 실패한다. 접속 기록 없이 민감 값을 내보내지 않는다. */
    @Test
    void failsWhenTheLogCannotBeSaved() {
        when(audits.recordSeparately(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(
                        () ->
                                recorder.record(
                                        planner,
                                        "OBJECT_DETAIL",
                                        List.of(Seen.object(1L, List.of("technician")))))
                .isInstanceOf(IllegalStateException.class);
    }
}

package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 검사 대상의 구분과 후보 revision 필수 조건을 검증한다. */
class CheckTargetTests {

    @Test
    void preservesApprovedRuleAndCandidateRevision() {
        assertThat(CheckTarget.forLinkMapping(10L))
                .extracting(
                        CheckTarget::linkMappingId,
                        CheckTarget::proposalItemId,
                        CheckTarget::proposalItemRevision)
                .containsExactly(10L, null, null);
        assertThat(CheckTarget.forProposal(20L, 3))
                .extracting(
                        CheckTarget::linkMappingId,
                        CheckTarget::proposalItemId,
                        CheckTarget::proposalItemRevision)
                .containsExactly(null, 20L, 3);
    }

    @Test
    void rejectsInvalidTarget() {
        // 대상은 정확히 하나이며, 후보 ID와 revision은 함께 지정한다.
        assertThrows(IllegalArgumentException.class, () -> new CheckTarget(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CheckTarget(10L, 20L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CheckTarget(null, 20L, null));
        assertThrows(IllegalArgumentException.class, () -> new CheckTarget(10L, null, 1));
        assertThrows(IllegalArgumentException.class, () -> CheckTarget.forLinkMapping(0L));
        assertThrows(IllegalArgumentException.class, () -> CheckTarget.forProposal(-1L, 1));
        assertThrows(IllegalArgumentException.class, () -> CheckTarget.forProposal(20L, 0));
    }
}

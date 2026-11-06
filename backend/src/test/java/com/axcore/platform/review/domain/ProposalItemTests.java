package com.axcore.platform.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.global.error.BusinessException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ProposalItemTests {
    private static final Instant NOW = Instant.parse("2026-11-05T01:30:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvesPendingOrHeldCandidateAfterApplication(boolean held) {
        ProposalItem item = candidate(held);
        item.approve(NOW);
        assertThat(item.getReviewStatus()).isEqualTo("APPROVED");
        assertThat(item.getApplyStatus()).isEqualTo("APPLIED");
        assertThat(item.getAppliedAt()).isEqualTo(NOW);
        assertThat(item.getApplyError()).isNull();
        assertOriginalRevision(item);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsPendingOrHeldCandidateWithoutApplication(boolean held) {
        ProposalItem item = candidate(held);
        item.reject();
        assertThat(item.getReviewStatus()).isEqualTo("REJECTED");
        assertThat(item.getApplyStatus()).isEqualTo("NOT_APPLIED");
        assertThat(item.getAppliedAt()).isNull();
        assertThat(item.getApplyError()).isNull();
        assertOriginalRevision(item);
    }

    @Test
    void allowsRepeatedHoldAndStillAllowsRevision() {
        ProposalItem item = candidate(false);
        item.hold();
        item.hold();
        assertThat(item.getReviewStatus()).isEqualTo("HELD");
        assertThat(item.getApplyStatus()).isEqualTo("NOT_APPLIED");
        assertThat(item.getAppliedAt()).isNull();
        assertOriginalRevision(item);
        item.revise(Map.of("code", "NEW"), Map.of());
        assertThat(item.getRevision()).isEqualTo(2);
        assertThat(item.getReviewStatus()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @CsvSource({
        "APPROVE,APPROVE",
        "APPROVE,REJECT",
        "APPROVE,HOLD",
        "REJECT,APPROVE",
        "REJECT,REJECT",
        "REJECT,HOLD"
    })
    void cannotChangeAFinalDecision(String first, String second) {
        ProposalItem item = candidate(false);
        decide(item, first);
        String beforeReview = item.getReviewStatus();
        String beforeApply = item.getApplyStatus();
        Instant beforeTime = item.getAppliedAt();
        assertThatThrownBy(() -> decide(item, second))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ReviewErrorCode.PROPOSAL_ALREADY_DECIDED));
        assertThat(item.getReviewStatus()).isEqualTo(beforeReview);
        assertThat(item.getApplyStatus()).isEqualTo(beforeApply);
        assertThat(item.getAppliedAt()).isEqualTo(beforeTime);
        assertOriginalRevision(item);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "HOLD"})
    void successfulDecisionClearsEarlierApplyError(String decision) {
        ProposalItem item = candidate(true);
        ReflectionTestUtils.setField(item, "applyStatus", "FAILED");
        ReflectionTestUtils.setField(item, "applyError", "이전 요청 오류");
        ReflectionTestUtils.setField(item, "appliedAt", NOW.minusSeconds(60));
        decide(item, decision);
        assertThat(item.getApplyError()).isNull();
        assertThat(item.getAppliedAt()).isEqualTo(decision.equals("APPROVE") ? NOW : null);
        assertThat(item.getApplyStatus())
                .isEqualTo(decision.equals("APPROVE") ? "APPLIED" : "NOT_APPLIED");
    }

    @Test
    void missingApplicationTimeDoesNotPartiallyApprove() {
        ProposalItem item = candidate(true);
        assertThatThrownBy(() -> item.approve(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(item.getReviewStatus()).isEqualTo("HELD");
        assertThat(item.getApplyStatus()).isEqualTo("NOT_APPLIED");
        assertThat(item.getAppliedAt()).isNull();
    }

    private static ProposalItem candidate(boolean held) {
        ProposalItem item =
                ProposalItem.manual(Map.of("code", "ORIGINAL"), Map.of("source", "test"));
        if (held) item.hold();
        return item;
    }

    private static void decide(ProposalItem item, String decision) {
        switch (decision) {
            case "APPROVE" -> item.approve(NOW);
            case "REJECT" -> item.reject();
            case "HOLD" -> item.hold();
            default -> throw new IllegalArgumentException(decision);
        }
    }

    private static void assertOriginalRevision(ProposalItem item) {
        assertThat(item.getRevision()).isEqualTo(1);
        assertThat(item.getPayload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("code", "ORIGINAL"));
        assertThat(item.getEvidence()).containsEntry("source", "test");
    }
}

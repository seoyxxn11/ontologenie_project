package com.axcore.platform.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueStatusTests {

    @Test
    void followsResolutionFlow() {
        assertThat(IssueStatus.NEW.nextStatuses()).containsExactly(IssueStatus.CHECKING);
        assertThat(IssueStatus.CHECKING.nextStatuses())
                .containsExactly(IssueStatus.FIXED_PENDING_REVERIFY);
        assertThat(IssueStatus.FIXED_PENDING_REVERIFY.nextStatuses())
                .containsExactlyInAnyOrder(IssueStatus.CHECKING, IssueStatus.RESOLVED);
        assertThat(IssueStatus.RESOLVED.nextStatuses()).isEmpty();
    }

    @Test
    void resolvesOnlyAfterFixIsReported() {
        assertThat(IssueStatus.NEW.canMoveTo(IssueStatus.RESOLVED)).isFalse();
        assertThat(IssueStatus.CHECKING.canMoveTo(IssueStatus.RESOLVED)).isFalse();
        assertThat(IssueStatus.FIXED_PENDING_REVERIFY.canMoveTo(IssueStatus.RESOLVED)).isTrue();
    }

    @Test
    void neverStaysInPlace() {
        for (IssueStatus status : IssueStatus.values()) {
            assertThat(status.canMoveTo(status)).isFalse();
        }
    }
}

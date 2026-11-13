package com.axcore.platform.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewIssueTests {

    private static final IssueDedupeKey KEY =
            IssueDedupeKey.forRule(IssueKind.UNMATCHED, "W2E", 501);

    @Test
    void buildsReadableDedupeKeys() {
        assertThat(KEY.value()).isEqualTo("UNMATCHED:rule:W2E:object:501");
        assertThat(IssueDedupeKey.forProposal(IssueKind.DUPLICATE, 7, "W23").value())
                .isEqualTo("DUPLICATE:proposal:7:row:W23");
    }

    @Test
    void rejectsBlankOrTooLongDedupeKey() {
        assertThatThrownBy(() -> new IssueDedupeKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueDedupeKey("x".repeat(IssueDedupeKey.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IssueDedupeKey.forRule(IssueKind.UNMATCHED, "", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IssueDedupeKey.forProposal(IssueKind.UNMATCHED, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsExactlyOneSourceOfIssue() {
        assertThatThrownBy(() -> issue(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(1L, 2L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(issue(1L, null).linkMappingId()).isEqualTo(1L);
        assertThat(issue(null, 2L).proposalItemId()).isEqualTo(2L);
    }

    @Test
    void needsReasonAndDetectingRun() {
        assertThatThrownBy(
                        () ->
                                new NewIssue(
                                        IssueKind.UNMATCHED,
                                        KEY,
                                        1,
                                        " ",
                                        1L,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NewIssue(
                                        IssueKind.UNMATCHED,
                                        KEY,
                                        0,
                                        "사유",
                                        1L,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesEvidence() {
        Map<String, Object> evidence = new HashMap<>(Map.of("source", "MES"));
        NewIssue issue =
                new NewIssue(
                        IssueKind.UNMATCHED, KEY, 1, "사유", 1L, null, null, null, null, evidence);
        evidence.put("source", "ERP");

        assertThat(issue.evidence()).containsEntry("source", "MES");
        assertThat(issue(1L, null).evidence()).isEmpty();
    }

    private static NewIssue issue(Long linkMappingId, Long proposalItemId) {
        return new NewIssue(
                IssueKind.UNMATCHED,
                KEY,
                1,
                "사유",
                linkMappingId,
                proposalItemId,
                null,
                null,
                null,
                null);
    }
}

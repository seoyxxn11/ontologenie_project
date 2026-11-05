package com.axcore.platform.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.review.DecisionHistoryFixtures;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, ProposalDecisionWriteRepository.class})
class ProposalDecisionRepositoryTests {
    @Autowired ProposalDecisionWriteRepository repository;
    @Autowired ProposalItemRepository items;
    @Autowired JdbcClient jdbc;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;
    private static final Instant DECIDED_AT = Instant.parse("2026-11-04T01:30:00Z");

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        account = data.account();
        proposal = data.proposal(account);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "HOLD"})
    void storesAllDecisionMetadata(String decision) {
        long option = data.option(proposal, 1);
        ProposalItem item = items.findById(proposal).orElseThrow();
        long id =
                repository.insert(
                        item,
                        new ProposalDecisionRequest(1, decision, option, " 판단 근거 보존 "),
                        account,
                        DECIDED_AT);

        jdbc.sql("select * from review_decision where id=:id")
                .param("id", id)
                .query(
                        (rs, row) -> {
                            assertThat(rs.getLong("proposal_item_id")).isEqualTo(proposal);
                            assertThat(rs.getInt("item_revision")).isEqualTo(1);
                            assertThat(rs.getString("decision")).isEqualTo(decision);
                            assertThat(rs.getLong("selected_option_id")).isEqualTo(option);
                            assertThat(rs.getString("reason")).isEqualTo(" 판단 근거 보존 ");
                            assertThat(rs.getLong("decided_by")).isEqualTo(account);
                            assertThat(rs.getTimestamp("decided_at").toInstant())
                                    .isEqualTo(DECIDED_AT);
                            return id;
                        })
                .single();
    }

    @Test
    void storesNullOptionAndAppendsWithoutOverwritingTheEarlierDecision() {
        ProposalItem item = items.findById(proposal).orElseThrow();
        long first =
                repository.insert(
                        item,
                        new ProposalDecisionRequest(1, "HOLD", null, "첫 번째 사유"),
                        account,
                        DECIDED_AT);
        long second =
                repository.insert(
                        item,
                        new ProposalDecisionRequest(1, "REJECT", null, "두 번째 사유"),
                        account,
                        DECIDED_AT);
        assertThat(second).isGreaterThan(first);
        assertThat(data.count(proposal)).isEqualTo(2);
        assertThat(
                        jdbc.sql("select reason from review_decision where id=:id")
                                .param("id", first)
                                .query(String.class)
                                .single())
                .isEqualTo("첫 번째 사유");
        assertThat(
                        jdbc.sql("select selected_option_id from review_decision where id=:id")
                                .param("id", second)
                                .query((rs, row) -> rs.getObject(1, Long.class))
                                .list())
                .containsExactly((Long) null);
    }

    @Test
    void keepsTheDecidedRevisionAfterTheCandidateChanges() {
        ProposalItem item = items.findById(proposal).orElseThrow();
        long id =
                repository.insert(
                        item,
                        new ProposalDecisionRequest(1, "HOLD", null, "원래 개정본 판단"),
                        account,
                        DECIDED_AT);
        data.revise(proposal, 2);
        assertThat(
                        jdbc.sql("select item_revision from review_decision where id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsAnOptionFromAnotherCandidate() {
        long other = data.proposal(account);
        long option = data.option(other, 1);
        ProposalItem item = items.findById(proposal).orElseThrow();
        assertThatThrownBy(
                        () ->
                                repository.insert(
                                        item,
                                        new ProposalDecisionRequest(1, "HOLD", option, "잘못된 선택지"),
                                        account,
                                        DECIDED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsAnOptionFromAnotherRevision() {
        data.revise(proposal, 2);
        long option = data.option(proposal, 2);
        ProposalItem item = items.findById(proposal).orElseThrow();
        // The locked item and selected option must name the same immutable revision.
        long oldOption = data.option(proposal, 1);
        assertThat(option).isNotEqualTo(oldOption);
        assertThatThrownBy(
                        () ->
                                repository.insert(
                                        item,
                                        new ProposalDecisionRequest(2, "HOLD", oldOption, "과거 선택지"),
                                        account,
                                        DECIDED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

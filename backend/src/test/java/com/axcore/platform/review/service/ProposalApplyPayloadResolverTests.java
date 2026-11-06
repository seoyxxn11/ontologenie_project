package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.review.DecisionHistoryFixtures;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.repository.ProposalItemRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProposalApplyPayloadResolverTests {
    @Autowired ProposalApplyPayloadResolver resolver;
    @Autowired ProposalItemRepository items;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            account = data.account();
            proposal = data.proposal(account);
        }
    }

    @Test
    void resolvesTheImmutableRevisionInsteadOfTheMutableCandidateCopy() {
        // The revision is created with its original payload in one INSERT, never edited.
        proposal = data.proposal(account, "{\"code\":\"IMMUTABLE\"}");
        jdbc.sql("update proposal_item set payload='{\"code\":\"COPY\"}'::jsonb where id=:id")
                .param("id", proposal)
                .update();
        ProposalItem item = locked();
        assertThat(item.getPayload()).containsEntry("code", "COPY");
        assertThat(resolver.resolve(item, null))
                .containsExactlyInAnyOrderEntriesOf(Map.of("code", "IMMUTABLE"));
        assertThat(item.getPayload()).containsEntry("code", "COPY");
    }

    @Test
    void resolvesTheCurrentRevisionAndTheSelectedOption() {
        proposal = data.proposal(account, "{\"code\":\"REVISION_1\"}");
        jdbc.sql(
                        """
                insert into proposal_revision(proposal_item_id, revision, payload, created_by)
                values (:id, 2, '{"code":"REVISION_2"}'::jsonb, :actor)
                """)
                .param("id", proposal)
                .param("actor", account)
                .update();
        jdbc.sql("update proposal_item set revision=2 where id=:id").param("id", proposal).update();
        long option = data.option(proposal, 2, "{\"code\":\"OPTION\"}");
        ProposalItem item = locked();
        assertThat(resolver.resolve(item, null)).containsEntry("code", "REVISION_2");
        assertThat(resolver.resolve(item, option))
                .containsExactlyInAnyOrderEntriesOf(Map.of("code", "OPTION"));
        assertThat(item.getRevision()).isEqualTo(2);
    }

    @Test
    void rejectsAnOptionFromAnotherCandidate() {
        long other = data.proposal(account);
        long option = data.option(other, 1, "{\"code\":\"OTHER\"}");
        assertInvalidOption(locked(), option);
    }

    @Test
    void rejectsAnOptionFromAnOlderRevision() {
        long option = data.option(proposal, 1, "{\"code\":\"OLD_OPTION\"}");
        data.revise(proposal, 2);
        assertInvalidOption(locked(), option);
    }

    @Test
    void rejectsANonexistentOptionWithoutFallingBackToTheBasePayload() {
        assertInvalidOption(locked(), Long.MAX_VALUE);
    }

    @Test
    void preservesSourceIdentifiersAndNestedNullsWithoutRewritingThePayload() {
        proposal =
                data.proposal(
                        account,
                        """
                {"sourceColumn":{"table":"attendance","column":"memo"},
                 "resourceKey":"erp.attendance","optional":null,"values":[1,"원문"]}
                """);
        Map<String, Object> payload = resolver.resolve(locked(), null);
        assertThat(payload)
                .containsEntry("resourceKey", "erp.attendance")
                .containsEntry("optional", null);
        assertThat(payload.get("sourceColumn"))
                .isEqualTo(Map.of("table", "attendance", "column", "memo"));
        assertThat(payload.get("values")).isEqualTo(List.of(1, "원문"));
    }

    @Test
    void resolvingAnOptionDoesNotCreateARevisionOrChangeTheCandidateOrHistory() {
        long option = data.option(proposal, 1);
        Map<String, Object> before = candidateRow();
        resolver.resolve(locked(), option);
        items.flush();
        assertThat(candidateRow()).isEqualTo(before);
        assertThat(revisionCount()).isEqualTo(1);
        assertThat(data.count(proposal)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "HOLD"})
    void stateTransitionPersistsWithVersionIncrementWithoutChangingRevision(String decision) {
        ProposalItem item = locked();
        int originalVersion = item.getRowVersion();
        Instant at = Instant.parse("2026-11-05T01:30:00Z");
        switch (decision) {
            case "APPROVE" -> item.approve(at);
            case "REJECT" -> item.reject();
            case "HOLD" -> item.hold();
            default -> throw new IllegalArgumentException(decision);
        }
        items.flush();
        entities.clear();
        ProposalItem saved = items.findById(proposal).orElseThrow();
        assertThat(saved.getReviewStatus())
                .isEqualTo(
                        switch (decision) {
                            case "APPROVE" -> "APPROVED";
                            case "REJECT" -> "REJECTED";
                            default -> "HELD";
                        });
        assertThat(saved.getApplyStatus())
                .isEqualTo(decision.equals("APPROVE") ? "APPLIED" : "NOT_APPLIED");
        assertThat(saved.getAppliedAt()).isEqualTo(decision.equals("APPROVE") ? at : null);
        assertThat(saved.getRowVersion()).isEqualTo(originalVersion + 1);
        assertThat(saved.getRevision()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresTheCallingDecisionTransaction() {
        assertThatThrownBy(() -> resolver.resolve(ProposalItem.manual(Map.of(), Map.of()), null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void callerFailureRollsBackAFlushedStateTransition() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        long id = tx.execute(status -> data.proposal(data.account()));
        Map<String, Object> before =
                jdbc.sql("select * from proposal_item where id=:id")
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            ProposalItem item =
                                                    items.findForUpdate(id).orElseThrow();
                                            item.hold();
                                            item.approve(Instant.parse("2026-11-05T01:30:00Z"));
                                            items.flush();
                                            throw new IllegalStateException(
                                                    "후속 작업 실패를 대신하는 테스트 예외");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.sql("select * from proposal_item where id=:id")
                                .param("id", id)
                                .query()
                                .singleRow())
                .isEqualTo(before);
    }

    private ProposalItem locked() {
        return items.findForUpdate(proposal).orElseThrow();
    }

    private void assertInvalidOption(ProposalItem item, long option) {
        assertThatThrownBy(() -> resolver.resolve(item, option))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode().code()).isEqualTo("VALIDATION_FAILED");
                            assertThat(ex.getDetails()).containsEntry("field", "selectedOptionId");
                        });
    }

    private Map<String, Object> candidateRow() {
        return jdbc.sql("select * from proposal_item where id=:id")
                .param("id", proposal)
                .query()
                .singleRow();
    }

    private long revisionCount() {
        return jdbc.sql("select count(*) from proposal_revision where proposal_item_id=:id")
                .param("id", proposal)
                .query(Long.class)
                .single();
    }
}

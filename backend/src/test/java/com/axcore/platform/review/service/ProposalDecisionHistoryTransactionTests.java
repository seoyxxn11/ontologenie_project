package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.DecisionHistoryFixtures;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("api")
@Import({
    TestcontainersConfiguration.class,
    ProposalDecisionHistoryTransactionTests.FixedTime.class
})
@Transactional
class ProposalDecisionHistoryTransactionTests {
    @Autowired ProposalDecisionHistoryService history;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;
    private static final Instant NOW = Instant.parse("2026-11-04T01:30:00Z");

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock decisionClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        // Non-transactional tests create their own fixtures inside an explicit transaction.
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            account = data.account();
            proposal = data.proposal(account);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "HOLD"})
    void savesAValidatedDecisionWithoutPerformingThe131StateTransition(String decision) {
        long option = data.option(proposal, 1);
        if (decision.equals("APPROVE")) data.successfulCheck(proposal, 1, account);
        long id =
                history.recordValidated(
                        proposal,
                        new ProposalDecisionRequest(1, decision, option, "검토 완료"),
                        login("ADMIN"));
        assertThat(data.count(proposal)).isEqualTo(1);
        jdbc.sql("select * from review_decision where id=:id")
                .param("id", id)
                .query(
                        (rs, row) -> {
                            assertThat(rs.getLong("decided_by")).isEqualTo(account);
                            assertThat(rs.getString("decision")).isEqualTo(decision);
                            assertThat(rs.getTimestamp("decided_at").toInstant()).isEqualTo(NOW);
                            return id;
                        })
                .single();
        assertThat(
                        jdbc.sql("select review_status from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(String.class)
                                .single())
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.sql("select apply_status from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(String.class)
                                .single())
                .isEqualTo("NOT_APPLIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void unauthorizedRequestsNeverInsertHistory(String role) {
        assertThatThrownBy(() -> history.recordValidated(proposal, request(), login(role)))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void missingLoginNeverInsertsHistory() {
        assertThatThrownBy(() -> history.recordValidated(proposal, request(), null))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void revisionConflictNeverInsertsHistory() {
        data.revise(proposal, 2);
        assertThatThrownBy(() -> history.recordValidated(proposal, request(), login("ADMIN")))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void approvalWithoutSuccessfulCheckNeverInsertsHistory() {
        assertThatThrownBy(
                        () ->
                                history.recordValidated(
                                        proposal,
                                        new ProposalDecisionRequest(1, "APPROVE", null, "승인 사유"),
                                        login("ADMIN")))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void wrongOptionNeverInsertsHistory() {
        long other = data.proposal(account);
        long option = data.option(other, 1);
        assertThatThrownBy(
                        () ->
                                history.recordValidated(
                                        proposal,
                                        new ProposalDecisionRequest(1, "HOLD", option, "사유"),
                                        login("ADMIN")))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void finalizedCandidateNeverGetsAnotherHistoryRow(String status) {
        jdbc.sql("update proposal_item set review_status=:status where id=:id")
                .param("status", status)
                .param("id", proposal)
                .update();
        assertThatThrownBy(() -> history.recordValidated(proposal, request(), login("ADMIN")))
                .isInstanceOf(BusinessException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void internalCallsAlsoRejectBlankReasons(String reason) {
        assertThatThrownBy(
                        () ->
                                history.recordValidated(
                                        proposal,
                                        new ProposalDecisionRequest(1, "HOLD", null, reason),
                                        login("ADMIN")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0,HOLD", "1,OTHER"})
    void internalCallsAlsoValidateRevisionAndDecision(int revision, String decision) {
        assertThatThrownBy(
                        () ->
                                history.recordValidated(
                                        proposal,
                                        new ProposalDecisionRequest(revision, decision, null, "사유"),
                                        login("ADMIN")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresCallerTransaction() {
        assertThatThrownBy(
                        () ->
                                history.recordValidated(
                                        1,
                                        request(),
                                        new LoginAccount(1L, "admin", "ADMIN", false)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackInsertedHistoryWhenTheCallerFails() {
        AtomicLong id = new AtomicLong();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            long actor = data.account();
                                            long candidate = data.proposal(actor);
                                            id.set(
                                                    history.recordValidated(
                                                            candidate,
                                                            request(),
                                                            new LoginAccount(
                                                                    actor, "admin", "ADMIN",
                                                                    false)));
                                            assertThat(data.count(candidate)).isEqualTo(1);
                                            throw new IllegalStateException(
                                                    "후속 반영 실패를 대신하는 테스트 예외");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(id.get()).isPositive();
        assertThat(
                        jdbc.sql("select count(*) from review_decision where id=:id")
                                .param("id", id.get())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private ProposalDecisionRequest request() {
        return new ProposalDecisionRequest(1, "HOLD", null, "사유");
    }

    private LoginAccount login(String role) {
        return new LoginAccount(account, "admin", role, false);
    }
}

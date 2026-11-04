package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ProposalDecisionCheckRepository.class,
    ProposalDecisionValidator.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProposalDecisionValidatorTransactionTests {
    @Autowired ProposalDecisionValidator validator;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void requiresTheCallerToOwnTheDecisionTransaction() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        1L,
                                        new ProposalDecisionRequest(1, "HOLD", null, "사유"),
                                        new LoginAccount(1L, "admin", "ADMIN", false)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void keepsTheCandidateLockedUntilTheCallingTransactionFinishes() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        long account =
                jdbc.sql(
                                """
                insert into account(login_id, display_name, password_hash, role_id)
                values (:login, '관리자', 'test', (select id from role where code='ADMIN')) returning id
                """)
                        .param("login", "decision-lock-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long id =
                jdbc.sql(
                                """
                with candidate as (
                    insert into proposal_item(origin, item_kind, payload, created_by)
                    values ('MANUAL', 'LINK_RULE', '{}'::jsonb, :account) returning *
                )
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, created_by)
                select id, revision, payload, evidence, created_by from candidate returning proposal_item_id
                """)
                        .param("account", account)
                        .query(Long.class)
                        .single();

        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(
                    transaction -> {
                        var candidate =
                                validator.validate(
                                        id,
                                        new ProposalDecisionRequest(1, "HOLD", null, "검토 보류"),
                                        new LoginAccount(account, "admin", "ADMIN", false));
                        assertThat(candidate.getId()).isEqualTo(id);
                        var competingEdit =
                                executor.submit(
                                        () ->
                                                tx.executeWithoutResult(
                                                        other -> {
                                                            jdbc.sql(
                                                                            "set local lock_timeout = '250ms'")
                                                                    .update();
                                                            jdbc.sql(
                                                                            "update proposal_item set row_version = row_version + 1 where id=:id")
                                                                    .param("id", id)
                                                                    .update();
                                                        }));
                        Throwable failure =
                                catchThrowable(() -> competingEdit.get(5, TimeUnit.SECONDS));
                        assertThat(failure).isNotNull();
                        while (failure.getCause() != null) failure = failure.getCause();
                        assertThat(failure).isInstanceOf(SQLException.class);
                        assertThat(((SQLException) failure).getSQLState()).isEqualTo("55P03");
                    });
        }

        assertThat(
                        jdbc.sql("select row_version from proposal_item where id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
        tx.executeWithoutResult(
                transaction ->
                        assertThat(
                                        jdbc.sql(
                                                        "update proposal_item set row_version = row_version + 1 where id=:id")
                                                .param("id", id)
                                                .update())
                                .isEqualTo(1));
    }
}

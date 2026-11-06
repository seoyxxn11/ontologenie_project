package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplier;
import com.axcore.platform.ontology.service.ProposalApplyService;
import com.axcore.platform.review.DecisionHistoryFixtures;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.dto.ProposalRevisionCreateRequest;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "test.context=proposal-decision-service")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, ProposalDecisionServiceTests.ApplyConfiguration.class})
class ProposalDecisionServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-05T05:00:00Z");
    @Autowired ProposalDecisionService service;
    @Autowired MockMvc mvc;
    @Autowired ProposalCommandService revisions;
    @Autowired ProposalApplyService applyService;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RecordingApplier applier;
    @MockitoSpyBean AuditRecorder audits;
    private DecisionHistoryFixtures data;
    private long account;
    private long proposal;

    @TestConfiguration
    static class ApplyConfiguration {
        @Bean
        RecordingApplier recordingApplier(JdbcClient jdbc) {
            return new RecordingApplier(jdbc);
        }

        @Bean
        @Primary
        Clock decisionClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /** Only a transaction probe; this is not the production OBJECT_TYPE implementation. */
    static class RecordingApplier implements ProposalApplier {
        private final JdbcClient jdbc;
        final AtomicInteger calls = new AtomicInteger();
        volatile ApplyCommand command;
        volatile ApplyErrorCode failure;
        volatile boolean failBeforeCommit;
        volatile boolean failDatabaseCommit;
        volatile boolean nullResult;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        RecordingApplier(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public ProposalItemKind kind() {
            return ProposalItemKind.OBJECT_TYPE;
        }

        @Override
        public ApplyResult apply(ApplyCommand input) {
            calls.incrementAndGet();
            command = input;
            long id =
                    jdbc.sql(
                                    "insert into object_type(code,name) values (:code,'transaction probe') returning id")
                            .param("code", "DECISION_" + input.proposalItemId())
                            .query(Long.class)
                            .single();
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("apply release timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            if (failure != null) {
                throw new BusinessException(failure, Map.of("field", "payload.code"));
            }
            if (failDatabaseCommit) {
                // Existing initially-deferred FK detects this missing revision only at COMMIT.
                jdbc.sql(
                                "insert into proposal_item(origin,item_kind,payload,created_by) values ('MANUAL','LINK_RULE','{}'::jsonb,:actor)")
                        .param("actor", input.decidedBy())
                        .update();
            }
            if (nullResult) return null;
            if (failBeforeCommit) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("commit phase failure");
                            }
                        });
            }
            // Nonempty IDs deliberately exercise result forwarding, not real held-item creation.
            return new ApplyResult(
                    "object_type",
                    id,
                    List.of(71L),
                    List.of(91L),
                    Map.of("createdCount", 1),
                    List.of(
                            ChangeEntry.insert(
                                    "object_type",
                                    Map.of("id", id),
                                    Map.of("code", "DECISION_" + input.proposalItemId()))));
        }
    }

    @BeforeEach
    void prepare() {
        data = new DecisionHistoryFixtures(jdbc);
        account = data.account();
        proposal = data.proposal(account, "{\"code\":\"IMMUTABLE\",\"optional\":null}");
        jdbc.sql("update proposal_item set item_kind='OBJECT_TYPE' where id=:id")
                .param("id", proposal)
                .update();
        data.successfulCheck(proposal, 1, account);
        applier.calls.set(0);
        applier.command = null;
        applier.failure = null;
        applier.failBeforeCommit = false;
        applier.failDatabaseCommit = false;
        applier.nullResult = false;
        applier.entered = null;
        applier.release = null;
    }

    @ParameterizedTest
    @CsvSource({"AI,false", "MANUAL,true"})
    void appliesTheImmutablePayloadAndAuditsTheActualResult(String origin, boolean selected)
            throws Exception {
        Long analysis = null;
        if (origin.equals("AI")) {
            analysis =
                    jdbc.sql(
                                    "insert into analysis_run(run_kind,work_key,requested_by) values ('INITIAL',:key,:account) returning id")
                            .param("key", UUID.randomUUID().toString())
                            .param("account", account)
                            .query(Long.class)
                            .single();
        }
        jdbc.sql(
                        "update proposal_item set origin=:origin,analysis_run_id=:analysis,payload='{\"code\":\"COPY\"}'::jsonb where id=:id")
                .param("origin", origin)
                .param("analysis", analysis, Types.BIGINT)
                .param("id", proposal)
                .update();
        Long option =
                selected
                        ? data.option(
                                proposal,
                                1,
                                "{\"code\":\"OPTION\",\"optional\":null,\"sourceColumn\":{\"sourceId\":\"ERP_MAIN\",\"resourceKey\":\"erp.attendance\",\"table\":\"attendance\",\"column\":\"memo\"}}")
                        : null;
        var result = service.decide(proposal, request(option), login());

        assertThat(applier.command.proposalItemId()).isEqualTo(proposal);
        assertThat(applier.command.itemKind()).isEqualTo(ProposalItemKind.OBJECT_TYPE);
        assertThat(applier.command.origin()).isEqualTo(ProposalOrigin.valueOf(origin));
        assertThat(applier.command.decidedBy()).isEqualTo(account);
        assertThat(applier.command.payload())
                .containsEntry("code", selected ? "OPTION" : "IMMUTABLE")
                .containsEntry("optional", null);
        if (selected) {
            assertThat(applier.command.payload().get("sourceColumn"))
                    .isEqualTo(
                            Map.of(
                                    "sourceId",
                                    "ERP_MAIN",
                                    "resourceKey",
                                    "erp.attendance",
                                    "table",
                                    "attendance",
                                    "column",
                                    "memo"));
        }
        assertThat(result.reviewStatus()).isEqualTo("APPROVED");
        assertThat(result.applyStatus()).isEqualTo("APPLIED");
        assertThat(result.appliedEntityIds()).containsExactly(targetId());
        assertThat(result.heldItemIds()).containsExactly(71L);
        assertThat(data.count(proposal)).isEqualTo(1);
        assertThat(
                        jdbc.sql("select applied_at from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(Instant.class)
                                .single())
                .isEqualTo(NOW);

        var audit =
                json.readTree(
                        jdbc.sql("select result::text from action_execution where id=:id")
                                .param("id", result.actionExecutionId())
                                .query(String.class)
                                .single());
        assertThat(audit.path("decisionId").asLong()).isEqualTo(result.decisionId());
        assertThat(audit.path("targetTable").asText()).isEqualTo("object_type");
        assertThat(audit.path("targetId").asLong()).isEqualTo(targetId());
        assertThat(audit.at("/reloadResourceIds/0").asLong()).isEqualTo(91L);
        assertThat(audit.at("/summary/createdCount").asInt()).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "select table_name from entity_change where action_execution_id=:id order by table_name")
                                .param("id", result.actionExecutionId())
                                .query(String.class)
                                .list())
                .containsExactly("object_type", "proposal_item", "review_decision");
        var savedChange =
                json.readTree(
                        jdbc.sql(
                                        "select after_data::text from entity_change where action_execution_id=:id and table_name='proposal_item'")
                                .param("id", result.actionExecutionId())
                                .query(String.class)
                                .single());
        assertThat(savedChange.path("row_version").asInt())
                .isEqualTo(
                        jdbc.sql("select row_version from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(Integer.class)
                                .single());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollsBackAppliedRowsAndPreservesTheInternalBusinessError(boolean held) {
        if (held) {
            jdbc.sql("update proposal_item set review_status='HELD' where id=:id")
                    .param("id", proposal)
                    .update();
        }
        applier.failure = ApplyErrorCode.APPLY_TARGET_EXISTS;
        assertThatThrownBy(() -> service.decide(proposal, request(null), login()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ApplyErrorCode.APPLY_TARGET_EXISTS);
                            assertThat(ex.getDetails()).containsEntry("field", "payload.code");
                            assertFailureAudit(
                                    ((Number) ex.getDetails().get("actionExecutionId"))
                                            .longValue());
                        });
        assertRolledBack(held ? "HELD" : "PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIT", "COMMIT", "DATABASE_COMMIT"})
    void rollsBackBusinessWritesAndSuccessAuditWhenFinishingFails(String stage) {
        if (stage.equals("AUDIT")) {
            doThrow(new IllegalStateException("success audit failure")).when(audits).record(any());
        } else if (stage.equals("COMMIT")) {
            applier.failBeforeCommit = true;
        } else {
            applier.failDatabaseCommit = true;
        }
        assertThatThrownBy(() -> service.decide(proposal, request(null), login()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
                            assertFailureAudit(
                                    ((Number) ex.getDetails().get("actionExecutionId"))
                                            .longValue());
                        });
        assertRolledBack("PENDING");
        assertThat(
                        jdbc.sql(
                                        "select count(*) from action_execution where target_type='proposal_item' and target_id=:id and status='SUCCEEDED'")
                                .param("id", proposal)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void failureAuditFailureDoesNotReplaceTheOriginalApplyErrorOrInventAnId() {
        applier.failure = ApplyErrorCode.APPLY_PAYLOAD_INVALID;
        doThrow(new IllegalStateException("failure audit unavailable"))
                .when(audits)
                .recordSeparately(any());
        assertThatThrownBy(() -> service.decide(proposal, request(null), login()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ApplyErrorCode.APPLY_PAYLOAD_INVALID);
                            assertThat(ex.getDetails())
                                    .containsEntry("field", "payload.code")
                                    .doesNotContainKey("actionExecutionId");
                        });
        assertRolledBack("PENDING");
    }

    @Test
    void refusesAnAmbientTransactionBeforeWritingAnything() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status ->
                                                        service.decide(
                                                                proposal, request(null), login())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(applier.calls).hasValue(0);
        assertThat(data.count(proposal)).isZero();
    }

    @Test
    void theProvidedApplyServiceRequiresTheCallingTransaction() {
        assertThatThrownBy(
                        () ->
                                applyService.apply(
                                        new ApplyCommand(
                                                proposal,
                                                ProposalItemKind.OBJECT_TYPE,
                                                ProposalOrigin.MANUAL,
                                                Map.of(),
                                                account)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(applier.calls).hasValue(0);
    }

    @Test
    void onlyOneConcurrentFinalDecisionAppliesAndCommits() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> decide =
                    () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("start timeout");
                        try {
                            service.decide(proposal, request(null), login());
                            return "OK";
                        } catch (BusinessException ex) {
                            return ex.getErrorCode().code();
                        }
                    };
            var first = pool.submit(decide);
            var second = pool.submit(decide);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "PROPOSAL_ALREADY_DECIDED");
        }
        assertThat(applier.calls).hasValue(1);
        assertThat(data.count(proposal)).isEqualTo(1);
        assertThat(targetId()).isPositive();
    }

    @Test
    void aRevisionWaitsForTheDecisionLockAndThenRejectsTheFinalState() throws Exception {
        applier.entered = new CountDownLatch(1);
        applier.release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> service.decide(proposal, request(null), login()));
            assertThat(applier.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var revisionStarted = new CountDownLatch(1);
            var revision =
                    pool.submit(
                            () -> {
                                revisionStarted.countDown();
                                try {
                                    revisions.revise(
                                            proposal,
                                            new ProposalRevisionCreateRequest(
                                                    1, Map.of("code", "EDIT"), Map.of(), List.of()),
                                            login());
                                    return "REVISED";
                                } catch (BusinessException failure) {
                                    return failure.getErrorCode().code();
                                }
                            });
            assertThat(revisionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> revision.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                applier.release.countDown();
            }
            assertThat(approval.get(20, TimeUnit.SECONDS).reviewStatus()).isEqualTo("APPROVED");
            assertThat(revision.get(20, TimeUnit.SECONDS)).isEqualTo("PROPOSAL_ALREADY_DECIDED");
        } finally {
            applier.release.countDown();
        }
        assertThat(
                        jdbc.sql("select revision from proposal_item where id=:id")
                                .param("id", proposal)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(data.count(proposal)).isEqualTo(1);
        assertThat(applier.calls).hasValue(1);
    }

    @Test
    void approvalApiForwardsTheResultIdsAndExposesOnlyTheSpecifiedFields() throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/proposals/{id}/decisions", proposal)
                                        .with(authentication(login().toAuthentication()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "revision",
                                                                1,
                                                                "decision",
                                                                "APPROVE",
                                                                "reason",
                                                                "검사 확인"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                        .andExpect(jsonPath("$.data.applyStatus").value("APPLIED"))
                        .andExpect(jsonPath("$.data.heldItemIds[0]").value(71))
                        .andExpect(jsonPath("$.data.reloadResourceIds").doesNotExist())
                        .andExpect(jsonPath("$.data.summary").doesNotExist())
                        .andExpect(jsonPath("$.data.itemKind").doesNotExist())
                        .andReturn();
        var result = json.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.at("/appliedEntityIds/0").asLong()).isEqualTo(targetId());
        assertThat(result.path("decisionId").asLong()).isPositive();
        assertThat(result.path("actionExecutionId").asLong()).isPositive();
    }

    @Test
    void rejectsANullApplyResultInsteadOfApprovingWithoutAReportedTarget() {
        applier.nullResult = true;
        assertThatThrownBy(() -> service.decide(proposal, request(null), login()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> {
                            assertThat(failure.getErrorCode())
                                    .isEqualTo(CommonErrorCode.INTERNAL_ERROR);
                            assertFailureAudit(
                                    ((Number) failure.getDetails().get("actionExecutionId"))
                                            .longValue());
                        });
        assertRolledBack("PENDING");
    }

    private ProposalDecisionRequest request(Long option) {
        return new ProposalDecisionRequest(1, "APPROVE", option, "검사 결과 확인");
    }

    private LoginAccount login() {
        return new LoginAccount(account, "reviewer", "ADMIN", false);
    }

    private long targetId() {
        return jdbc.sql("select id from object_type where code=:code")
                .param("code", "DECISION_" + proposal)
                .query(Long.class)
                .single();
    }

    private void assertRolledBack(String reviewStatus) {
        assertThat(data.count(proposal)).isZero();
        var item =
                jdbc.sql(
                                "select review_status,apply_status,applied_at,apply_error from proposal_item where id=:id")
                        .param("id", proposal)
                        .query()
                        .singleRow();
        assertThat(item)
                .containsEntry("review_status", reviewStatus)
                .containsEntry("apply_status", "NOT_APPLIED")
                .containsEntry("applied_at", null)
                .containsEntry("apply_error", null);
        assertThat(
                        jdbc.sql("select count(*) from object_type where code=:code")
                                .param("code", "DECISION_" + proposal)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private void assertFailureAudit(long id) {
        assertThat(
                        jdbc.sql("select status from action_execution where id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.sql("select count(*) from entity_change where action_execution_id=:id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
    }
}

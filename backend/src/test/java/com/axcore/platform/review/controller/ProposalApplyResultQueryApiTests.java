package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplier;
import com.axcore.platform.ontology.service.ProposalApplyService;
import com.axcore.platform.review.DecisionHistoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 133: 실행 종료 후 별도 HTTP 요청으로 049·055·096·097과 보류 건 참조를 검증한다. */
@SpringBootTest(properties = "test.context=proposal-apply-result-query")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({
    TestcontainersConfiguration.class,
    ProposalApplyResultQueryApiTests.ApplyConfiguration.class
})
class ProposalApplyResultQueryApiTests {
    private static final String AUDIT = "/api/v1/audit/actions";
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final QueryProbeApplier applier;
    private DecisionHistoryFixtures fixtures;
    private long actor;
    private long proposal;

    @Autowired
    ProposalApplyResultQueryApiTests(
            MockMvc mvc, JdbcClient jdbc, JsonMapper json, QueryProbeApplier applier) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.applier = applier;
    }

    @TestConfiguration
    static class ApplyConfiguration {
        @Bean
        QueryProbeApplier queryProbeApplier(JdbcClient jdbc) {
            return new QueryProbeApplier(jdbc);
        }

        @Bean
        @Primary
        ProposalApplyService probeApplyService(QueryProbeApplier probe) {
            return new ProposalApplyService(
                    List.of(
                            new ProposalApplier() {
                                @Override
                                public ProposalItemKind kind() {
                                    return probe.kind();
                                }

                                @Override
                                public ApplyResult apply(ApplyCommand command) {
                                    return probe.apply(command);
                                }
                            }));
        }

        @Bean
        @Primary
        Clock resultQueryClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /**
     * 실제 반영기가 아닌 DB 트랜잭션 probe. 생성한 규칙·보류 행으로 조회 연결만 확인한다. 반영기 빈(ProposalApplier)으로 등록하지 않는다. 같은
     * 종류의 실제 반영기(189 LINK_RULE)와 겹치면 컨텍스트가 뜨지 않으므로, 이 probe만 쓰는 ProposalApplyService를 @Primary로 둔다.
     */
    static class QueryProbeApplier {
        private final JdbcClient jdbc;
        long linkType;
        boolean businessFailure;
        boolean commitFailure;

        QueryProbeApplier(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        ProposalItemKind kind() {
            return ProposalItemKind.LINK_RULE;
        }

        ApplyResult apply(ApplyCommand command) {
            long mapping =
                    jdbc.sql(
                                    """
                    insert into link_mapping(link_type_id, code, revision, proposal_item_id, created_by)
                    values (:type, :code, 1, :proposal, :actor) returning id
                    """)
                            .param("type", linkType)
                            .param("code", "QUERY_RESULT_" + command.proposalItemId())
                            .param("proposal", command.proposalItemId())
                            .param("actor", command.decidedBy())
                            .query(Long.class)
                            .single();
            if (businessFailure) {
                throw new BusinessException(ApplyErrorCode.APPLY_REFERENCE_MISSING);
            }
            if (commitFailure) {
                // 기존 지연 FK를 커밋 시 위반시킨다. 업무 행과 성공 감사가 함께 롤백돼야 한다.
                jdbc.sql(
                                """
                        insert into proposal_item(origin, item_kind, payload, created_by)
                        values ('MANUAL', 'LINK_RULE', '{}'::jsonb, :actor)
                        """)
                        .param("actor", command.decidedBy())
                        .update();
            }
            return new ApplyResult(
                    "link_mapping",
                    mapping,
                    List.of(),
                    Map.of("linksActivated", 1),
                    List.of(
                            ChangeEntry.insert(
                                    "link_mapping",
                                    Map.of("id", mapping),
                                    Map.of("proposal_item_id", command.proposalItemId()))));
        }
    }

    @BeforeEach
    void prepare() {
        fixtures = new DecisionHistoryFixtures(jdbc);
        actor = fixtures.account();
        proposal = fixtures.proposal(actor);
        fixtures.successfulCheck(proposal, 1, actor);
        long type =
                jdbc.sql(
                                "insert into object_type(code,name) values (:code,'조회 테스트 유형') returning id")
                        .param("code", UUID.randomUUID().toString())
                        .query(Long.class)
                        .single();
        applier.linkType =
                jdbc.sql(
                                """
                insert into link_type(code,name,source_type_id,target_type_id,cardinality,description)
                values (:code,'조회 테스트 관계',:type,:type,'MANY_TO_ONE','결과 조회 검증') returning id
                """)
                        .param("code", UUID.randomUUID().toString())
                        .param("type", type)
                        .query(Long.class)
                        .single();
        applier.businessFailure = false;
        applier.commitFailure = false;
    }

    @Test
    void readsCommittedApprovalThroughExistingApis() throws Exception {
        JsonNode executed = data(decide(proposal, 1, "APPROVE").andExpect(status().isOk()));
        long decision = executed.path("decisionId").asLong();
        long execution = executed.path("actionExecutionId").asLong();
        long mapping = executed.at("/appliedEntityIds/0").asLong();
        assertThat(mapping).isPositive();
        assertThat(executed.has("heldItemIds")).isFalse();

        read("/api/v1/proposals/" + proposal)
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.applyStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.appliedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.data.applyError").value(nullValue()));
        read("/api/v1/proposals/" + proposal + "/decisions")
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(decision))
                .andExpect(jsonPath("$.data[0].itemRevision").value(1))
                .andExpect(jsonPath("$.data[0].decision").value("APPROVE"))
                .andExpect(jsonPath("$.data[0].decidedBy").value(actor))
                .andExpect(jsonPath("$.data[0].reason").value("결과 조회 검증"));

        // 현행 프론트와 같은 후보·Action 필터 없는 목록에서도 표시할 데이터가 내려온다.
        read(AUDIT)
                .andExpect(jsonPath("$.meta.scope").value("ALL"))
                .andExpect(jsonPath("$.data[0].id").value(execution))
                .andExpect(jsonPath("$.data[0].targetType").value("proposal_item"))
                .andExpect(jsonPath("$.data[0].targetId").value(proposal))
                .andExpect(jsonPath("$.data[0].actionCode").value("DECIDE_PROPOSAL"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].changeCount").value(3))
                .andExpect(jsonPath("$.data[0].result").doesNotExist());
        read(AUDIT + "/" + execution)
                .andExpect(jsonPath("$.data.result.decisionId").value(decision))
                .andExpect(jsonPath("$.data.result.appliedEntityIds[0]").value(mapping))
                .andExpect(jsonPath("$.data.result.heldItemIds").doesNotExist())
                .andExpect(jsonPath("$.data.result.targetTable").value("link_mapping"))
                .andExpect(jsonPath("$.data.result.targetId").value(mapping))
                .andExpect(jsonPath("$.data.result.applyStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.result.summary.linksActivated").value(1));
        read(AUDIT + "/" + execution + "/changes")
                .andExpect(jsonPath("$.data.changes.length()").value(3));
        assertThat(
                        jdbc.sql("select id from link_mapping where proposal_item_id=:id")
                                .param("id", proposal)
                                .query(Long.class)
                                .single())
                .isEqualTo(mapping);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSINESS", "COMMIT"})
    void readsFailureAuditAfterRollbackAndPreservesThePreviousHold(String stage) throws Exception {
        JsonNode held = data(decide(proposal, 1, "HOLD").andExpect(status().isOk()));
        applier.businessFailure = stage.equals("BUSINESS");
        applier.commitFailure = stage.equals("COMMIT");
        JsonNode failure =
                body(
                        decide(proposal, 1, "APPROVE")
                                .andExpect(status().is(stage.equals("BUSINESS") ? 422 : 500)));
        long execution = failure.at("/error/details/actionExecutionId").asLong();
        String errorCode = stage.equals("BUSINESS") ? "APPLY_REFERENCE_MISSING" : "INTERNAL_ERROR";
        assertThat(execution).isPositive();
        assertUnapplied("HELD");
        assertNoAppliedRows();
        read("/api/v1/proposals/" + proposal + "/decisions")
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(held.path("decisionId").asLong()))
                .andExpect(jsonPath("$.data[0].decision").value("HOLD"));
        assertFailure(execution, errorCode);
        read(AUDIT + "?status=FAILED&from=2026-11-05T00:00:00Z&to=2026-11-06T00:00:00Z")
                .andExpect(jsonPath("$.data[0].id").value(execution))
                .andExpect(jsonPath("$.data[0].changeCount").value(0));
        long heldExecution = held.path("actionExecutionId").asLong();
        read(candidateAudits() + "&status=SUCCEEDED")
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(heldExecution));
        read(AUDIT + "/" + heldExecution)
                .andExpect(jsonPath("$.data.result.decision").value("HOLD"));
    }

    private void assertUnapplied(String reviewStatus) throws Exception {
        read("/api/v1/proposals/" + proposal)
                .andExpect(jsonPath("$.data.reviewStatus").value(reviewStatus))
                .andExpect(jsonPath("$.data.applyStatus").value("NOT_APPLIED"))
                .andExpect(jsonPath("$.data.applyError").value(nullValue()))
                .andExpect(jsonPath("$.data.appliedAt").value(nullValue()));
    }

    private void assertNoAppliedRows() {
        for (String table : List.of("link_mapping")) {
            assertThat(
                            jdbc.sql(
                                            "select count(*) from "
                                                    + table
                                                    + " where proposal_item_id=:id")
                                    .param("id", proposal)
                                    .query(Long.class)
                                    .single())
                    .isZero();
        }
    }

    private void assertFailure(long execution, String errorCode) throws Exception {
        read(AUDIT + "/" + execution)
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.input.proposalItemId").value(proposal))
                .andExpect(jsonPath("$.data.input.revision").value(1))
                .andExpect(jsonPath("$.data.input.decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.input.reason").value("결과 조회 검증"))
                .andExpect(jsonPath("$.data.result.errorCode").value(errorCode))
                .andExpect(jsonPath("$.data.result.reason").isNotEmpty())
                .andExpect(jsonPath("$.data.failureReason").isNotEmpty())
                .andExpect(jsonPath("$.data.result.decisionId").doesNotExist());
        read(AUDIT + "/" + execution + "/changes").andExpect(jsonPath("$.data.changes").isEmpty());
    }

    private String candidateAudits() {
        return AUDIT + "?actionCode=DECIDE_PROPOSAL&targetType=proposal_item&targetId=" + proposal;
    }

    private ResultActions decide(long id, int revision, String decision) throws Exception {
        return mvc.perform(
                post("/api/v1/proposals/{id}/decisions", id)
                        .with(login(actor, "ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "revision",
                                                revision,
                                                "decision",
                                                decision,
                                                "reason",
                                                "결과 조회 검증"))));
    }

    private ResultActions read(String path) throws Exception {
        return mvc.perform(get(path).with(login(actor, "ADMIN"))).andExpect(status().isOk());
    }

    private RequestPostProcessor login(long account, String role) {
        return authentication(
                new LoginAccount(account, "result-query", role, false).toAuthentication());
    }

    private JsonNode data(ResultActions result) throws Exception {
        return body(result).path("data");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}

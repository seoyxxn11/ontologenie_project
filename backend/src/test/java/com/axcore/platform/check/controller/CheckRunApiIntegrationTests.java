package com.axcore.platform.check.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.IssueTestFixtures;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** API 059 검사 실행 목록, 060 수동 검사·재검증 요청, 061 검사 상태 조회를 실제 PostgreSQL 제약까지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class CheckRunApiIntegrationTests {

    private static final String RUNS = "/api/v1/checks/runs";
    private static final String RUN = "/api/v1/checks/runs/{id}";
    private static final String MANUAL_ALL =
            "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"ALL\"}}";
    private static final Instant SYNCED_AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    private CheckRunTestFixtures fixtures;
    private long accountId;
    private long dataSource;
    private long workerTable;
    private long employeeTable;
    private long linkType;
    private long workerId;
    private long employeeNo;
    private long lastWorkerSync;

    @BeforeEach
    void prepare() {
        fixtures = new CheckRunTestFixtures(jdbcClient);
        fixtures.retireAllRules();
        accountId = fixtures.insertAdmin();
        long workerType = fixtures.insertObjectType("WORKER");
        long employeeType = fixtures.insertObjectType("EMPLOYEE");
        workerId = fixtures.insertProperty(workerType, "id");
        employeeNo = fixtures.insertProperty(employeeType, "emp_no");
        linkType = fixtures.insertLinkType(workerType, employeeType, "MANY_TO_ONE");
        dataSource = fixtures.insertDataSource();
        workerTable = fixtures.insertResource(dataSource, "mes.worker");
        employeeTable = fixtures.insertResource(dataSource, "erp.employee");
        fixtures.insertObjectMapping(workerTable, workerType, true);
        fixtures.insertObjectMapping(employeeTable, employeeType, true);
        // 작업자 표는 성공 뒤 실패한 수집이 있다. 기준은 마지막 성공이다. 직원 표는 아직 수집 전이다.
        lastWorkerSync =
                fixtures.insertSync(dataSource, workerTable, accountId, "SUCCEEDED", SYNCED_AT);
        fixtures.insertSync(
                dataSource, workerTable, accountId, "FAILED", SYNCED_AT.plusSeconds(60));
    }

    // ---- 060 접수 ----

    @Test
    void queuesManualCheckOfAllActiveRules() throws Exception {
        long ruleId = rule();
        fixtures.insertLinkMapping(linkType, "RETIRED", "{}");

        long runId =
                id(
                        request(MANUAL_ALL)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.triggerKind").value("MANUAL"))
                                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                                .andExpect(jsonPath("$.data.scope.kind").value("ALL"))
                                .andExpect(jsonPath("$.data.scope.linkMappingIds", hasSize(1)))
                                .andExpect(jsonPath("$.data.scope.linkMappingIds[0]").value(ruleId))
                                .andExpect(jsonPath("$.data.comparedToRunId").value(nullValue()))
                                .andExpect(jsonPath("$.data.requestedBy").value(accountId))
                                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                                .andExpect(jsonPath("$.data.errorMessage").value(nullValue()))
                                .andExpect(jsonPath("$.data.syncRunId").value(nullValue()))
                                .andExpect(jsonPath("$.data.dataBasis.resources", hasSize(2)))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[0].sourceResourceId")
                                                .value(workerTable))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[0].lastSyncRunId")
                                                .value(lastWorkerSync))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[1].sourceResourceId")
                                                .value(employeeTable))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[1].lastSyncRunId")
                                                .value(nullValue()))
                                .andExpect(jsonPath("$.data.joined").value(false))
                                .andExpect(jsonPath("$.data.reused").value(false)));

        Map<String, Object> row =
                jdbcClient
                        .sql("select trigger_kind, status, work_key from check_run where id = :id")
                        .param("id", runId)
                        .query()
                        .singleRow();
        assertThat(row).containsEntry("trigger_kind", "MANUAL").containsEntry("status", "QUEUED");
        assertThat((String) row.get("work_key")).startsWith("MANUAL:-:");
    }

    @Test
    void joinsQueuedCheckWithSameBasis() throws Exception {
        rule();
        long first = id(request(MANUAL_ALL).andExpect(status().isAccepted()));

        request(MANUAL_ALL)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.joined").value(true))
                .andExpect(jsonPath("$.data.reused").value(false));

        fixtures.setStatus(first, "RUNNING");
        request(MANUAL_ALL)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.joined").value(true));
        assertThat(runCount()).isEqualTo(1);
    }

    /** 389: 사람이 요청한 검사는 같은 기준으로 끝난 결과가 있어도 다시 쓰지 않고 새로 돈다. */
    @Test
    void runsAgainEvenAfterSucceededCheckWithSameBasis() throws Exception {
        rule();
        long first = id(request(MANUAL_ALL).andExpect(status().isAccepted()));
        fixtures.setStatus(first, "SUCCEEDED");

        long second =
                id(
                        request(MANUAL_ALL)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                                .andExpect(jsonPath("$.data.joined").value(false))
                                .andExpect(jsonPath("$.data.reused").value(false)));
        assertThat(second).isNotEqualTo(first);
    }

    /**
     * 개발 가이드 14.2: 오래 걸리는 작업은 접수 1줄과 결과 1줄을 남긴다. 접수 줄은 요청한 사람·SUCCEEDED이고 합류해도 남긴다. 범위 종류와 개수만 남기고
     * ID 목록은 넣지 않는다.
     */
    @Test
    void recordsIntakeLineForNewAndJoinedRequests() throws Exception {
        rule();
        long first = id(request(MANUAL_ALL).andExpect(status().isAccepted()));
        request(MANUAL_ALL).andExpect(status().isAccepted());

        List<Map<String, Object>> lines =
                jdbcClient
                        .sql(
                                """
                                select actor_type, actor_id, entry_point, status, event_kind,
                                       checked_permission_code, input::text as input, result::text as result
                                from action_execution
                                where target_type = 'check_run' and target_id = :id
                                order by id
                                """)
                        .param("id", first)
                        .query()
                        .listOfRows();
        assertThat(lines).hasSize(2);
        assertThat(lines)
                .allSatisfy(
                        line ->
                                assertThat(line)
                                        .containsEntry("actor_type", "USER")
                                        .containsEntry("actor_id", accountId)
                                        .containsEntry("entry_point", "WEB")
                                        .containsEntry("status", "SUCCEEDED")
                                        .containsEntry("event_kind", "CHECK_RUN")
                                        .containsEntry("checked_permission_code", "ADMIN"));
        assertThat((String) lines.get(0).get("input"))
                .contains("\"triggerKind\": \"MANUAL\"")
                .contains("\"scopeKind\": \"ALL\"")
                .contains("\"linkMappingCount\": 1")
                .doesNotContain("linkMappingIds");
        assertThat((String) lines.get(0).get("result"))
                .contains("\"joined\": false", "\"reused\": false");
        assertThat((String) lines.get(1).get("result")).contains("\"joined\": true");
    }

    @Test
    void startsNewCheckAfterNewSyncOrFailure() throws Exception {
        rule();
        long first = id(request(MANUAL_ALL).andExpect(status().isAccepted()));
        fixtures.setStatus(first, "SUCCEEDED");

        // 새 수집이 성공하면 기준이 바뀌어 새 검사다.
        long sync =
                fixtures.insertSync(
                        dataSource,
                        employeeTable,
                        accountId,
                        "SUCCEEDED",
                        SYNCED_AT.plusSeconds(120));
        long second =
                id(
                        request(MANUAL_ALL)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.reused").value(false))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[1].lastSyncRunId")
                                                .value(sync)));
        assertThat(second).isNotEqualTo(first);

        // 실패한 검사는 다시 쓰지 않는다.
        fixtures.setStatus(second, "FAILED");
        long third = id(request(MANUAL_ALL).andExpect(status().isAccepted()));
        assertThat(third).isNotEqualTo(second);
    }

    @Test
    void checksChosenRulesInIdOrder() throws Exception {
        long a = rule();
        long b = rule();

        request(
                        "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"LINK_MAPPINGS\","
                                + "\"linkMappingIds\":["
                                + b
                                + ","
                                + a
                                + ","
                                + b
                                + "]}}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.scope.kind").value("LINK_MAPPINGS"))
                .andExpect(jsonPath("$.data.scope.linkMappingIds", hasSize(2)))
                .andExpect(jsonPath("$.data.scope.linkMappingIds[0]").value(a))
                .andExpect(jsonPath("$.data.scope.linkMappingIds[1]").value(b));
    }

    @Test
    void rejectsRetiredOrMissingRule() throws Exception {
        long active = rule();
        long retired = fixtures.insertLinkMapping(linkType, "RETIRED", "{}");

        request(
                        "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"LINK_MAPPINGS\","
                                + "\"linkMappingIds\":["
                                + active
                                + ","
                                + retired
                                + ","
                                + Long.MAX_VALUE
                                + "]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value("scope.linkMappingIds"))
                .andExpect(jsonPath("$.error.details.linkMappingIds", hasSize(2)))
                .andExpect(jsonPath("$.error.details.linkMappingIds[0]").value(retired));
        assertThat(runCount()).isZero();
    }

    @Test
    void rejectsWhenNoRuleIsActive() throws Exception {
        request(MANUAL_ALL)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("scope"));
    }

    @Test
    void rejectsRuleThatCannotBecomeCheckInput() throws Exception {
        long withoutCondition = fixtures.insertLinkMapping(linkType, "ACTIVE", "{}");

        request(MANUAL_ALL)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("scope"))
                .andExpect(jsonPath("$.error.details.linkMappingId").value(withoutCondition))
                .andExpect(
                        jsonPath("$.error.details.reason")
                                .value(startsWith("규칙 " + withoutCondition)));
        assertThat(runCount()).isZero();
    }

    @Test
    void queuesReverificationOfIssues() throws Exception {
        long ruleId = rule();
        long previous = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long issueId = issue(previous, ruleId);

        request(reverify(previous, "{\"kind\":\"ISSUES\",\"issueIds\":[" + issueId + "]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.triggerKind").value("REVERIFY"))
                .andExpect(jsonPath("$.data.comparedToRunId").value(previous))
                .andExpect(jsonPath("$.data.scope.kind").value("ISSUES"))
                .andExpect(jsonPath("$.data.scope.issueIds[0]").value(issueId))
                .andExpect(jsonPath("$.data.scope.linkMappingIds[0]").value(ruleId));
    }

    /** 규칙을 개정한 뒤에도 옛 개정본에서 나온 열린 문제는 같은 코드의 반영 중인 개정본으로 재검증한다(246). */
    @Test
    void reverifiesIssuesWithTheCurrentRevisionOfTheirRule() throws Exception {
        long first = rule();
        long previous = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long issueId = issue(previous, first);
        long sameRuleIssue = issue(previous, first);
        String code =
                jdbcClient
                        .sql("select code from link_mapping where id = :id")
                        .param("id", first)
                        .query(String.class)
                        .single();
        jdbcClient
                .sql(
                        "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                .param("id", first)
                .update();
        long second =
                jdbcClient
                        .sql(
                                """
                                insert into link_mapping (link_type_id, code, revision, status)
                                values (:type, :code, 2, 'ACTIVE') returning id
                                """)
                        .param("type", linkType)
                        .param("code", code)
                        .query(Long.class)
                        .single();
        fixtures.insertCondition(second, 1, workerId, employeeNo, "[]", "[]");

        request(
                        reverify(
                                previous,
                                "{\"kind\":\"ISSUES\",\"issueIds\":["
                                        + issueId
                                        + ","
                                        + sameRuleIssue
                                        + "]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.scope.linkMappingIds", hasSize(1)))
                .andExpect(jsonPath("$.data.scope.linkMappingIds[0]").value(second));
    }

    /** 대기 중인 같은 재검증에는 합류하고, 끝난 재검증은 데이터가 그대로여도 다시 쓰지 않고 새로 돈다(389). */
    @Test
    void joinsReverificationButRunsAgainAfterItFinished() throws Exception {
        long ruleId = rule();
        long detected = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long issueId = issue(detected, ruleId);
        String body = reverify(detected, "{\"kind\":\"ISSUES\",\"issueIds\":[" + issueId + "]}");

        long first = id(request(body).andExpect(status().isAccepted()));
        request(body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.joined").value(true));

        fixtures.setStatus(first, "SUCCEEDED");
        long second =
                id(
                        request(body)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                                .andExpect(jsonPath("$.data.reused").value(false))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[0].lastSyncRunId")
                                                .value(lastWorkerSync)));
        assertThat(second).isNotEqualTo(first);
        fixtures.setStatus(second, "SUCCEEDED");

        long sync =
                fixtures.insertSync(
                        dataSource,
                        workerTable,
                        accountId,
                        "SUCCEEDED",
                        SYNCED_AT.plusSeconds(120));
        long next =
                id(
                        request(body)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.reused").value(false))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[0].lastSyncRunId")
                                                .value(sync)));
        assertThat(next).isNotEqualTo(first).isNotEqualTo(second);
    }

    @Test
    void joinsRunningReverificationOnlyWhileNoNewDataWasCollected() throws Exception {
        long ruleId = rule();
        long detected = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long issueId = issue(detected, ruleId);
        String body = reverify(detected, "{\"kind\":\"ISSUES\",\"issueIds\":[" + issueId + "]}");

        long first = id(request(body).andExpect(status().isAccepted()));
        fixtures.setStatus(first, "RUNNING");

        // 수집 기준이 그대로면 실행 중인 작업이 지금 데이터를 읽은 것과 같으므로 합류한다.
        request(body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.joined").value(true));

        // 실행 중인 작업이 시작한 뒤 새 수집이 끝났으면, 그 작업은 새 데이터를 보지 못하므로 새로 넣는다.
        long sync =
                fixtures.insertSync(
                        dataSource,
                        workerTable,
                        accountId,
                        "SUCCEEDED",
                        SYNCED_AT.plusSeconds(120));
        long next =
                id(
                        request(body)
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.joined").value(false))
                                .andExpect(jsonPath("$.data.reused").value(false))
                                .andExpect(
                                        jsonPath("$.data.dataBasis.resources[0].lastSyncRunId")
                                                .value(sync)));
        assertThat(next).isNotEqualTo(first);

        // 새로 넣은 작업은 아직 대기 중이라 다음 요청은 거기에 합류한다.
        request(body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(next))
                .andExpect(jsonPath("$.data.joined").value(true));
    }

    @Test
    void keepsManualAndReverificationApart() throws Exception {
        rule();
        long previous = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long manual = id(request(MANUAL_ALL).andExpect(status().isAccepted()));

        long reverify =
                id(
                        request(reverify(previous, "{\"kind\":\"ALL\"}"))
                                .andExpect(status().isAccepted()));

        assertThat(reverify).isNotEqualTo(manual);
    }

    @Test
    void rejectsUnusableComparison() throws Exception {
        rule();
        long candidate = fixtures.insertRun("CANDIDATE", "SUCCEEDED", null, key());
        long failed = fixtures.insertRun("MANUAL", "FAILED", accountId, key());
        long queued = fixtures.insertRun("MANUAL", "QUEUED", accountId, key());

        request(reverify(Long.MAX_VALUE, "{\"kind\":\"ALL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("comparedToRunId"));
        request(reverify(candidate, "{\"kind\":\"ALL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.triggerKind").value("CANDIDATE"));
        request(reverify(failed, "{\"kind\":\"ALL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.status").value("FAILED"));
        request(reverify(queued, "{\"kind\":\"ALL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.status").value("QUEUED"));
    }

    @Test
    void rejectsUnusableIssues() throws Exception {
        long ruleId = rule();
        long previous = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        IssueTestFixtures issues = new IssueTestFixtures(jdbcClient);
        long resolved = issue(previous, ruleId);
        issues.resolve(resolved, issues.insertReverifyRun(previous, accountId));
        long onRetired = issue(previous, fixtures.insertLinkMapping(linkType, "RETIRED", "{}"));

        request(reverify(previous, "{\"kind\":\"ISSUES\",\"issueIds\":[" + Long.MAX_VALUE + "]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.issueIds[0]").value(Long.MAX_VALUE));
        request(reverify(previous, "{\"kind\":\"ISSUES\",\"issueIds\":[" + resolved + "]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.reason").value("해결된 문제는 재검증하지 않습니다."));
        // 폐기만 되고 반영 중인 개정본이 없는 규칙 코드
        request(reverify(previous, "{\"kind\":\"ISSUES\",\"issueIds\":[" + onRetired + "]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.field").value("scope.issueIds"))
                .andExpect(jsonPath("$.error.details.issueIds[0]").value(onRetired))
                .andExpect(jsonPath("$.error.details.linkMappingCodes", hasSize(1)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"scope\":{\"kind\":\"ALL\"}}",
                "{\"triggerKind\":\"SYNC\",\"scope\":{\"kind\":\"ALL\"}}",
                "{\"triggerKind\":\"MANUAL\"}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"SOME\"}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"ALL\",\"linkMappingIds\":[1]}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"LINK_MAPPINGS\"}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"LINK_MAPPINGS\",\"linkMappingIds\":[0]}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"ISSUES\",\"issueIds\":[1]}}",
                "{\"triggerKind\":\"MANUAL\",\"scope\":{\"kind\":\"ALL\"},\"comparedToRunId\":1}",
                "{\"triggerKind\":\"REVERIFY\",\"scope\":{\"kind\":\"ALL\"}}",
                "{\"triggerKind\":\"REVERIFY\",\"scope\":{\"kind\":\"ISSUES\"},\"comparedToRunId\":1}"
            })
    void rejectsMalformedRequest(String body) throws Exception {
        request(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(runCount()).isZero();
    }

    // ---- 059 목록, 061 상태 ----

    @Test
    void listsRunsNewestFirstWithFilters() throws Exception {
        long older = fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, key());
        long candidate = fixtures.insertRun("CANDIDATE", "QUEUED", null, key());
        long newer = fixtures.insertRun("MANUAL", "FAILED", accountId, key());
        fixtures.setRequestedAt(older, Instant.parse("2026-11-01T00:00:00Z"));
        fixtures.setRequestedAt(candidate, Instant.parse("2026-11-02T00:00:00Z"));
        fixtures.setRequestedAt(newer, Instant.parse("2026-11-03T00:00:00Z"));

        mockMvc.perform(
                        get(RUNS)
                                .param("triggerKind", "MANUAL")
                                .param("from", "2026-11-01T00:00:00Z")
                                .param("to", "2026-11-03T00:00:00Z")
                                .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(newer))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("테스트 실패"))
                .andExpect(jsonPath("$.data[1].id").value(older))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mockMvc.perform(
                        get(RUNS)
                                .param("status", "QUEUED")
                                .param("from", "2026-11-01T00:00:00Z")
                                .param("to", "2026-11-03T00:00:00Z")
                                .with(admin()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].triggerKind").value("CANDIDATE"))
                .andExpect(jsonPath("$.data[0].requestedBy").value(nullValue()));

        mockMvc.perform(
                        get(RUNS)
                                .param("from", "2026-11-01T00:00:00Z")
                                .param("to", "2026-11-03T00:00:00Z")
                                .param("size", "1")
                                .with(admin()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(newer))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
    }

    @Test
    void rejectsInvalidListConditions() throws Exception {
        mockMvc.perform(get(RUNS).param("status", "DONE").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RUNS).param("triggerKind", "SCHEDULED").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get(RUNS)
                                .param("from", "2026-11-03T00:00:00Z")
                                .param("to", "2026-11-01T00:00:00Z")
                                .with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RUNS).param("size", "0").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void showsRunStatusWithStoredBasis() throws Exception {
        long candidate = fixtures.insertRun("CANDIDATE", "RUNNING", null, key());
        // 후보 검사가 저장하는 기준 형식(CheckDataBasis)도 같은 응답 모양으로 읽는다.
        jdbcClient
                .sql(
                        """
                        update check_run set data_basis = cast(:basis as jsonb) where id = :id
                        """)
                .param(
                        "basis",
                        "{\"resources\":[{\"sourceResourceId\":"
                                + workerTable
                                + ",\"lastSuccessfulSyncRunId\":"
                                + lastWorkerSync
                                + "}]}")
                .param("id", candidate)
                .update();

        mockMvc.perform(get(RUN, candidate).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(candidate))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.scope").isMap())
                .andExpect(
                        jsonPath("$.data.dataBasis.resources[0].sourceResourceId")
                                .value(workerTable))
                .andExpect(
                        jsonPath("$.data.dataBasis.resources[0].lastSyncRunId")
                                .value(lastWorkerSync))
                .andExpect(jsonPath("$.data.joined").doesNotExist());

        mockMvc.perform(get(RUN, Long.MAX_VALUE).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.resource").value("check_run"));
        mockMvc.perform(get(RUN, 0).with(admin())).andExpect(status().isBadRequest());
    }

    // ---- 인증·권한 ----

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(RUNS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RUN, 1)).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post(RUNS)
                                .with(csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MANUAL_ALL))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        rule();

        mockMvc.perform(get(RUNS).with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(
                        post(RUNS)
                                .with(loggedIn(roleCode))
                                .with(csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MANUAL_ALL))
                .andExpect(status().isForbidden());
        assertThat(runCount()).isZero();
    }

    @Test
    void requiresCsrfTokenToRequest() throws Exception {
        rule();

        mockMvc.perform(
                        post(RUNS)
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MANUAL_ALL))
                .andExpect(status().isForbidden());
        assertThat(runCount()).isZero();
    }

    private long rule() {
        long ruleId =
                fixtures.insertLinkMapping(
                        fixtures.linkTypeForNewActiveRule(linkType), "ACTIVE", "{}");
        fixtures.insertCondition(
                ruleId,
                1,
                workerId,
                employeeNo,
                "[{\"op\":\"PAD_ZEROS\",\"arg\":4},{\"op\":\"ADD_PREFIX\",\"arg\":\"GP\"}]",
                "[]");
        return ruleId;
    }

    private long issue(long detectedRunId, long linkMappingId) {
        return new IssueTestFixtures(jdbcClient)
                .insertIssue(detectedRunId, linkMappingId, "UNMATCHED", "NEW", "{}");
    }

    private static String reverify(long comparedToRunId, String scope) {
        return "{\"triggerKind\":\"REVERIFY\",\"scope\":"
                + scope
                + ",\"comparedToRunId\":"
                + comparedToRunId
                + "}";
    }

    private ResultActions request(String body) throws Exception {
        return mockMvc.perform(
                post(RUNS)
                        .with(admin())
                        .with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static long id(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /** 이 테스트에서 요청 API가 만든 검사 수. 픽스처가 넣은 실행은 요청자가 아니거나 work_key가 test-로 시작한다. */
    private long runCount() {
        return jdbcClient
                .sql(
                        """
                        select count(*) from check_run
                        where requested_by = :accountId and work_key not like 'test-%'
                        """)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private RequestPostProcessor admin() {
        return loggedIn("ADMIN");
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, "check-test", roleCode, false);
        return authentication(account.toAuthentication());
    }

    /**
     * 화면처럼 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 같은 값으로 보낸다. spring-security-test의 csrf()는 공유 컨텍스트를 깨뜨리므로
     * 쓰지 않는다.
     */
    private static RequestPostProcessor csrfToken() {
        String token = UUID.randomUUID().toString();
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
}

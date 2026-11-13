package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.domain.IssueReverification;
import com.axcore.platform.check.domain.IssueReverification.Verdict;
import com.axcore.platform.check.dto.CheckRunCreateRequest;
import com.axcore.platform.check.dto.CheckRunCreateRequest.Scope;
import com.axcore.platform.check.dto.CheckRunRequestResponse;
import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.CheckRunResponse.Resource;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.service.CheckRunJob.Outcome;
import com.axcore.platform.check.service.CheckRunJob.SkippedRule;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.IssueTestFixtures;
import com.axcore.platform.issue.service.IssueVerificationRecorder;
import com.axcore.platform.source.service.CdcReloadJob;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 실행기(191-2)를 실제 PostgreSQL로 확인한다. 작업 표에서 가져가 객체로 판정하고 규칙별 결과를 저장하기까지를 {@link QueuedJobRunner}로
 * 돌린다.
 *
 * <p>기본 장면: 작업지시(WO, 출발)의 eq_code가 설비(EQ, 도착)의 code와 같으면 연결되는 MANY_TO_ONE 규칙. WO1은 E1 하나와 맞고(정상),
 * WO2는 맞는 설비가 없고(미연결), WO3은 E3이 둘이라 중복이다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "spring.main.web-application-type=servlet",
            "axcore.check.sync.enabled=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles({"api", "batch"})
@Import(TestcontainersConfiguration.class)
class CheckRunJobTests {

    // 주기 실행이 시험이 넣은 검사를 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    // 감시 작업이 시험 데이터에 다시 읽기를 넣지 않게 막는다.
    @MockitoBean CdcReloadJob cdcReloadJob;
    @MockitoSpyBean IssueVerificationRecorder reverificationRecorder;

    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;
    @Autowired CheckRunRequestService requests;
    @Autowired CheckRunRepository runs;
    @Autowired CheckResultRepository results;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    private CheckRunTestFixtures fixtures;
    private long accountId;
    private long dataSource;
    private long woType;
    private long eqType;
    private long woResource;
    private long eqResource;
    private long woSync;
    private long eqSync;
    private long woMapping;
    private long eqMapping;
    private long woEqCode;
    private long eqCode;
    private long linkType;

    @BeforeEach
    void setUp() {
        // 앞 시험이 남긴 대기·실행 중 검사를 끝낸다. runNext는 가장 먼저 들어온 검사를 가져간다.
        jdbc.sql(
                        """
                        update check_run
                        set status = 'FAILED', started_at = coalesce(started_at, now()),
                            finished_at = now(), error_message = '시험 정리'
                        where status in ('QUEUED', 'RUNNING')
                        """)
                .update();
        fixtures = new CheckRunTestFixtures(jdbc);
        accountId = fixtures.insertAdmin();
        woType = fixtures.insertObjectType("WO");
        eqType = fixtures.insertObjectType("EQ");
        woEqCode = fixtures.insertProperty(woType, "eq_code");
        fixtures.insertProperty(woType, "status");
        eqCode = fixtures.insertProperty(eqType, "code");
        dataSource = fixtures.insertDataSource();
        woResource = fixtures.insertResource(dataSource, "mes.work_order");
        eqResource = fixtures.insertResource(dataSource, "mes.equipment");
        woMapping = fixtures.insertObjectMapping(woResource, woType, true);
        eqMapping = fixtures.insertObjectMapping(eqResource, eqType, true);
        linkType = fixtures.insertLinkType(woType, eqType, "MANY_TO_ONE");
        // 수동 검사는 판정(191-3)을 거친다. FULL_COMPARE 표가 한 번은 수집되어 있어야 건너뛰지 않는다.
        woSync = fixtures.insertSync(dataSource, woResource, accountId, "SUCCEEDED", Instant.now());
        eqSync = fixtures.insertSync(dataSource, eqResource, accountId, "SUCCEEDED", Instant.now());

        fixtures.insertObject(
                woMapping, woType, "WO1", "{\"eq_code\": \"E1\", \"status\": \"OPEN\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO2", "{\"eq_code\": \"E2\", \"status\": \"OPEN\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO3", "{\"eq_code\": \"E3\", \"status\": \"DONE\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO4", "{\"eq_code\": \"E9\"}", "DELETED_AT_SOURCE");
        fixtures.insertObject(null, woType, "WO5", "{\"eq_code\": \"E8\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ1", "{\"code\": \"E1\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3a", "{\"code\": \"E3\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3b", "{\"code\": \"E3\"}", "ACTIVE");
    }

    @Test
    void savesResultPerRuleFromActiveSourceObjects() {
        long rule = rule("{}");
        long runId = queue("MANUAL", rule);

        assertThat(runner.runNext(job)).isTrue();

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        List<CheckResult> saved = resultsOf(runId);
        assertThat(saved).hasSize(1);
        CheckResult result = saved.getFirst();
        assertThat(result.getLinkMappingId()).isEqualTo(rule);
        assertThat(result.getPreviousResultId()).isNull();
        // 원천에서 지운 WO4와 플랫폼에서 만든 WO5는 판정하지 않는다.
        assertThat(
                        List.of(
                                result.getTotalCount(),
                                result.getMatchedCount(),
                                result.getUnmatchedCount(),
                                result.getDuplicateCount(),
                                result.getViolationCount()))
                .containsExactly(3, 1, 1, 1, 0);
        assertThat(result.getSampleRows())
                .extracting(
                        s -> s.get("sourceKey"),
                        s -> s.get("classification"),
                        s -> s.get("rawValue"),
                        s -> s.get("transformedValue"))
                .containsExactly(
                        tuple(Map.of("id", "WO2"), "UNMATCHED", "E2", "E2"),
                        tuple(Map.of("id", "WO3"), "DUPLICATE", "E3", "E3"));
        assertThat((List<?>) result.getSampleRows().get(1).get("targetObjectIds")).hasSize(2);
    }

    @Test
    void appliesRuleRowFilterToObjects() {
        long rule =
                rule(
                        "{\"property\": \""
                                + fixtures.typeCode(woType)
                                + ".status\", \"equals\": \"OPEN\"}");
        long runId = queue("MANUAL", rule);

        runner.runNext(job);

        CheckResult result = resultsOf(runId).getFirst();
        // OPEN인 WO1(정상)·WO2(미연결)만 본다.
        assertThat(
                        List.of(
                                result.getTotalCount(),
                                result.getMatchedCount(),
                                result.getUnmatchedCount()))
                .containsExactly(2, 1, 1);
    }

    @Test
    void ignoresObjectsFromInactiveMappings() {
        long rule = rule("{}");
        long otherResource = fixtures.insertResource(fixtures.insertDataSource(), "old.equipment");
        long inactive = fixtures.insertObjectMapping(otherResource, eqType, false);
        fixtures.insertObject(inactive, eqType, "OLD2", "{\"code\": \"E2\"}", "ACTIVE");
        long runId = queue("MANUAL", rule);

        runner.runNext(job);

        // 비활성 매핑에서 온 설비 E2는 도착으로 보지 않아 WO2는 여전히 미연결이다.
        assertThat(resultsOf(runId).getFirst().getUnmatchedCount()).isEqualTo(1);
    }

    @Test
    void activeManualLinkTurnsUnmatchedSourceIntoMatched() {
        long rule = rule("{}");
        long wo2 = objectId(woType, "eq_code", "E2");
        long eq1 = objectId(eqType, "code", "E1");
        long manual =
                jdbc.sql(
                                """
                                insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                                values (:type, :source, :target, 'MANUAL', :account) returning id
                                """)
                        .param("type", linkType)
                        .param("source", wo2)
                        .param("target", eq1)
                        .param("account", accountId)
                        .query(Long.class)
                        .single();
        long runId = queue("MANUAL", rule);

        runner.runNext(job);

        // 규칙으로는 미연결인 WO2를 관리자가 이었으므로 정상으로 본다(366). 중복인 WO3은 그대로다.
        CheckResult result = resultsOf(runId).getFirst();
        assertThat(
                        List.of(
                                result.getTotalCount(),
                                result.getMatchedCount(),
                                result.getUnmatchedCount(),
                                result.getDuplicateCount()))
                .containsExactly(3, 2, 0, 1);
        assertThat(result.getSampleRows())
                .extracting(s -> s.get("classification"))
                .containsExactly("DUPLICATE");

        // 수동 연결이 꺼지면 다시 미연결이다.
        jdbc.sql(
                        "update object_link set status = 'INACTIVE', inactivated_at = now() where id = :id")
                .param("id", manual)
                .update();
        long next = queue("MANUAL", rule);
        runner.runNext(job);
        assertThat(resultsOf(next).getFirst().getUnmatchedCount()).isEqualTo(1);
    }

    @Test
    void manualLinkPassReverificationRecordsTheManualTarget() {
        long rule = rule("{}");
        long detected =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        long wo2 = woObject("WO2");
        long issue = issueOn(new IssueTestFixtures(jdbc), detected, rule, wo2);
        // 플랫폼에서 만든 객체라 규칙의 도착 행에는 없다(원천 키 없음).
        long outside = fixtures.insertObject(null, eqType, "EQX", "{\"code\": \"ZZ\"}", "ACTIVE");
        jdbc.sql(
                        """
                        insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                        values (:type, :source, :target, 'MANUAL', :account)
                        """)
                .param("type", linkType)
                .param("source", wo2)
                .param("target", outside)
                .param("account", accountId)
                .update();
        long runId =
                fixtures.insertQueuedRun(
                        "REVERIFY",
                        "{\"kind\": \"ISSUES\", \"linkMappingIds\": [%d], \"issueIds\": [%d]}"
                                .formatted(rule, issue),
                        basis(),
                        accountId);

        assertThat(runner.runNext(job)).isTrue();

        ArgumentCaptor<List<IssueReverification>> captured = ArgumentCaptor.captor();
        verify(reverificationRecorder).record(eq(runId), captured.capture(), any());
        IssueReverification verdict = captured.getValue().getFirst();
        assertThat(verdict.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(verdict.detail())
                .containsEntry("matchedBy", "MANUAL_LINK")
                .containsEntry("manualTargetObjectIds", List.of(outside))
                .containsEntry("targetFound", true)
                .containsEntry("reason", CheckRunJob.MANUAL_LINK);
    }

    /** 문제별 판정을 넘길 때 검사가 실제로 읽은(실행 시점의) 데이터 기준이 이미 저장돼 있다. 되돌리기 판단이 그 기준을 읽는다(376). */
    @Test
    void reverificationStoresTheDataBasisItReadBeforeRecordingVerdicts() {
        long rule = rule("{}");
        long detected =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        long issue = issueOn(new IssueTestFixtures(jdbc), detected, rule, woObject("WO1"));
        long runId =
                fixtures.insertQueuedRun(
                        "REVERIFY",
                        "{\"kind\": \"ISSUES\", \"linkMappingIds\": [%d], \"issueIds\": [%d]}"
                                .formatted(rule, issue),
                        basis(),
                        accountId);
        // 접수 뒤에 작업지시 표를 한 번 더 읽었다. 재검증은 실행할 때 이 수집을 기준으로 쓴다.
        long newerSync =
                fixtures.insertSync(dataSource, woResource, accountId, "SUCCEEDED", Instant.now());
        List<String> basisAtRecord = new ArrayList<>();
        IssueVerificationRecorder spy =
                AopTestUtils.getUltimateTargetObject(reverificationRecorder);
        doAnswer(
                        invocation -> {
                            basisAtRecord.add(
                                    jdbc.sql(
                                                    "select data_basis::text from check_run where id = :id")
                                            .param("id", runId)
                                            .query(String.class)
                                            .single());
                            return invocation.callRealMethod();
                        })
                .when(spy)
                .record(eq(runId), any(), any());

        assertThat(runner.runNext(job)).isTrue();

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(basisAtRecord).hasSize(1);
        assertThat(basisAtRecord.getFirst())
                .contains("\"lastSuccessfulSyncRunId\": " + newerSync)
                .doesNotContain("\"lastSuccessfulSyncRunId\": " + woSync + "}");
    }

    @Test
    void linksPreviousResultOfSameRuleRevision() {
        long rule = rule("{}");
        long first = queue("MANUAL", rule);
        runner.runNext(job);
        long second = queue("REVERIFY", rule);

        runner.runNext(job);

        assertThat(runStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(resultsOf(second).getFirst().getPreviousResultId())
                .isEqualTo(resultsOf(first).getFirst().getId());
    }

    /**
     * 범위가 ISSUES인 재검증은 문제마다 판정을 만들어 확장 지점(135)에 넘긴다(246). 문제는 옛 개정본에서 나왔어도 같은 코드의 반영 중인 개정본으로 판정한다.
     */
    @Test
    void reverificationJudgesEachIssueWithTheCurrentRevisionOfItsRule() {
        long first = rule("{}");
        long detected =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        IssueTestFixtures issueFixtures = new IssueTestFixtures(jdbc);
        long matched = issueOn(issueFixtures, detected, first, woObject("WO1"));
        long unmatched = issueOn(issueFixtures, detected, first, woObject("WO2"));
        long deleted = issueOn(issueFixtures, detected, first, woObject("WO4"));
        long withoutObject = issueFixtures.insertIssue(detected, first, "UNMATCHED", "NEW", "{}");
        long second = revise(first);
        long runId =
                fixtures.insertQueuedRun(
                        "REVERIFY",
                        "{\"kind\": \"ISSUES\", \"linkMappingIds\": [%d], \"issueIds\": [%d, %d, %d, %d]}"
                                .formatted(second, matched, unmatched, deleted, withoutObject),
                        basis(),
                        accountId);

        assertThat(runner.runNext(job)).isTrue();

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        ArgumentCaptor<List<IssueReverification>> captured = ArgumentCaptor.captor();
        verify(reverificationRecorder).record(eq(runId), captured.capture(), any());
        assertThat(captured.getValue())
                .extracting(
                        IssueReverification::issueId,
                        IssueReverification::linkMappingId,
                        IssueReverification::verdict)
                .containsExactly(
                        tuple(matched, second, Verdict.PASSED),
                        tuple(unmatched, second, Verdict.FAILED),
                        tuple(deleted, second, Verdict.NOT_EVALUATED),
                        tuple(withoutObject, second, Verdict.NOT_EVALUATED));
        assertThat(captured.getValue().get(1).detail())
                .containsEntry("rawValue", "E2")
                .containsEntry("transformedValue", "E2")
                .containsEntry("targetFound", false)
                .containsEntry("classification", "UNMATCHED");
        assertThat(captured.getValue().get(2).reason()).contains("판정 대상에 없습니다");
    }

    @Test
    void joinsQueuedReverificationAndUsesTheLatestCollectedRows() {
        long previousRule = rule("{}");
        long detected = queue("MANUAL", previousRule);
        assertThat(runner.runNext(job)).isTrue();
        CheckResult original = resultsOf(detected).getFirst();
        assertThat(original.getMatchedCount()).isEqualTo(1);
        assertThat(original.getUnmatchedCount()).isEqualTo(1);
        long issue = issueOn(new IssueTestFixtures(jdbc), detected, previousRule, woObject("WO2"));
        long activeRule = revise(previousRule);
        CheckRunCreateRequest request =
                new CheckRunCreateRequest(
                        "REVERIFY", new Scope("ISSUES", null, List.of(issue)), detected);
        LoginAccount admin = new LoginAccount(accountId, "admin", "ADMIN", false);

        CheckRunResponse queued = requests.request(request, admin).run();
        assertThat(queued.dataBasis().resources()).contains(new Resource(eqResource, eqSync));

        long updatedSync =
                new TransactionTemplate(transactionManager)
                        .execute(
                                tx -> {
                                    fixtures.insertObject(
                                            eqMapping,
                                            eqType,
                                            "EQ2",
                                            "{\"code\": \"E2\"}",
                                            "ACTIVE");
                                    return fixtures.insertSync(
                                            dataSource,
                                            eqResource,
                                            accountId,
                                            "SUCCEEDED",
                                            Instant.now());
                                });

        CheckRunRequestResponse joined = requests.request(request, admin);
        assertThat(joined.joined()).isTrue();
        assertThat(joined.run().id()).isEqualTo(queued.id());
        assertThat(runner.runNext(job)).isTrue();
        assertThat(runStatus(queued.id())).isEqualTo("SUCCEEDED");
        assertThat(resultsOf(queued.id()))
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.getLinkMappingId()).isEqualTo(activeRule);
                            assertThat(result.getTotalCount()).isEqualTo(3);
                            assertThat(result.getMatchedCount()).isEqualTo(2);
                            assertThat(result.getUnmatchedCount()).isZero();
                            assertThat(result.getDuplicateCount()).isEqualTo(1);
                        });
        ArgumentCaptor<List<IssueReverification>> captured = ArgumentCaptor.captor();
        verify(reverificationRecorder).record(eq(queued.id()), captured.capture(), any());
        assertThat(captured.getValue())
                .extracting(IssueReverification::issueId, IssueReverification::verdict)
                .containsExactly(tuple(issue, Verdict.PASSED));
        // 끝난 재검증은 다시 쓰지 않고 지금 기준으로 새로 돈다(389).
        CheckRunRequestResponse again = requests.request(request, admin);
        assertThat(again.reused()).isFalse();
        assertThat(again.run().id()).isNotEqualTo(queued.id());
        assertThat(again.run().dataBasis().resources())
                .contains(new Resource(eqResource, updatedSync));
        assertThat(runner.runNext(job)).isTrue();
    }

    @Test
    void requestsReverificationAndResolvesUsingTheRecordedPass() throws Exception {
        long previousRule = rule("{}");
        long baseline = queue("MANUAL", previousRule);
        assertThat(runner.runNext(job)).isTrue();
        IssueTestFixtures issueFixtures = new IssueTestFixtures(jdbc);
        long issue = issueOn(issueFixtures, baseline, previousRule, woObject("WO2"));
        issueFixtures.markFixedAt(issue, accountId, Instant.EPOCH);
        long activeRule = revise(previousRule);

        String body =
                """
                {"triggerKind":"REVERIFY","scope":{"kind":"ISSUES","issueIds":[%d]},"comparedToRunId":%d}
                """
                        .formatted(issue, baseline);
        String accepted =
                mvc.perform(
                                post("/api/v1/checks/runs")
                                        .with(admin())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long run = json.readTree(accepted).path("data").path("id").asLong();

        fixtures.insertObject(eqMapping, eqType, "EQ2", "{\"code\": \"E2\"}", "ACTIVE");
        fixtures.insertSync(dataSource, eqResource, accountId, "SUCCEEDED", Instant.now());
        assertThat(runner.runNext(job)).isTrue();

        mvc.perform(get("/api/v1/checks/runs/{id}", run).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.comparedToRunId").value(baseline));
        mvc.perform(get("/api/v1/issues/{id}/verifications", issue).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].checkRunId").value(run))
                .andExpect(jsonPath("$.data[0].outcome").value("PASSED"))
                .andExpect(jsonPath("$.data[0].detail.linkMappingId").value(activeRule));
        mvc.perform(get("/api/v1/checks/runs/{id}/comparison", run).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparedToRunId").value(baseline))
                .andExpect(jsonPath("$.data.coverage").value("PARTIAL"))
                .andExpect(
                        jsonPath("$.data.rows[?(@.category == 'RESOLVED')].objectInstanceId")
                                .value(org.hamcrest.Matchers.contains((int) woObject("WO2"))))
                .andExpect(
                        jsonPath("$.data.rows[?(@.category == 'REMAINING')].objectInstanceId")
                                .value(org.hamcrest.Matchers.contains((int) woObject("WO3"))));

        mvc.perform(
                        patch("/api/v1/issues/{id}/status", issue)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status":"RESOLVED","reason":"재검증 통과","resolvedCheckRunId":%d,"rowVersion":1}
                                """
                                                .formatted(run)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedCheckRunId").value(run));
    }

    /**
     * 389: 재검증 통과 뒤 확인중 → 조치완료로 되돌리고 데이터가 그대로인 채 다시 요청해도, 예전 성공 결과를 돌려주지 않고 새 재검증을 돈다. 예전 실행은 마지막
     * 조치완료보다 먼저 시작해 해결 근거가 안 되고, 새 실행으로는 해결된다.
     */
    @Test
    void reverificationRequestedAgainAfterMarkingFixedRunsAnewAndResolves() throws Exception {
        long rule = rule("{}");
        long baseline = queue("MANUAL", rule);
        assertThat(runner.runNext(job)).isTrue();
        IssueTestFixtures issueFixtures = new IssueTestFixtures(jdbc);
        long issue = issueOn(issueFixtures, baseline, rule, woObject("WO2"));
        issueFixtures.markFixedAt(issue, accountId, Instant.EPOCH);
        fixtures.insertObject(eqMapping, eqType, "EQ2", "{\"code\": \"E2\"}", "ACTIVE");
        fixtures.insertSync(dataSource, eqResource, accountId, "SUCCEEDED", Instant.now());
        Scope scope = new Scope("ISSUES", null, List.of(issue));
        long first = requestReverification(scope, baseline);
        assertThat(runner.runNext(job)).isTrue();
        assertThat(runStatus(first)).isEqualTo("SUCCEEDED");

        // 확인중으로 되돌렸다가 다시 조치완료. 원천 데이터·연결은 그대로라 work_key가 같다.
        issueFixtures.markFixedAt(issue, accountId, Instant.now());
        var again =
                requests.request(
                        new CheckRunCreateRequest("REVERIFY", scope, baseline),
                        new LoginAccount(accountId, "admin", "ADMIN", false));
        assertThat(again.reused()).isFalse();
        assertThat(again.joined()).isFalse();
        assertThat(again.run().id()).isNotEqualTo(first);
        assertThat(runner.runNext(job)).isTrue();

        String resolve =
                """
                {"status":"RESOLVED","reason":"재검증 통과","resolvedCheckRunId":%d,"rowVersion":2}
                """;
        mvc.perform(
                        patch("/api/v1/issues/{id}/status", issue)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(resolve.formatted(first)))
                .andExpect(jsonPath("$.error.code").value("REVERIFICATION_REQUIRED"));
        mvc.perform(
                        patch("/api/v1/issues/{id}/status", issue)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(resolve.formatted(again.run().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void classifiesNewOnlyWhenTheSelectedRunRecordedAPass() throws Exception {
        long rule = rule("{}");
        long initial = queue("MANUAL", rule);
        assertThat(runner.runNext(job)).isTrue();
        IssueTestFixtures issueFixtures = new IssueTestFixtures(jdbc);
        long objectId = woObject("WO1");
        long issueId = issueOn(issueFixtures, initial, rule, objectId);
        issueFixtures.markFixedAt(issueId, accountId, Instant.EPOCH);
        Scope scope = new Scope("ISSUES", null, List.of(issueId));
        long previous = requestReverification(scope, initial);
        assertThat(runner.runNext(job)).isTrue();

        jdbc.sql("update object_instance set properties = '{\"eq_code\": \"E9\"}' where id = :id")
                .param("id", objectId)
                .update();
        fixtures.insertSync(dataSource, woResource, accountId, "SUCCEEDED", Instant.now());
        long current = requestReverification(scope, previous);

        String path = "/api/v1/checks/runs/{id}/comparison";
        mvc.perform(get(path, current).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage").value("UNAVAILABLE"));
        assertThat(runner.runNext(job)).isTrue();
        mvc.perform(get(path, current)).andExpect(status().isUnauthorized());
        mvc.perform(get(path, current).with(accountWithRole("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path, current).with(admin()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path, Long.MAX_VALUE).with(admin())).andExpect(status().isNotFound());
        mvc.perform(get(path, initial).with(admin()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        mvc.perform(get(path, current).with(admin()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparedToRunId").value(previous))
                .andExpect(jsonPath("$.data.hasBaselinePassEvidence").value(true))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.data.rows[0].category").value("NEW"))
                .andExpect(jsonPath("$.data.rows[0].objectInstanceId").value(objectId))
                .andExpect(jsonPath("$.data.rows[0].before.classification").value("MATCHED"))
                .andExpect(jsonPath("$.data.rows[0].after.rawValue").value("E9"));
        mvc.perform(get(path, current).with(admin()).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].category").value("REMAINING"));

        long comparedToSampleOnly = requestReverification(scope, initial);
        assertThat(runner.runNext(job)).isTrue();
        mvc.perform(get(path, comparedToSampleOnly).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage").value("PARTIAL"))
                .andExpect(jsonPath("$.data.hasBaselinePassEvidence").value(false))
                .andExpect(jsonPath("$.data.rows[?(@.category == 'NEW')]").isEmpty());
    }

    @Test
    void doesNotReuseAReverificationFromBeforeHistoryWasRecorded() {
        long rule = rule("{}");
        long baseline =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        long issue = issueOn(new IssueTestFixtures(jdbc), baseline, rule, woObject("WO1"));
        var request =
                new CheckRunCreateRequest(
                        "REVERIFY", new Scope("ISSUES", null, List.of(issue)), baseline);
        var account = new LoginAccount(accountId, "admin", "ADMIN", false);
        long legacy = requests.request(request, account).run().id();
        String scopeJson =
                "{\"kind\":\"ISSUES\",\"linkMappingIds\":[%d],\"issueIds\":[%d]}"
                        .formatted(rule, issue);
        String basisJson =
                json.writeValueAsString(
                        new CheckDataBasis(runs.loadCheckpoints(List.of(woResource, eqResource))));
        String fingerprint = CheckRunRequestService.sha256(scopeJson + "|" + basisJson);
        String legacyKey = "REVERIFY:%d:%s".formatted(baseline, fingerprint);
        jdbc.sql(
                        """
                        update check_run set status = 'SUCCEEDED', started_at = now(),
                            finished_at = now(), work_key = :key where id = :id
                        """)
                .param("key", legacyKey)
                .param("id", legacy)
                .update();

        CheckRunRequestResponse accepted = requests.request(request, account);
        assertThat(accepted.reused()).isFalse();
        assertThat(accepted.run().id()).isNotEqualTo(legacy);
        assertThat(runner.runNext(job)).isTrue();
        assertThat(verificationCount(legacy)).isZero();
        assertThat(verificationCount(accepted.run().id())).isEqualTo(1);
        CheckRunRequestResponse repeated = requests.request(request, account);
        assertThat(repeated.reused()).isFalse();
        assertThat(repeated.run().id()).isNotEqualTo(accepted.run().id());
    }

    private long requestReverification(Scope scope, long baseline) {
        var request = new CheckRunCreateRequest("REVERIFY", scope, baseline);
        var account = new LoginAccount(accountId, "admin", "ADMIN", false);
        return requests.request(request, account).run().id();
    }

    private RequestPostProcessor admin() {
        return accountWithRole("ADMIN");
    }

    private RequestPostProcessor accountWithRole(String role) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginAccount(accountId, "admin", role, false),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void acceptsNewRunWhenWaitedRequestFindsTheRunFinished() throws Exception {
        long rule = rule("{}");
        long detected =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        CheckRunCreateRequest request =
                new CheckRunCreateRequest(
                        "REVERIFY", new Scope("LINK_MAPPINGS", List.of(rule), null), detected);
        LoginAccount admin = new LoginAccount(accountId, "admin", "ADMIN", false);
        long runId = requests.request(request, admin).run().id();
        markRunning(runId);

        CompletableFuture<CheckRunRequestResponse> waiting =
                new TransactionTemplate(transactionManager)
                        .execute(
                                tx -> {
                                    runs.lockReverificationScope(
                                            runs.findToExecute(runId)
                                                    .orElseThrow()
                                                    .reverificationScope());
                                    var response =
                                            CompletableFuture.supplyAsync(
                                                    () -> requests.request(request, admin));
                                    await().pollInSameThread()
                                            .atMost(Duration.ofSeconds(10))
                                            .until(
                                                    () ->
                                                            jdbc.sql(
                                                                            """
                                                    select exists (
                                                        select 1 from pg_locks
                                                        where locktype = 'advisory' and not granted
                                                          and pg_backend_pid() = any(pg_blocking_pids(pid))
                                                    )
                                                    """)
                                                                    .query(Boolean.class)
                                                                    .single());
                                    Outcome outcome =
                                            CompletableFuture.supplyAsync(
                                                            () -> {
                                                                fixtures.insertSync(
                                                                        dataSource,
                                                                        eqResource,
                                                                        accountId,
                                                                        "SUCCEEDED",
                                                                        Instant.now());
                                                                return job.run(runId);
                                                            })
                                                    .orTimeout(10, TimeUnit.SECONDS)
                                                    .join();
                                    job.complete(runId, outcome);
                                    return response;
                                });

        // 기다리던 사이 같은 범위의 재검증이 끝났으면 그 결과를 다시 쓰지 않고 새로 접수한다(389).
        CheckRunRequestResponse accepted = waiting.get(10, TimeUnit.SECONDS);
        assertThat(accepted.reused()).isFalse();
        assertThat(accepted.joined()).isFalse();
        assertThat(accepted.run().id()).isNotEqualTo(runId);
        assertThat(accepted.run().status()).isEqualTo("QUEUED");
        assertThat(runner.runNext(job)).isTrue();
    }

    @Test
    void rollsBackRuleResultsWhenReverificationRecorderFails() {
        long rule = rule("{}");
        long detected =
                fixtures.insertRun(
                        "MANUAL", "SUCCEEDED", accountId, "detected-" + UUID.randomUUID());
        IssueTestFixtures issueFixtures = new IssueTestFixtures(jdbc);
        long issue = issueOn(issueFixtures, detected, rule, woObject("WO2"));
        issueFixtures.markFixedAt(issue, accountId, Instant.EPOCH);
        CheckRunCreateRequest request =
                new CheckRunCreateRequest(
                        "REVERIFY", new Scope("ISSUES", null, List.of(issue)), detected);
        LoginAccount admin = new LoginAccount(accountId, "admin", "ADMIN", false);
        CheckRunResponse queued = requests.request(request, admin).run();
        IssueVerificationRecorder spy =
                AopTestUtils.getUltimateTargetObject(reverificationRecorder);
        doAnswer(
                        invocation -> {
                            results.flush();
                            assertThat(resultsOf(queued.id())).hasSize(1);
                            invocation.callRealMethod();
                            assertThat(verificationCount(queued.id())).isEqualTo(1);
                            assertThat(issueStatus(issue)).isEqualTo("CHECKING");
                            throw new IllegalStateException("시험용 재검증 기록 실패");
                        })
                .when(spy)
                .record(eq(queued.id()), any(), any());

        assertThat(runner.runNext(job)).isTrue();

        verify(reverificationRecorder).record(eq(queued.id()), any(), any());
        assertThat(runStatus(queued.id())).isEqualTo("FAILED");
        assertThat(resultsOf(queued.id())).isEmpty();
        assertThat(verificationCount(queued.id())).isZero();
        assertThat(issueStatus(issue)).isEqualTo("FIXED_PENDING_REVERIFY");
        CheckRunResponse retry = requests.request(request, admin).run();
        assertThat(retry.id()).isNotEqualTo(queued.id());
        assertThat(retry.status()).isEqualTo("QUEUED");
        assertThat(retry.dataBasis()).isEqualTo(queued.dataBasis());
    }

    @Test
    void reverificationOfRulesOnlyDoesNotCallTheRecorder() {
        long rule = rule("{}");
        long runId = queue("REVERIFY", rule);

        runner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        verifyNoInteractions(reverificationRecorder);
    }

    @Test
    void skipsRulesThatCannotBeCheckedAndChecksTheRest() {
        long good = rule("{}");
        long retired = rule("RETIRED", "{}", "[]");
        long badFilter = rule("{\"property\": \"OTHER.status\", \"equals\": \"OPEN\"}");
        long badTransform = rule("ACTIVE", "{}", "[{\"op\": \"NO_SUCH_OP\"}]");
        long runId = queue("MANUAL", good, retired, badFilter, badTransform);
        markRunning(runId);

        Outcome outcome = job.run(runId);

        assertThat(outcome.evaluated()).hasSize(1);
        assertThat(outcome.skipped())
                .extracting(SkippedRule::linkMappingId)
                .containsExactlyInAnyOrder(retired, badFilter, badTransform);
        assertThat(outcome.skipped())
                .filteredOn(s -> s.linkMappingId() == retired)
                .extracting(SkippedRule::reason)
                .containsExactly(CheckRunJob.NOT_ACTIVE);
        assertThat(job.auditResult(outcome))
                .containsEntry("checkedRuleCount", 1)
                .extractingByKey("skippedRules")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(3);
    }

    @Test
    void failsWhenNoRuleCanBeChecked() {
        long retired = rule("RETIRED", "{}", "[]");
        long runId = queue("MANUAL", retired);

        runner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("FAILED");
        assertThat(errorMessage(runId)).startsWith("검사할 수 있는 규칙이 없습니다.");
        assertThat(resultsOf(runId)).isEmpty();
    }

    @Test
    void leavesCandidateTrialsQueued() {
        long candidate = fixtures.insertQueuedRun("CANDIDATE", "{}", "{}", null);

        assertThat(runner.runNext(job)).isFalse();

        assertThat(runStatus(candidate)).isEqualTo("QUEUED");
    }

    /** 활성 규칙: WO.eq_code = EQ.code, 변환 없음. */
    private long rule(String sourceFilter) {
        return rule("ACTIVE", sourceFilter, "[]");
    }

    /** 규칙 개정본의 비교 조건은 개정본을 만든 트랜잭션에서만 넣을 수 있다(트리거). */
    private long rule(String status, String sourceFilter, String sourceTransforms) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        tx -> {
                            long rule =
                                    fixtures.insertLinkMapping(
                                            "ACTIVE".equals(status)
                                                    ? fixtures.linkTypeForNewActiveRule(linkType)
                                                    : linkType,
                                            status,
                                            sourceFilter);
                            fixtures.insertCondition(
                                    rule, 1, woEqCode, eqCode, sourceTransforms, "[]");
                            return rule;
                        });
    }

    private long queue(String triggerKind, long... ruleIds) {
        String ids =
                java.util.Arrays.stream(ruleIds)
                        .mapToObj(String::valueOf)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
        String scope = "{\"kind\": \"LINK_MAPPINGS\", \"linkMappingIds\": [" + ids + "]}";
        return fixtures.insertQueuedRun(triggerKind, scope, basis(), accountId);
    }

    private String basis() {
        return """
                {"resources": [
                    {"sourceResourceId": %d, "lastSuccessfulSyncRunId": %d},
                    {"sourceResourceId": %d, "lastSuccessfulSyncRunId": %d}
                ]}
                """
                .formatted(woResource, woSync, eqResource, eqSync);
    }

    /** 같은 코드로 다음 개정본을 만들고 앞 개정본을 폐기한다. 비교 조건은 개정본을 만든 트랜잭션에서 넣는다. */
    private long revise(long previous) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        tx -> {
                            jdbc.sql(
                                            "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                                    .param("id", previous)
                                    .update();
                            long next =
                                    jdbc.sql(
                                                    """
                                                    insert into link_mapping (link_type_id, code, revision, status)
                                                    select link_type_id, code, revision + 1, 'ACTIVE'
                                                    from link_mapping where id = :id
                                                    returning id
                                                    """)
                                            .param("id", previous)
                                            .query(Long.class)
                                            .single();
                            fixtures.insertCondition(next, 1, woEqCode, eqCode, "[]", "[]");
                            return next;
                        });
    }

    private long woObject(String key) {
        return jdbc.sql(
                        "select id from object_instance where object_type_id = :type and source_key->>'id' = :key")
                .param("type", woType)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long issueOn(IssueTestFixtures issueFixtures, long detected, long rule, long objectId) {
        long issue =
                issueFixtures.insertIssue(
                        detected, rule, "UNMATCHED", "FIXED_PENDING_REVERIFY", "{}");
        jdbc.sql("update issue set object_instance_id = :object where id = :id")
                .param("object", objectId)
                .param("id", issue)
                .update();
        return issue;
    }

    private void markRunning(long runId) {
        jdbc.sql("update check_run set status = 'RUNNING', started_at = now() where id = :id")
                .param("id", runId)
                .update();
    }

    private List<CheckResult> resultsOf(long runId) {
        return results.findAll().stream().filter(r -> r.getCheckRunId() == runId).toList();
    }

    private String runStatus(long runId) {
        return jdbc.sql("select status from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private long verificationCount(long runId) {
        return jdbc.sql("select count(*) from issue_verification where check_run_id = :id")
                .param("id", runId)
                .query(Long.class)
                .single();
    }

    private String issueStatus(long issueId) {
        return jdbc.sql("select status from issue where id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
    }

    private String errorMessage(long runId) {
        return jdbc.sql("select error_message from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private long objectId(long type, String property, String value) {
        return jdbc.sql(
                        """
                        select id from object_instance
                        where object_type_id = :type and properties->>:property = :value
                        """)
                .param("type", type)
                .param("property", property)
                .param("value", value)
                .query(Long.class)
                .single();
    }
}

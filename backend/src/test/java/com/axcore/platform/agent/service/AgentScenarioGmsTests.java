package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가상 프레스 데이터로 Agent가 시나리오 질문에 필요한 객체를 찾아 조회하는지 실제 GMS로 본다(342). AXCORE_AGENT_LLM=openai와 GMS 키를 넣은
 * PC에서만 돈다. 조건을 클래스에 걸어, 스위치가 없으면 Spring 컨텍스트도 시험 데이터 적재도 하지 않는다. 그래서 CI(./gradlew build)에서는 돌지
 * 않는다.
 *
 * <p>질문은 답변 처리기(AgentAnswerProcessor)의 새 질문 경로와 같게 처리한다. 해석 AI가 대상을 뽑고, 서버가 대상을 객체로 정하고, 정하지 못하면
 * 되묻는다. 정했으면 그 객체를 허용하고 조회 AI가 도구를 부른다. Action 도구는 빼서 계획이 만들어지지 않게 한다.
 *
 * <p>판정은 도구 호출 기록으로 한다. 시나리오마다 꼭 조회해야 하는 객체(원천 키로 찾은 ID)를 정해 두고, 그 객체를 정해진 도구로 조회했는지 본다. AI 답은 실행마다
 * 달라질 수 있어 답변 문장과 정답(ai/data/gold-v1.0/scenario_answers.json)은 로그로만 비교한다. 실행 방법은
 * docs/agent-scenario-gms.md에 있다.
 *
 * <p>시험 데이터(agent-scenario/press-v1.0.json.gz)는 정답 온톨로지의 유형 코드(WorkOrder 등)를 쓴다. 운영 코드로 바뀐 설정 기본값
 * 대신 이 클래스에서만 정답 코드로 덮어쓴다.
 */
@Slf4j
@SpringBootTest(
        properties = {
            "test.context=agent-scenario-gms",
            "axcore.reassignment.equipment.type=Equipment",
            "axcore.reassignment.work-order.type=WorkOrder",
            "axcore.reassignment.operation.type=WorkOrderOperation",
            "axcore.reassignment.routing.type=Routing",
            "axcore.agent.completion-estimate.work-order.type=WorkOrder",
            "axcore.agent.completion-estimate.operation.type=WorkOrderOperation",
            "axcore.agent.completion-estimate.routing.type=Routing",
            "axcore.agent.completion-estimate.equipment.type=Equipment"
        })
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentScenarioGmsTests {
    /**
     * 서버가 "P103"의 대상을 되물을 때 사용자가 후보 중 설비를 고른 답이다. 349 뒤로는 첫 질문에서 설비로 정해져 대상을 되묻지 않지만, 대상 찾기가 바뀌면 다시
     * 쓴다.
     */
    private static final String PRESS_REPLY = "1라인 3호 프레스(설비)요";

    private static final Set<String> READ_TOOLS =
            Set.of(AgentObjectDetailTools.OBJECT_DETAIL, AgentObjectDetailTools.OBJECT_LINKS);

    @Autowired QuestionInterpreter interpreter;
    @Autowired AgentTargetResolver targetResolver;
    @Autowired AgentResponder responder;
    @Autowired DataAsOfResolver dataAsOfResolver;
    @Autowired ObjectSearchQuery objectSearch;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transaction;
    @Autowired JsonMapper json;
    @MockitoBean AgentActionTools actionTools;

    private final AtomicLong questionIds = new AtomicLong(100);
    private AgentScenarioDataset.Loaded data;
    private LoginAccount admin;

    @BeforeAll
    void loadDataset() {
        long started = System.nanoTime();
        data = new AgentScenarioDataset(jdbc, jdbcTemplate, transaction, json).load();
        admin = new LoginAccount(data.adminAccountId(), "scenario-loader", "ADMIN", false);
        log.info(
                "시나리오 시험 데이터 적재: 객체 {}개, 연결 {}개, {}ms",
                data.objects(),
                data.links(),
                (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * S3: P103의 비가동 이력(관계)과 ERP 장부가를 함께 묻는다. 정답은 비가동 10건, 최근 DT-0460이다. "최근"이라 서버가 기간도 되물으므로, 답에
     * 비가동 10건이 모두 들어가는 기간을 함께 말한다. 장부가가 있는 고정자산(FA-0006)은 FINANCE 그룹이다. 384(V21)부터 ADMIN도 허용된
     * 그룹(PRODUCTION·SALES_SUPPLY)만 보므로 시험 사용자는 고정자산을 볼 수 없다. 장부가 값(8억 3천만 원)을 지어내지 않아야 한다.
     */
    @Test
    void s3FindsDowntimeHistoryAndDoesNotMakeUpHiddenBookValue() {
        Run run =
                ask("P103의 최근 비가동 이력과 ERP 장부가 알려줘", "1라인 3호 프레스(설비)요. 기간은 2026년 1월 1일부터 10월 30일까지");

        run.expectRead(press(), Set.of(AgentObjectDetailTools.OBJECT_LINKS));
        run.expectReached(data.object("Downtime", Map.of("downtime_id", "DT-0460")));
        run.expectAnswerWithout("830,000,000", "830000000", "8억 3천", "8억3천", "8.3억");
        run.log("비가동 10건, 최근 DT-0460(2026-10-30 03:10 시작), 장부가는 볼 수 없음(FINANCE, 384)");
        run.verify();
    }

    /** P2: P103 센서 측정을 관계로 찾고, 상한(4.50)을 처음 넘은 진동 측정(2026-10-28 09:00, 4.90)을 상세로 읽는다. */
    @Test
    void p2FindsWhenVibrationOfP103FirstExceededLimit() {
        Run run = ask("P103 진동이 언제부터 상한을 넘었어?", PRESS_REPLY);

        run.expectRead(press(), Set.of(AgentObjectDetailTools.OBJECT_LINKS));
        run.expectRead(
                data.object(
                        "SensorReading",
                        Map.of(
                                "equipment_code", "P103",
                                "measured_at", "2026-10-28T09:00:00",
                                "metric", "vibration_mm_s")),
                Set.of(AgentObjectDetailTools.OBJECT_DETAIL));
        run.log("2026-10-28 09:00:00부터 (4.90 > 상한 4.50)");
        run.verify();
    }

    /**
     * P3: 로트 LT261025-2의 검사 행은 없다. 로트는 객체가 아니라 출하(DL2610-0054)의 속성이라 검색으로 찾을 수 없다. 서버가 대상을 찾지 못했다고
     * 되묻거나, 조회했다면 검사 결과를 지어내지 않고 데이터 없음으로 답해야 한다. 검사 객체를 상세 조회했으면 틀린 것이다.
     */
    @Test
    void p3SaysNoInspectionDataForLot() {
        Run run = ask("LT261025-2 로트 검사 결과는?", null);

        run.log("최종 검사 기록 없음 — 연결된 검사 행 없음('데이터 없음'으로 안내)");
        if (run.clarified()) {
            assertThat(run.issues())
                    .as("찾지 못한 대상으로 되물어야 한다")
                    .anySatisfy(issue -> assertThat(issue.target()).contains("LT261025-2"));
            return;
        }
        List<Long> inspections =
                jdbc.sql(
                                """
                                select oi.id from object_instance oi
                                join object_type ot on ot.id = oi.object_type_id
                                where ot.code = 'Inspection'
                                """)
                        .query(Long.class)
                        .list();
        assertThat(run.detailed())
                .as("검사 객체를 상세 조회하지 않아야 한다(이 로트의 검사 행이 없다)")
                .doesNotContainAnyElementsOf(inspections);
    }

    /**
     * S4: P103 고장으로 멈춘 작업지시 JB2610-0141과 그 수주 OD-2610-021을 관계로 따라가 찾는다. 정답의 납기 위험 수주 OD-2610-019는
     * 재배정 뒤 밀리는 작업지시를 따져야 나오므로 답변 비교에만 쓴다.
     */
    @Test
    void s4FollowsP103ToWorkOrderAndSalesOrderAtRisk() {
        Run run = ask("P103이 멈춰서 납기가 위험한 수주는?", PRESS_REPLY);

        // 관계 조회로 따라가도, 객체 집합(382)으로 건너도 된다.
        run.expectStartedFrom(press());
        // 객체 집합으로 건너면 중간 작업지시는 목록으로 보지 않아도 된다. 작업지시 유형을 거쳐 정답 수주에 닿았으면 된다.
        run.expectReadOrPassedThrough(
                data.object("WorkOrder", Map.of("wo_no", "JB2610-0141")), typeId("WorkOrder"));
        run.expectReached(data.object("SalesOrder", Map.of("so_no", "OD-2610-021")));
        run.log(
                "멈춘 작업지시 JB2610-0141(성형·트리밍 잔량 3,200) → 수주 OD-2610-021(납기 2026-11-04). "
                        + "납기 위험 수주는 OD-2610-019(연결 작업지시 JB2610-0145 대기, 납기 2026-10-31)");
        run.verify();
    }

    /**
     * M1(382): 작업지시에는 설비 코드가 없어 설비 ← 작업지시 공정 → 작업지시 두 단계를 건너야 한다. P103에 공정이 배정된 작업지시 61건 중 진행 중은
     * JB2610-0141 하나다. 360 GMS에서 공정 122건을 하나씩 상세 조회하다 도구 호출 한도(40회)를 넘겼다. 한도 안에서 답하고 그 작업지시를 조회 결과로
     * 받아야 한다.
     */
    @Test
    void m1FindsWorkOrdersInProgressAssignedToP103() {
        Run run = ask("P103에 배정된 진행 중인 작업지시 보여줘", PRESS_REPLY);

        run.expectStartedFrom(press());
        // 건넌 결과가 1건이면 목록 없이 결과의 이름으로 답할 수 있다. 그때는 작업지시 유형으로 건너고 답에 그 번호를 말했으면 된다.
        run.expectReadOrAnswered(
                data.object("WorkOrder", Map.of("wo_no", "JB2610-0141")),
                typeId("WorkOrder"),
                "JB2610-0141");
        run.log("진행 중 작업지시 1건: JB2610-0141 (P103에 공정이 배정된 작업지시 61건 중)");
        run.verify();
    }

    /**
     * P1(148): 작업지시 JB2610-0141의 남은 성형·트리밍을 완료 시각 계산 도구로 구한다. 정답은 3,200개 × 11.5초 = 10.2시간, 준비 70분
     * 포함 11.4시간. P101이 설비와 센서 측정에 함께 걸려 되물으면 설비라고 답한다.
     */
    @Test
    void p1EstimatesHoursToFinishWorkOrderOnP101() {
        Run run = ask("JB2610-0141 남은 수량을 P101에서 찍으면 몇 시간?", "1라인 1호 프레스(설비)요");

        run.expectRead(
                data.object("WorkOrder", Map.of("wo_no", "JB2610-0141")),
                Set.of(AgentCompletionEstimateTools.ESTIMATE_COMPLETION));
        // 설비를 넘기지 않으면 검사 공정까지 더해 16.3시간이 나온다(148 첫 GMS 실행).
        run.expectInput(
                AgentCompletionEstimateTools.ESTIMATE_COMPLETION,
                "equipmentId",
                data.object("Equipment", Map.of("code", "P101")));
        run.log("3,200개 × 11.5초 = 10.2시간 (준비 70분 포함 11.4시간)");
        run.verify();
    }

    /**
     * P1 이름(372): 운영처럼 설비 유형의 대표 속성을 비워 표시 이름이 원천 키(P101)일 때, 설비 이름으로 물어도 서버가 대상을 찾는다. 076은 이 이름을
     * 찾지 못하고 속성 값 검색이 찾는다. 같은 시험 DB를 다른 시나리오도 쓰므로 끝나면 대표 속성과 표시 이름을 되돌린다.
     */
    @Test
    void p1ByNameFindsEquipmentWithoutDisplayProperty() {
        String name = "1라인 1호 프레스";
        long equipmentType =
                jdbc.sql("select id from object_type where code = 'Equipment'")
                        .query(Long.class)
                        .single();
        Long displayProperty =
                jdbc.sql("select display_property_id from object_type where id = :type")
                        .param("type", equipmentType)
                        .query((rs, rowNum) -> rs.getObject(1, Long.class))
                        .list()
                        .getFirst();
        Map<Long, String> displayNames = new LinkedHashMap<>();
        jdbc.sql("select id, display_name from object_instance where object_type_id = :type")
                .param("type", equipmentType)
                .query((rs, rowNum) -> displayNames.put(rs.getLong(1), rs.getString(2)))
                .list();
        try {
            jdbc.sql("update object_type set display_property_id = null where id = :type")
                    .param("type", equipmentType)
                    .update();
            jdbc.sql(
                            """
                            update object_instance oi
                            set display_name = (select string_agg(key_value.value, ' / ' order by key_value.key)
                                                from jsonb_each_text(oi.source_key) key_value)
                            where oi.object_type_id = :type
                            """)
                    .param("type", equipmentType)
                    .update();
            assertThat(
                            objectSearch
                                    .search(
                                            admin,
                                            new ObjectSearchRequest(name, null, null, 0, 100))
                                    .getContent())
                    .as("대표 속성이 비면 076은 설비 이름으로 찾지 못한다")
                    .isEmpty();

            Run run = ask("JB2610-0141 남은 수량을 " + name + "에서 찍으면 몇 시간 걸려?", null);

            run.expectRead(
                    data.object("WorkOrder", Map.of("wo_no", "JB2610-0141")),
                    Set.of(AgentCompletionEstimateTools.ESTIMATE_COMPLETION));
            run.expectInput(
                    AgentCompletionEstimateTools.ESTIMATE_COMPLETION,
                    "equipmentId",
                    data.object("Equipment", Map.of("code", "P101")));
            run.log("3,200개 × 11.5초 = 10.2시간 (준비 70분 포함 11.4시간)");
            run.verify();
        } finally {
            jdbc.sql("update object_type set display_property_id = :property where id = :type")
                    .param("property", displayProperty)
                    .param("type", equipmentType)
                    .update();
            displayNames.forEach(
                    (id, displayName) ->
                            jdbc.sql(
                                            "update object_instance set display_name = :name where id = :id")
                                    .param("name", displayName)
                                    .param("id", id)
                                    .update());
        }
    }

    /**
     * L1(359): 종류 전체·조건 질문을 객체 목록 조회로 답한다. 진행 중인 작업지시는 JB2610-0138·0141·0142 세 건이다. 값("진행")은 AI가 목록
     * 결과에서 골라야 한다.
     */
    @Test
    void l1ListsWorkOrdersInProgress() {
        Run run = ask("현재 진행 중인 작업지시 목록 보여줘", null);

        run.expectInput(AgentObjectListTools.OBJECT_LIST, "objectTypeId", typeId("WorkOrder"));
        for (String woNo : List.of("JB2610-0138", "JB2610-0141", "JB2610-0142")) {
            run.expectReached(data.object("WorkOrder", Map.of("wo_no", woNo)));
        }
        run.log("진행 중 작업지시 3건: JB2610-0138, JB2610-0141, JB2610-0142");
        run.verify();
    }

    /** L2(359): "정지 중인 설비"는 저장 값이 "정비중"이라 그대로 거르면 0건이다. 목록 값과 비교해 P103(정비중)을 찾아야 한다. */
    @Test
    void l2FindsStoppedEquipmentByStoredStatusValue() {
        Run run = ask("지금 정지 중인 설비 있어?", null);

        run.expectInput(AgentObjectListTools.OBJECT_LIST, "objectTypeId", typeId("Equipment"));
        run.expectReached(press());
        run.log("가동이 아닌 설비는 P103(1라인 3호 프레스, 상태 정비중) 1대");
        run.verify();
    }

    /** L3(359): 10월 계획 시작 작업지시는 42건이다. 20건만 받으므로 전체 건수와 일부임을 함께 말해야 한다. */
    @Test
    void l3CountsWorkOrdersPlannedInOctober() {
        Run run = ask("10월에 시작 예정이었던 작업지시는 몇 건이야?", null);

        run.expectInput(AgentObjectListTools.OBJECT_LIST, "objectTypeId", typeId("WorkOrder"));
        run.expectInput(AgentObjectListTools.OBJECT_LIST, "dateProperty", "planned_start");
        run.log("42건 (planned_start 2026-10-01~10-31, 20건만 보여 주면 일부임을 말함)");
        run.verify();
    }

    private long typeId(String code) {
        return jdbc.sql("select id from object_type where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long press() {
        return data.object("Equipment", Map.of("code", "P103"));
    }

    /**
     * 질문하고, 서버가 되물으면 reply로 한 번 답한다(2턴). 판정은 마지막 턴으로 한다. 대상 찾기가 바뀌어 첫 질문에 바로 답하면 reply는 쓰지 않는다.
     * reply가 null이면 되묻기에서 멈춘다.
     */
    private Run ask(String question, String reply) {
        Run first = turn(question, null, List.of());
        if (!first.clarified() || reply == null) {
            return first;
        }
        PendingClarification pending =
                PendingClarification.fromAnswer(first.clarifyingQuestion(), first.answerMeta());
        return turn(
                reply,
                pending,
                List.of(new ConversationTurn(question, first.clarifyingQuestion())));
    }

    /**
     * 답변 처리기(AgentAnswerProcessor.answer)가 한 질문을 처리하는 순서를 그대로 밟는다. 처리기는 비동기 이벤트로만 돌아 시험에서 직접 부를 수
     * 없어 여기 옮겼다. 처리기 순서가 바뀌면 함께 바꾼다. 화면 참고 객체와 직전 정상 답변은 쓰지 않는다.
     */
    private Run turn(String question, PendingClarification pending, List<ConversationTurn> recent) {
        AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
        AgentQuerySession session =
                new AgentQuerySession(
                        admin,
                        questionIds.incrementAndGet(),
                        recorder,
                        Clock.systemUTC(),
                        dataAsOfResolver);
        QuestionInterpretation asked =
                interpreter.interpret(question, Map.of(), pending, null, recent, null);
        boolean newQuestion =
                pending != null && !AgentAnswerProcessor.repliesToClarification(asked, pending);
        PendingClarification kept = newQuestion ? null : pending;
        QuestionInterpretation interpretation =
                kept == null ? asked : asked.fillFrom(kept.previous());
        // 처리기의 known(event)처럼 새 질문으로 판정돼도 직전 되묻기의 대상·후보는 넣는다.
        List<AgentReferenceObject> known = new ArrayList<>();
        if (pending != null) {
            known.addAll(pending.previous().referenceObjects());
            known.addAll(pending.preferred());
        }
        known = AgentAnswerProcessor.merged(known, List.of());
        List<AgentTargetResolver.TargetIssue> issues = List.of();
        List<String> unresolved =
                kept == null || !asked.kinds().isEmpty() ? List.of() : kept.unresolvedTargets();
        if (!unresolved.isEmpty()) {
            List<String> answered = asked.targets().isEmpty() ? unresolved : asked.targets();
            AgentTargetResolver.Resolution resolution =
                    targetResolver.resolve(answered, known, session);
            QuestionInterpretation before = kept.previous();
            interpretation =
                    interpretation.withTargets(
                            AgentAnswerProcessor.replaced(before.targets(), unresolved, answered),
                            AgentAnswerProcessor.merged(
                                    before.referenceObjects(), resolution.found()));
            issues = resolution.issues();
        } else {
            List<String> toResolve = AgentAnswerProcessor.targetsToResolve(asked, interpretation);
            if (!toResolve.isEmpty()) {
                AgentTargetResolver.Resolution resolution =
                        targetResolver.resolve(toResolve, known, session);
                interpretation = interpretation.withReferenceObjects(resolution.found());
                issues = resolution.issues();
            }
        }
        log.info("GMS 시나리오 질문: {}", question);
        log.info("GMS 시나리오 해석: {}", interpretation);
        if (!interpretation.missing().isEmpty() || !issues.isEmpty()) {
            String clarifying = AgentAnswerService.clarifyingQuestion(interpretation, issues);
            Map<String, Object> answerMeta =
                    Map.of(
                            "conditions",
                            interpretation.toConditions(),
                            "clarification",
                            AgentAnswerService.clarification(interpretation, issues, known));
            log.info("GMS 시나리오 되묻기: {}", clarifying);
            return new Run(question, recorder, issues, null, clarifying, answerMeta);
        }
        session.allowObjects(interpretation.referenceObjectIds(), interpretation.kinds().isEmpty());
        String answer =
                responder.answer(
                        question,
                        interpretation,
                        session,
                        kept == null ? null : kept.askedQuestion());
        return new Run(question, recorder, issues, answer, null, Map.of());
    }

    /** 한 질문의 처리 결과와 판정. */
    private static final class Run {
        private final String question;
        private final List<Call> calls;
        private final List<AgentTargetResolver.TargetIssue> issues;
        private final String answer;
        private final String clarifyingQuestion;
        private final Map<String, Object> answerMeta;
        private final List<String> failures = new ArrayList<>();

        Run(
                String question,
                AgentQueryRecorder recorder,
                List<AgentTargetResolver.TargetIssue> issues,
                String answer,
                String clarifyingQuestion,
                Map<String, Object> answerMeta) {
            this.question = question;
            this.issues = issues;
            this.answer = answer;
            this.clarifyingQuestion = clarifyingQuestion;
            this.answerMeta = answerMeta;
            this.calls =
                    mockingDetails(recorder).getInvocations().stream()
                            .map(
                                    call ->
                                            new Call(
                                                    ((AgentQueryResult) call.getArguments()[6])
                                                            .toolCode(),
                                                    (Map<?, ?>) call.getArguments()[3],
                                                    (String) call.getArguments()[4],
                                                    (Long) call.getArguments()[5],
                                                    ((AgentQueryResult) call.getArguments()[6])
                                                            .sources()))
                            .toList();
            calls.forEach(call -> log.info("GMS 시나리오 도구: {} 입력 {}", call.tool(), call.input()));
            log.info("GMS 시나리오 답변: {}", answer);
        }

        boolean clarified() {
            return answer == null;
        }

        String clarifyingQuestion() {
            return clarifyingQuestion;
        }

        Map<String, Object> answerMeta() {
            return answerMeta;
        }

        List<AgentTargetResolver.TargetIssue> issues() {
            return issues;
        }

        /** 상세 조회한 객체 ID. */
        List<Long> detailed() {
            return calls.stream()
                    .filter(call -> AgentObjectDetailTools.OBJECT_DETAIL.equals(call.tool()))
                    .map(Call::targetId)
                    .toList();
        }

        /** 그 객체를 tools 중 하나로 직접 조회했어야 한다. */
        void expectRead(long objectId, Set<String> tools) {
            boolean read =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            AgentQueryTools.OBJECT_INSTANCE.equals(
                                                            call.targetType())
                                                    && Long.valueOf(objectId)
                                                            .equals(call.targetId())
                                                    && tools.contains(call.tool()));
            if (!read) {
                failures.add("객체 " + objectId + "를 " + tools + "로 조회하지 않음");
            }
        }

        /** 그 객체에서 관계를 따라가기 시작했어야 한다. 관계 조회의 기준 객체이거나 객체 집합 시작 객체(382)다. */
        void expectStartedFrom(long objectId) {
            boolean started =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            (AgentObjectDetailTools.OBJECT_LINKS.equals(call.tool())
                                                            && Long.valueOf(objectId)
                                                                    .equals(call.targetId()))
                                                    || (AgentObjectSetTools.OBJECT_SET_START.equals(
                                                                    call.tool())
                                                            && hasSource(call, objectId)));
            if (!started) {
                failures.add("객체 " + objectId + "에서 관계 조회나 객체 집합을 시작하지 않음");
            }
        }

        /** 그 객체를 직접 조회·목록으로 받았거나, 객체 집합으로 그 유형을 거쳐 건넜어야 한다(382). */
        void expectReadOrPassedThrough(long objectId, long objectTypeId) {
            if (!readOrListed(objectId) && !hoppedTo(objectTypeId, false)) {
                failures.add("객체 " + objectId + "를 조회하지 않았고 유형 " + objectTypeId + "로 건너지도 않음");
            }
        }

        /**
         * 그 객체를 상세·관계로 직접 조회했거나, 객체 집합으로 그 유형으로 조건을 넣어 건너고 그 객체를 목록으로 받았거나 답에 그 번호를 말했어야 한다(382).
         * 조건 없이 전부 건너 목록 첫 쪽이나 답에 우연히 들어간 경우를 통과시키지 않으려고 조건을 넣은 건너기를 요구한다.
         */
        void expectReadOrAnswered(long objectId, long objectTypeId, String code) {
            boolean read =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            READ_TOOLS.contains(call.tool())
                                                    && Long.valueOf(objectId)
                                                            .equals(call.targetId()));
            boolean found =
                    calls.stream()
                                    .anyMatch(
                                            call ->
                                                    AgentObjectSetTools.OBJECT_SET_LIST.equals(
                                                                    call.tool())
                                                            && hasSource(call, objectId))
                            || (answer != null && answer.contains(code));
            if (!read && !(hoppedTo(objectTypeId, true) && found)) {
                failures.add("객체 " + objectId + "를 조회하지 않았고, 조건을 넣어 건넌 결과·답에 " + code + "도 없음");
            }
        }

        private boolean readOrListed(long objectId) {
            return calls.stream()
                    .anyMatch(
                            call ->
                                    (READ_TOOLS.contains(call.tool())
                                                    && Long.valueOf(objectId)
                                                            .equals(call.targetId()))
                                            || (AgentObjectSetTools.OBJECT_SET_LIST.equals(
                                                            call.tool())
                                                    && hasSource(call, objectId)));
        }

        private boolean hoppedTo(long objectTypeId, boolean filtered) {
            return calls.stream()
                    .filter(
                            call ->
                                    AgentObjectSetTools.OBJECT_SET_SEARCH_AROUND.equals(
                                            call.tool()))
                    .filter(
                            call ->
                                    !filtered
                                            || call.input().get("propertyValues")
                                                            instanceof Map<?, ?> values
                                                    && !values.isEmpty())
                    .flatMap(call -> call.sources().stream())
                    .anyMatch(
                            source ->
                                    Long.valueOf(objectTypeId).equals(source.get("objectTypeId"))
                                            && source.get("count") instanceof Number count
                                            && count.longValue() > 0);
        }

        private static boolean hasSource(Call call, long objectId) {
            return call.sources().stream()
                    .anyMatch(source -> Long.valueOf(objectId).equals(source.get("objectId")));
        }

        /** 그 도구를 그 입력 값으로 불렀어야 한다. 숫자는 Long·Integer를 구분하지 않고 비교한다. */
        void expectInput(String tool, String key, Object value) {
            boolean called =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            tool.equals(call.tool())
                                                    && String.valueOf(value)
                                                            .equals(
                                                                    String.valueOf(
                                                                            call.input()
                                                                                    .get(key))));
            if (!called) {
                failures.add(tool + "를 " + key + "=" + value + "로 부르지 않음");
            }
        }

        /** 답에 이 값들이 없어야 한다. 볼 수 없는 정보의 값을 지어내지 않았는지 본다(S3, 384). */
        void expectAnswerWithout(String... values) {
            for (String value : values) {
                if (answer != null && answer.contains(value)) {
                    failures.add("볼 수 없는 값 " + value + "이 답에 있음");
                }
            }
        }

        /** 그 객체가 도구 결과에 나왔거나 직접 조회했어야 한다. 관계 목록에서 이름만 읽어도 된다. */
        void expectReached(long objectId) {
            boolean reached =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            Long.valueOf(objectId).equals(call.targetId())
                                                    || call.sources().stream()
                                                            .anyMatch(
                                                                    source ->
                                                                            Long.valueOf(objectId)
                                                                                    .equals(
                                                                                            source
                                                                                                    .get(
                                                                                                            "objectId"))));
            if (!reached) {
                failures.add("객체 " + objectId + "가 도구 결과에 나오지 않음");
            }
        }

        void log(String expected) {
            log.info("GMS 시나리오 정답: {}", expected);
        }

        void verify() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("question", question);
            summary.put("clarified", clarified());
            summary.put("toolCalls", calls.size());
            summary.put("tools", calls.stream().map(Call::tool).toList());
            summary.put("failures", failures);
            log.info("GMS 시나리오 판정: {}", summary);
            assertThat(clarified()).as("되묻지 않고 답해야 한다. 정하지 못한 대상: %s", issues).isFalse();
            assertThat(failures).as("꼭 조회해야 하는 객체").isEmpty();
        }
    }

    private record Call(
            String tool,
            Map<?, ?> input,
            String targetType,
            Long targetId,
            List<Map<String, Object>> sources) {}
}

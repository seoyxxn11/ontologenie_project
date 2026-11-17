package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** 수주 번호(OD-연월-순번). */
    private static final Pattern ORDER_NO = Pattern.compile("OD-\\d{4}-\\d{3}");

    /** 작업지시 번호(JB연월-순번). */
    private static final Pattern WORK_ORDER_NO = Pattern.compile("JB\\d{4}-\\d{4}");

    /** P103이 멈추면 영향받는 끝나지 않은 수주. OD-2610-019는 재배정 뒤 밀리는 작업지시의 수주라 말해도 된다(S4). */
    private static final Set<String> UNFINISHED_ORDERS_NEAR_P103 =
            Set.of("OD-2610-021", "OD-2610-019");

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
     * 비가동 10건이 모두 들어가는 기간을 함께 말한다. 장부가가 있는 고정자산은 원천 정책의 금지 표라 시험 데이터에 없다(406). 없는 장부가 값(원천의 8억 3천만
     * 원)을 지어내지 않아야 한다.
     */
    @Test
    void s3FindsDowntimeHistoryAndDoesNotMakeUpHiddenBookValue() {
        Run run =
                ask("P103의 최근 비가동 이력과 ERP 장부가 알려줘", "1라인 3호 프레스(설비)요. 기간은 2026년 1월 1일부터 10월 30일까지");

        run.expectRead(press(), Set.of(AgentObjectDetailTools.OBJECT_LINKS));
        run.expectReached(data.object("Downtime", Map.of("downtime_id", "DT-0460")));
        run.expectAnswerWithout("830,000,000", "830000000", "8억 3천", "8억3천", "8.3억");
        run.log("비가동 10건, 최근 DT-0460(2026-10-30 03:10 시작), 장부가는 데이터에 없음(고정자산은 금지 표, 406)");
        run.verify();
    }

    /** P2: P103 센서 측정을 관계로 찾고, 상한(4.50)을 처음 넘은 진동 측정(2026-10-28 09:00, 4.90)을 상세로 읽는다. */
    @Test
    void p2FindsWhenVibrationOfP103FirstExceededLimit() {
        Run run = ask("P103 진동이 언제부터 상한을 넘었어?", PRESS_REPLY);

        // 측정 기록을 하나씩 상세 조회하면 도구 호출 한도(40)에 걸린다. 객체 목록의 속성 비교(value > threshold_high)와 측정 시각 정렬로
        // 첫 기록을 찾으면 된다(418). 그래서 어느 도구로 읽었는지가 아니라 답의 시각과 값을 본다.
        run.expectAnswerWith("10-28", "4.9");
        run.expectReached(
                jdbc.sql(
                                """
                                select oi.id from object_instance oi
                                join object_type ot on ot.id = oi.object_type_id
                                where ot.code = 'SensorReading'
                                  and oi.source_key ->> 'equipment_code' = 'P103'
                                  and oi.source_key ->> 'metric' = 'vibration_mm_s'
                                  and oi.source_key ->> 'measured_at' like '2026-10-28%09:00%'
                                """)
                        .query(Long.class)
                        .single());
        run.log("2026-10-28 09:00:00부터 (4.90 > 상한 4.50)");
        run.verify();
    }

    /**
     * C1(418): "4건"이라 쓰고 표에 5행을 나열한 일이 있었다(운영 VIEWER). 답에 쓴 건수와 나열한 비가동 번호 수가 같아야 한다. 2026년 10월
     * P103 비가동은 DT-0422·0427·0428·0431·0460 다섯 건이다.
     */
    @Test
    void c1DowntimeCountMatchesListedRows() {
        Run run = ask("2026년 10월 P103의 비가동 이력 보여줘", PRESS_REPLY);
        String answer = run.answer == null ? "" : run.answer;
        Set<String> listed = new TreeSet<>();
        Matcher code = Pattern.compile("DT-[0-9]{4}").matcher(answer);
        while (code.find()) {
            listed.add(code.group());
        }
        // "전체 N건 중 M건"이면 나열한 수는 M이다. 아니면 처음 쓴 "N건"이 나열한 수다.
        Matcher partial =
                Pattern.compile("(?<![0-9])([0-9]++) ?건 ?중 ?(?<![0-9])([0-9]++) ?건")
                        .matcher(answer);
        Matcher said = Pattern.compile("(?<![0-9])([0-9]++) ?건").matcher(answer);
        String count = partial.find() ? partial.group(2) : said.find() ? said.group(1) : null;
        if (count != null && Integer.parseInt(count) != listed.size()) {
            run.failures.add("답에 쓴 건수 " + count + "와 나열한 비가동 수 " + listed.size() + "가 다름");
        }
        run.expectAnswerWith("DT-0422", "DT-0460");
        run.log("10월 비가동 5건(DT-0422·0427·0428·0431·0460)");
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

        long stoppedWork = data.object("WorkOrder", Map.of("wo_no", "JB2610-0141"));
        // 서버가 납기 위험 질문으로 보고 멈춘 설비의 작업지시로 설비 조건 비교를 먼저 부른다(418). 그 결과에 멈춘 작업지시의 수주(workOrderLinked)가
        // 있다.
        run.expectInput(
                AgentEquipmentCapabilityTools.COMPARE_EQUIPMENT_CAPABILITY,
                "workOrderId",
                stoppedWork);
        run.expectReached(data.object("SalesOrder", Map.of("so_no", "OD-2610-021")));
        // 조건 없이 모은 수주를 그대로 나열하면 출하완료 수주가 섞인다(11-13 운영 질문). 끝나지 않은 수주만 말해야 한다.
        // 정답(gold S4)의 납기 위험 수주는 OD-2610-019다. JB2610-0141을 P101로 옮기면 P101에서 대기 중인 JB2610-0145(계획
        // 시작 11-02)가
        // 밀리고 그 수주의 납기는 10-31이다. OD-2610-021만 말하면 정답을 빠뜨린 것이다(418).
        run.expectOnlyOrders(UNFINISHED_ORDERS_NEAR_P103, "OD-2610-019");
        run.log(
                "멈춘 작업지시 JB2610-0141(성형·트리밍 잔량 3,200) → 수주 OD-2610-021(납기 2026-11-04). "
                        + "납기 위험 수주는 OD-2610-019(연결 작업지시 JB2610-0145 대기, 납기 2026-10-31)");
        run.verify();
    }

    /**
     * S5(11-13 운영 질문): P103에 공정이 배정된 작업지시 61건의 수주 57건 중 56건은 출하완료이고, 끝나지 않은 것은 OD-2610-021(생산중, 납기
     * 2026-11-04)뿐이다. 조건 없이 모아 오래된 출하완료 수주를 답하던 문제를 잡는다.
     */
    @Test
    void s5AnswersOnlyUnfinishedOrdersAffectedByStoppedP103() {
        Run run = ask("P103이 멈췄어. 어떤 주문이 영향을 받아? 납기도 알려줘.", PRESS_REPLY);

        run.expectStartedFrom(press());
        // OD-2610-019는 P103 경로의 수주가 아니다(재배정까지 따지는 S4만 허용). 여기서는 OD-2610-021만 허용한다(리뷰 m8).
        run.expectOnlyOrders(Set.of("OD-2610-021"), "OD-2610-021");
        run.log("P103 공정 배정 122 → 작업지시 61 → 수주 57 중 끝나지 않은 것은 OD-2610-021(생산중, 납기 2026-11-04)뿐");
        run.verify();
    }

    /**
     * G1(11-13 일반화 확인): 지시문 예시에 없는 설비·상태 값으로 S5와 같은 꼴을 묻는다. P201 공정 배정 30건 → 작업지시 15건 → 수주 15건 중
     * 끝나지 않은 것은 OD-2610-018(생산중, 납기 11-05)과 OD-2610-020(접수, 납기 11-10) 두 건이다. 끝나지 않은 값이 둘이라 값마다 건너야
     * 한다.
     */
    @Test
    void g1AnswersUnfinishedOrdersIfP201BreaksDown() {
        Run run = ask("P201이 고장 나면 영향받는 수주는?", "2라인 1호 프레스(설비)요");

        run.expectStartedFrom(data.object("Equipment", Map.of("code", "P201")));
        run.expectOnlyOrders(Set.of("OD-2610-018", "OD-2610-020"), "OD-2610-018", "OD-2610-020");
        run.log("OD-2610-018(생산중, 납기 2026-11-05), OD-2610-020(접수, 납기 2026-11-10)");
        run.verify();
    }

    /**
     * G2(11-13 일반화 확인): M1과 같은 2홉 꼴이지만 설비·상태 값이 다르다. 1라인 블랭킹기(B101)에 공정이 배정된 작업지시 139건 중 대기는
     * JB2610-0143, JB2610-0145 두 건이다. 조건(대기)은 공정이 아니라 작업지시 단계에 넣어야 한다.
     */
    @Test
    void g2FindsWaitingWorkOrdersAssignedToBlanker() {
        Run run = ask("1라인 블랭킹기에 배정된 대기 중인 작업지시 보여줘", "1라인 블랭킹기(설비)요");

        run.expectStartedFrom(data.object("Equipment", Map.of("code", "B101")));
        run.expectOnlyWorkOrders(
                Set.of("JB2610-0143", "JB2610-0145"), "JB2610-0143", "JB2610-0145");
        // 공정에도 같은 "대기" 값이 있어 공정 단계에 조건을 걸어도 우연히 맞는다. 작업지시로 건널 때 조건을 넣었는지 따로 본다.
        run.expectFilteredHopTo(typeId("WorkOrder"));
        run.log("대기 작업지시 JB2610-0143, JB2610-0145");
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
     * R1(대체 설비 비교 요청, 2026-11-13): 대체 설비 조건 비교를 저장 없이 한다. JB2610-0141(제품 BRK-210R)의 공정 20·30은
     * PRS·최소 400톤이다. P101은 PRS 400톤이라 충족, P201은 PRS 300톤이라 미충족이다. 재배정 계획 저장 Action을 부르지 않아야 한다.
     */
    @Test
    void r1ComparesAlternativeEquipmentWithoutSavingPlan() {
        Run run =
                ask(
                        "JB2610-0141을 P101이나 P201에서 이어서 생산할 수 있어? 각 설비의 능력이 이 작업에 필요한 조건을 충족하는지 비교해 줘.",
                        "작업지시 JB2610-0141, 설비 P101과 P201이요");

        run.expectInput(
                AgentEquipmentCapabilityTools.COMPARE_EQUIPMENT_CAPABILITY,
                "workOrderId",
                data.object("WorkOrder", Map.of("wo_no", "JB2610-0141")));
        run.expectAnswerWith("400", "300", "미충족");
        run.expectNotCalled("CREATE_REASSIGNMENT_PLAN");
        run.log("공정 20·30 PRS·최소 400톤. P101 400톤 충족, P201 300톤 미충족. 계획 저장 안 함");
        run.verify();
    }

    /**
     * W1(대화 103의 B2, 2026-11-14): 같은 질문이 한 번은 맞고 한 번은 "확인 불가"로 끝났다. 관계 따라가기에 "진행·대기"를 값 목록으로 넣다 입력을
     * 읽지 못하고, 조건 없이 63건을 모아 첫 쪽만 본 뒤 포기했다. P101의 진행·대기 작업지시는 JB2610-0138(진행, OD-2610-021)과
     * JB2610-0145(대기, OD-2610-019)다.
     */
    @Test
    void w1ComparesWaitingAndRunningWorkOrdersOnP101() {
        Run run =
                ask(
                        "P101에 배정된 진행·대기 작업을 확인해 줘. 특히 JB2610-0138과 JB2610-0145의 연결 수주, 납기, 계획 시작일을 비교해"
                                + " 줘.",
                        "1라인 1호 프레스(설비)요");

        run.expectAnswerWith("JB2610-0138", "JB2610-0145", "OD-2610-021", "OD-2610-019");
        run.expectAnswerWithout("확인 불가", "확인하지 못");
        run.log("JB2610-0138 진행·OD-2610-021, JB2610-0145 대기·OD-2610-019");
        run.verify();
    }

    /**
     * W2(대화 102의 A1, 2026-11-14): 답은 맞았지만 관계 따라가기 조건을 출발 유형의 속성(equipment_code, code)에 걸어 두 번 입력
     * 오류가 나 27초가 걸렸다. JB2610-0141의 중단 공정 20·30은 잔량 3,200개씩, 연결 수주는 OD-2610-021(납기 2026-11-04)이다.
     */
    @Test
    void w2ReadsStoppedOperationsOfWorkOrderOnP103() {
        Run run = ask("P103에서 중단된 JB2610-0141의 공정과 잔량을 확인하고, 연결된 수주와 납기를 알려줘.", null);

        run.expectAnswerWith("3,200", "OD-2610-021", "2026-11-04");
        run.expectAnswerWithout("확인 불가", "확인하지 못");
        run.log("공정 20·30 잔량 3,200, OD-2610-021 납기 2026-11-04");
        run.verify();
    }

    /** 운영 대화 95(2026-11-14)의 앞 세 질문. 계획 저장 요청 전에 정지 공정·P101 작업·설비 조건 비교를 차례로 물었다. */
    private static final List<String> PLAN_CONTEXT =
            List.of(
                    "P103에서 중단된 JB2610-0141의 공정과 잔량을 확인하고, 연결된 수주와 납기를 알려줘.",
                    "P101에 배정된 진행·대기 작업을 확인해 줘. 특히 JB2610-0138과 JB2610-0145의 연결 수주, 납기, 계획 시작일을 비교해 줘.",
                    "JB2610-0141을 P101이나 P201에서 이어서 생산할 수 있어? 각 설비의 능력이 이 작업에 필요한 조건을 충족하는지 비교해 줘.");

    /**
     * R2(재배정 계획 저장 실패, 대화 95): 대체 설비만 말한 저장 요청에서 정지 설비를 P101로도 넣어(P101 → P101) BLOCKED됐다. 정지 설비는
     * 공정이 지금 배정된 P103이어야 한다. 실행기는 가짜라 계획을 저장하지 않고 입력만 본다.
     */
    @Test
    void r2SavesPlanFromAssignedEquipmentWhenOnlyTargetIsNamed() {
        expectPlanFromP103ToP101(
                conversation(
                        PLAN_CONTEXT.get(0),
                        PLAN_CONTEXT.get(1),
                        PLAN_CONTEXT.get(2),
                        "JB2610-0141을 P101로 옮기는 재배정 계획을 저장해 줘."));
    }

    /**
     * R3(대화 95 재질문): 작업지시·정지 설비·대체 설비를 모두 말했는데 해석이 P103만 남겨 작업지시를 잃고, 공정 배정 ID를 작업지시 ID로 넣어 비교가
     * "없음"으로 끝났다. 세 대상을 모두 잇고 P103 → P101 계획을 저장해야 한다.
     */
    @Test
    void r3SavesPlanWhenFromAndToAreBothNamed() {
        expectPlanFromP103ToP101(
                conversation(
                        PLAN_CONTEXT.get(0),
                        PLAN_CONTEXT.get(1),
                        PLAN_CONTEXT.get(2),
                        "JB2610-0141의 P103에 배정된 중단 공정을 P103에서 P101로 옮기는 재배정 계획을 저장해 줘. 기존 설비는"
                                + " P103이고, 대체 설비는 P101이야."));
    }

    /** 대화의 마지막 질문에서만 계획을 저장하고, 그 입력이 P103 → P101(작업지시 JB2610-0141, 공정 20·30)인지 본다. */
    private void expectPlanFromP103ToP101(PlanConversation conversation) {
        long workOrder = data.object("WorkOrder", Map.of("wo_no", "JB2610-0141"));
        long p103 = data.object("Equipment", Map.of("code", "P103"));
        long p101 = data.object("Equipment", Map.of("code", "P101"));
        Run last = conversation.runs().getLast();
        List<String> failures = new ArrayList<>();
        List<Map<String, Object>> executed = conversation.executed();
        if (conversation.executedBeforeLast() > 0) {
            failures.add("저장을 요청하기 전 질문에서 계획 저장을 부름");
        }
        if (executed.isEmpty()) {
            failures.add("계획 저장 실행기를 부르지 않음. 답: " + last.answer);
        }
        for (Map<String, Object> input : executed) {
            if (!(input.get("fromEquipmentId") instanceof Number from)
                    || from.longValue() != p103) {
                failures.add("정지 설비가 P103(" + p103 + ")이 아님: " + input.get("fromEquipmentId"));
            }
            List<?> items = input.get("items") instanceof List<?> list ? list : List.of();
            Set<String> operations = new TreeSet<>();
            for (Object item : items) {
                Map<?, ?> map = (Map<?, ?>) item;
                if (!(map.get("workOrderId") instanceof Number id) || id.longValue() != workOrder) {
                    failures.add(
                            "작업지시 ID가 JB2610-0141("
                                    + workOrder
                                    + ")이 아님: "
                                    + map.get("workOrderId"));
                }
                if (!(map.get("toEquipmentId") instanceof Number to) || to.longValue() != p101) {
                    failures.add("대체 설비가 P101(" + p101 + ")이 아님: " + map.get("toEquipmentId"));
                }
                if (map.get("operationCode") != null) {
                    operations.add(String.valueOf(map.get("operationCode")));
                }
            }
            if (!operations.isEmpty() && !operations.equals(Set.of("20", "30"))) {
                failures.add("옮길 공정이 20·30이 아님: " + operations);
            }
        }
        log.info(
                "GMS 시나리오 판정: {}",
                Map.of(
                        "question",
                        last.question,
                        "notRun",
                        conversation.notRun(),
                        "executed",
                        executed,
                        "failures",
                        failures));
        assertThat(failures).as("재배정 계획 저장").isEmpty();
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

    /**
     * H1(419): 작업자는 이름이 금지라 대표 속성이 없고 표시 이름이 원천 키(6)다. "작업자 6"을 통째로 찾으면 0건이라 대상을 찾지 못했다. 유형 이름과 번호로
     * 나눠 현장 작업자 6을 찾고, 개인 평가로 거절하지 않고 교대·라인을 답해야 한다.
     */
    @Test
    void h1FindsWorkerByTypeWordAndNumber() {
        Run run = ask("작업자 6 상세 정보 알려줘", null);

        run.expectReached(data.object("Worker", Map.of("id", "6")));
        run.expectAnswerWith("주간", "1라인");
        run.log("현장 작업자 6: 주간, 1라인, 재직(Y). 숙련도는 민감 속성이라 볼 수 없음");
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

    /** 대화 하나의 결과. executed는 계획 저장 실행기로 넘어간 입력, notRun은 저장 전 확인에서 실행하지 않은 횟수다. */
    private record PlanConversation(
            List<Run> runs,
            List<Map<String, Object>> executed,
            int executedBeforeLast,
            int notRun) {}

    /**
     * 같은 대화에서 질문을 차례로 한다. 처리기처럼 직전 정상 답변의 조건을 잇고(continueFrom), 다시 쓴 이전 대상을
     * 되살리고(withRepeatedTargets), 최근 대화 원문을 해석에 준다. 되물으면 거기서 멈춘다. 재배정 계획 저장은 진짜 Agent 도구(저장 전 확인
     * 포함)로 부르되 실행기는 가짜라 입력만 모은다.
     */
    private PlanConversation conversation(String... questions) {
        ActionExecutionService executions = mock(ActionExecutionService.class);
        List<Map<String, Object>> executed = new ArrayList<>();
        when(executions.execute(any()))
                .thenAnswer(
                        call -> {
                            ActionExecutionCommand command = call.getArgument(0);
                            executed.add(command.input());
                            ActionExecutionResponse response = mock(ActionExecutionResponse.class);
                            when(response.id()).thenReturn(9000L + executed.size());
                            when(response.status()).thenReturn(ExecutionStatus.SUCCEEDED);
                            when(response.result())
                                    .thenReturn(Map.of("planId", executed.size(), "itemCount", 2));
                            return response;
                        });
        List<AgentQuerySession> sessions = new ArrayList<>();
        when(actionTools.forAnswer(any()))
                .thenAnswer(
                        call -> {
                            AgentQuerySession session = call.getArgument(0);
                            sessions.add(session);
                            return List.of(
                                    new AgentActionTool(PLAN_ACTION, session, executions, json));
                        });
        List<Run> runs = new ArrayList<>();
        List<ConversationTurn> recent = new ArrayList<>();
        QuestionInterpretation previous = null;
        int executedBeforeLast = 0;
        for (int i = 0; i < questions.length; i++) {
            String question = questions[i];
            AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
            AgentQuerySession session =
                    new AgentQuerySession(
                            admin,
                            questionIds.incrementAndGet(),
                            recorder,
                            Clock.systemUTC(),
                            dataAsOfResolver);
            QuestionInterpretation asked =
                    interpreter
                            .interpret(
                                    question, Map.of(), null, previous, List.copyOf(recent), null)
                            .withRepeatedTargets(question, previous);
            QuestionInterpretation interpretation =
                    previous == null ? asked : asked.continueFrom(previous);
            List<AgentReferenceObject> known =
                    previous == null ? List.of() : previous.referenceObjects();
            List<String> toResolve = AgentAnswerProcessor.targetsToResolve(asked, interpretation);
            List<AgentTargetResolver.TargetIssue> issues = List.of();
            if (!toResolve.isEmpty()) {
                AgentTargetResolver.Resolution resolution =
                        targetResolver.resolve(toResolve, known, session);
                interpretation = interpretation.withReferenceObjects(resolution.found());
                issues = resolution.issues();
            }
            log.info("GMS 시나리오 질문: {}", question);
            log.info("GMS 시나리오 해석: {}", interpretation);
            if (!interpretation.missing().isEmpty() || !issues.isEmpty()) {
                String clarifying = AgentAnswerService.clarifyingQuestion(interpretation, issues);
                log.info("GMS 시나리오 되묻기: {}", clarifying);
                runs.add(new Run(question, recorder, issues, null, clarifying, Map.of()));
                break;
            }
            session.allowObjects(interpretation.referenceObjectIds());
            String answer = responder.answer(question, interpretation, session, null);
            runs.add(new Run(question, recorder, issues, answer, null, Map.of()));
            if (i < questions.length - 1) {
                executedBeforeLast = executed.size();
            }
            recent.add(new ConversationTurn(question, answer, i + 1));
            previous = interpretation;
        }
        int notRun =
                (int)
                        sessions.stream()
                                .flatMap(session -> session.actions().stream())
                                .filter(action -> AgentActionTool.NOT_RUN.equals(action.status()))
                                .count();
        return new PlanConversation(runs, executed, executedBeforeLast, notRun);
    }

    /** 운영 V10의 재배정 계획 Action 정의와 같은 입력 형식이다. 이 시험은 실행기를 가짜로 둔다. */
    private static final ExecutableAction PLAN_ACTION =
            new ExecutableAction(
                    AgentActionTool.REASSIGNMENT_PLAN,
                    "재배정 계획 생성",
                    "정지 설비의 작업지시·공정을 대체 설비로 옮기는 계획을 저장한다. 원천 MES 배정은 바꾸지 않는다.",
                    Map.of(
                            "type",
                            "object",
                            "required",
                            List.of("fromEquipmentId", "items", "dataAsOf"),
                            "properties",
                            Map.of(
                                    "fromEquipmentId",
                                    Map.of("type", "integer", "minimum", 1),
                                    "items",
                                    Map.of(
                                            "type",
                                            "array",
                                            "minItems",
                                            1,
                                            "items",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "required",
                                                    List.of("workOrderId", "toEquipmentId"),
                                                    "properties",
                                                    Map.of(
                                                            "workOrderId",
                                                            Map.of("type", "integer", "minimum", 1),
                                                            "operationCode",
                                                            Map.of(
                                                                    "type",
                                                                    List.of("string", "null"),
                                                                    "maxLength",
                                                                    50),
                                                            "toEquipmentId",
                                                            Map.of(
                                                                    "type", "integer", "minimum",
                                                                    1)))),
                                    "selectionBasis",
                                    Map.of("type", "object"),
                                    "dataAsOf",
                                    Map.of("type", "string", "format", "date-time"))),
                    false);

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
        session.allowObjects(interpretation.referenceObjectIds());
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
                                                    || ((AgentObjectSetTools.OBJECT_SET_START
                                                                            .equals(call.tool())
                                                                    || AgentObjectSetTools
                                                                            .OBJECT_SET_FIND
                                                                            .equals(call.tool()))
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

        /** 객체 집합으로 그 유형에 조건을 넣어 건넜어야 한다. 조건을 중간 단계에 걸고 우연히 맞은 답을 걸러 낸다(G2). */
        void expectFilteredHopTo(long objectTypeId) {
            if (!hoppedTo(objectTypeId, true)) {
                failures.add("유형 " + objectTypeId + "로 건널 때 조건을 넣지 않음");
            }
        }

        /**
         * 객체 집합으로 그 유형에 닿았는지 본다. 건너기(SEARCH_AROUND)는 도착 유형, 목표 유형 찾기(FIND)는 지나간 유형(objectTypeIds)으로
         * 본다. filtered면 조건을 넣은 호출만 센다. FIND의 조건은 도착 유형에만 걸리므로 filtered는 도착 유형으로만 본다.
         */
        private boolean hoppedTo(long objectTypeId, boolean filtered) {
            boolean found =
                    calls.stream()
                            .filter(call -> AgentObjectSetTools.OBJECT_SET_FIND.equals(call.tool()))
                            .filter(call -> !filtered || hasConditions(call))
                            .flatMap(call -> call.sources().stream())
                            .anyMatch(
                                    source ->
                                            source.get("count") instanceof Number count
                                                    && count.longValue() > 0
                                                    && (filtered
                                                            ? Long.valueOf(objectTypeId)
                                                                    .equals(
                                                                            source.get(
                                                                                    "objectTypeId"))
                                                            : source.get("objectTypeIds")
                                                                            instanceof List<?> types
                                                                    && types.contains(
                                                                            objectTypeId)));
            return found || searchedAroundTo(objectTypeId, filtered);
        }

        private static boolean hasConditions(Call call) {
            return call.input().get("propertyValues") instanceof Map<?, ?> values
                    && !values.isEmpty();
        }

        private boolean searchedAroundTo(long objectTypeId, boolean filtered) {
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

        /**
         * 답에 required 수주 번호가 모두 있어야 하고, 답에 나온 수주 번호(OD-…)는 모두 allowed 안에 있어야 한다. 끝난 수주를 섞지 않았는지 본다.
         */
        void expectOnlyOrders(Set<String> allowed, String... required) {
            expectOnlyCodes(ORDER_NO, allowed, required);
        }

        /** expectOnlyOrders와 같고 작업지시 번호(JB…)를 본다. */
        void expectOnlyWorkOrders(Set<String> allowed, String... required) {
            expectOnlyCodes(WORK_ORDER_NO, allowed, required);
        }

        private void expectOnlyCodes(Pattern pattern, Set<String> allowed, String... required) {
            for (String code : required) {
                if (answer == null || !answer.contains(code)) {
                    failures.add("답에 " + code + "이 없음");
                }
            }
            if (answer == null) {
                return;
            }
            Set<String> others = new TreeSet<>();
            Matcher matcher = pattern.matcher(answer);
            while (matcher.find()) {
                if (!allowed.contains(matcher.group())) {
                    others.add(matcher.group());
                }
            }
            if (!others.isEmpty()) {
                failures.add("조건에 맞지 않는 번호를 답에 넣음: " + others);
            }
        }

        /** 답에 이 값들이 모두 있어야 한다. */
        void expectAnswerWith(String... values) {
            for (String value : values) {
                if (answer == null || !answer.contains(value)) {
                    failures.add("답에 " + value + "이 없음");
                }
            }
        }

        /** 그 도구를 부르지 않았어야 한다(예: 가능 여부만 물었는데 저장 Action을 부름). */
        void expectNotCalled(String tool) {
            if (calls.stream().anyMatch(call -> tool.equals(call.tool()))) {
                failures.add(tool + "를 부름");
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

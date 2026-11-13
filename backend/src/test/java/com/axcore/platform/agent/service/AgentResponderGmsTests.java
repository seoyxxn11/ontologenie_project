package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실제 GMS로 Agent가 조회 도구를 골라 부르는지 확인한다(326). AXCORE_AGENT_LLM=openai와 키를 넣은 PC에서만 돌고, 없으면 건너뛴다.
 *
 * <p>객체 검색(076)은 실제 서비스와 Testcontainers DB를 쓴다. 객체 상세·관계(077·078)는 정해 둔 응답을 돌려주도록 가짜로 두고, 검색이나 관계
 * 결과의 ID를 상세 조회에 이어서 넘기는지 본다. Action 도구는 빼서 계획이 만들어지지 않게 한다. AI 답은 실행마다 달라질 수 있어 답변 문장은 로그로만 남긴다.
 */
@Slf4j
@SpringBootTest(properties = "test.context=agent-gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class AgentResponderGmsTests {
    private static final LoginAccount ADMIN = new LoginAccount(1L, "gms-check", "ADMIN", false);

    @Autowired ObjectProvider<ChatModel> chatModels;
    @Autowired AuditExecutionQueryService executions;
    @Autowired ObjectSearchQuery objectSearch;
    @Autowired ObjectProvider<ObjectEvidenceQuery> objectEvidence;
    @Autowired ObjectProvider<ObjectPathPlanner> objectPaths;
    @Autowired ModelSummaryService modelSummary;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired PropertyValueSampleService propertyValues;
    @Autowired JdbcClient jdbc;
    @MockitoBean AgentActionTools actionTools;
    @Autowired CompletionEstimator completionEstimator;

    @Value("${axcore.agent.reasoning-effort:none}")
    String reasoningEffort;

    // 객체 검색·상세·근거는 한 빈(ObjectQueryService)이 함께 구현한다. 빈을 가짜로 바꾸면 검색도 사라지므로 상세·관계만 가짜로 넣는다.
    private final ObjectDetailQuery objectDetails = mock(ObjectDetailQuery.class);

    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);

    @Test
    void searchesObjectByCodeThenReadsItsDetail() {
        long type = objectType();
        long press =
                object(
                        type,
                        "프레스 PR-93",
                        """
                        {"equipment_code":"PR-93","tonnage":800,"line":"2공장 A라인"}
                        """);
        object(type, "프레스 PR-94", "{\"equipment_code\":\"PR-94\",\"tonnage\":600}");
        when(objectDetails.detail(any(), eq(press))).thenReturn(detail(press, type));
        when(objectDetails.links(any(), anyLong(), any())).thenReturn(new PageImpl<>(List.of()));
        AgentQuerySession session = new AgentQuerySession(ADMIN, 11L, recorder, Clock.systemUTC());

        String answer =
                responder()
                        .answer(
                                "PR-93 설비 데이터가 어느 원천 시스템의 어떤 테이블에서 왔는지 알려줘",
                                new QuestionInterpretation(
                                        "원천 위치", List.of("PR-93"), null, null, List.of(), null),
                                session,
                                null);

        List<String> called = logCalls(session, answer);

        assertThat(called)
                .contains(AgentQueryTools.OBJECT_SEARCH, AgentObjectDetailTools.OBJECT_DETAIL);
        assertThat(called.indexOf(AgentQueryTools.OBJECT_SEARCH))
                .isLessThan(called.indexOf(AgentObjectDetailTools.OBJECT_DETAIL));
        verify(objectDetails).detail(any(), eq(press));
    }

    /** Action 정의서 8-1 2단계: 설비에서 '현재 배정 설비' 관계를 거꾸로 따라가 배정된 작업지시를 찾는다. */
    @Test
    void searchesEquipmentThenFollowsLinksToAssignedWorkOrders() {
        long type = objectType();
        long press = object(type, "프레스 PR-95", "{\"equipment_code\":\"PR-95\",\"tonnage\":800}");
        when(objectDetails.links(any(), anyLong(), any())).thenReturn(new PageImpl<>(List.of()));
        when(objectDetails.links(any(), eq(press), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(
                                        assignedWorkOrder(91L, 501L, "WO-501", press),
                                        assignedWorkOrder(92L, 502L, "WO-502", press)),
                                PageRequest.of(0, AgentQueryTools.MAX_ROWS),
                                2));
        AgentQuerySession session = new AgentQuerySession(ADMIN, 12L, recorder, Clock.systemUTC());

        String answer =
                responder()
                        .answer(
                                "PR-95에 배정된 작업지시 알려줘",
                                new QuestionInterpretation(
                                        "배정된 작업지시", List.of("PR-95"), null, null, List.of(), null),
                                session,
                                null);

        List<String> called = logCalls(session, answer);

        assertThat(called)
                .contains(AgentQueryTools.OBJECT_SEARCH, AgentObjectDetailTools.OBJECT_LINKS);
        assertThat(called.indexOf(AgentQueryTools.OBJECT_SEARCH))
                .isLessThan(called.indexOf(AgentObjectDetailTools.OBJECT_LINKS));
        verify(objectDetails).links(any(), eq(press), any());
    }

    /**
     * 답변 처리기처럼 서버가 대상(PR-96)을 정하고 허용 목록을 둔 상태에서, 관계로 찾은 작업지시의 상세를 조회하는지 본다. 관계 결과에 나온 객체는 그 답변에서
     * 조회할 수 있다(326).
     */
    @Test
    void readsObjectFoundThroughLinksWhenServerChoseTarget() {
        long type = objectType();
        long press = object(type, "프레스 PR-96", "{\"equipment_code\":\"PR-96\",\"tonnage\":800}");
        long workOrder = object(type, "WO-503", "{\"work_order_no\":\"WO-503\",\"status\":\"진행\"}");
        when(objectDetails.links(any(), anyLong(), any())).thenReturn(new PageImpl<>(List.of()));
        when(objectDetails.links(any(), eq(press), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(assignedWorkOrder(93L, workOrder, "WO-503", press)),
                                PageRequest.of(0, AgentQueryTools.MAX_ROWS),
                                1));
        when(objectDetails.detail(any(), eq(workOrder)))
                .thenReturn(workOrderDetail(workOrder, type));
        AgentQuerySession session = new AgentQuerySession(ADMIN, 13L, recorder, Clock.systemUTC());
        session.allowObjects(List.of(press), true);

        String answer =
                responder()
                        .answer(
                                "PR-96에 배정된 작업지시의 상세 속성을 알려줘",
                                new QuestionInterpretation(
                                        "배정된 작업지시 상세",
                                        List.of("PR-96"),
                                        null,
                                        null,
                                        List.of(new AgentReferenceObject(press, "프레스 PR-96", "설비")),
                                        null),
                                session,
                                null);

        List<String> called = logCalls(session, answer);

        assertThat(called)
                .contains(
                        AgentObjectDetailTools.OBJECT_LINKS, AgentObjectDetailTools.OBJECT_DETAIL);
        assertThat(called.indexOf(AgentObjectDetailTools.OBJECT_LINKS))
                .isLessThan(called.lastIndexOf(AgentObjectDetailTools.OBJECT_DETAIL));
        verify(objectDetails).detail(any(), eq(workOrder));
    }

    private AgentResponder responder() {
        StaticListableBeanFactory details = new StaticListableBeanFactory();
        details.addBean("objectDetailQuery", objectDetails);
        return new AgentResponder(
                chatModels,
                executions,
                objectSearch,
                details.getBeanProvider(ObjectDetailQuery.class),
                objectEvidence,
                objectPaths,
                modelSummary,
                dataAccess,
                actionTools,
                completionEstimator,
                propertyValues,
                Clock.systemUTC(),
                reasoningEffort);
    }

    /** 유형으로 좁혀야 하는 질문이면 모델 요약으로 유형 ID를 얻은 뒤 그 유형으로 검색한다(345). 같은 코드를 쓰는 다른 유형의 객체가 섞이지 않아야 한다. */
    @Test
    void readsModelSummaryThenSearchesWithinTheNamedType() {
        String tag = String.valueOf(System.nanoTime() % 100_000);
        long equipment = objectType("시험설비-" + tag);
        long mold = objectType("시험금형-" + tag);
        object(equipment, "프레스 PR-97", "{\"equipment_code\":\"PR-97\"}");
        object(mold, "금형 PR-97", "{\"mold_code\":\"PR-97\"}");
        AgentQuerySession session = new AgentQuerySession(ADMIN, 14L, recorder, Clock.systemUTC());

        String answer =
                responder()
                        .answer(
                                "'시험설비-" + tag + "' 유형 객체 중에서 PR-97을 찾아줘",
                                new QuestionInterpretation(
                                        "객체 찾기", List.of("PR-97"), null, null, List.of(), null),
                                session,
                                null);

        List<String> called = logCalls(session, answer);

        assertThat(called)
                .contains(AgentModelSummaryTools.MODEL_SUMMARY, AgentQueryTools.OBJECT_SEARCH);
        assertThat(called.indexOf(AgentModelSummaryTools.MODEL_SUMMARY))
                .isLessThan(called.indexOf(AgentQueryTools.OBJECT_SEARCH));
        assertThat(toolInputs(AgentQueryTools.OBJECT_SEARCH))
                .anySatisfy(input -> assertThat(input).containsEntry("objectTypeId", equipment));
    }

    /** 기록기에 넘긴 도구 입력. 판정에 쓴다. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolInputs(String toolCode) {
        return mockingDetails(recorder).getInvocations().stream()
                .filter(
                        call ->
                                ((AgentQueryResult) call.getArguments()[6])
                                        .toolCode()
                                        .equals(toolCode))
                .map(call -> (Map<String, Object>) call.getArguments()[3])
                .toList();
    }

    /** 도구 호출 순서·입력과 답변을 남긴다. 판정은 도구 호출로만 하고 답변 문장은 사람이 로그로 본다. */
    private List<String> logCalls(AgentQuerySession session, String answer) {
        List<String> called = session.results().stream().map(AgentQueryResult::toolCode).toList();
        log.info("GMS Agent 도구 호출 순서: {}", called);
        mockingDetails(recorder)
                .getInvocations()
                .forEach(call -> log.info("GMS Agent 도구 입력: {}", call.getArguments()[3]));
        log.info("GMS Agent 답변: {}", answer);
        return called;
    }

    /** 작업지시 → 설비 방향의 '현재 배정 설비' 관계. 설비에서 보면 들어오는 관계다(078 예시). */
    private static ObjectLinkResponse assignedWorkOrder(
            long linkId, long workOrderId, String workOrderNo, long equipmentId) {
        return new ObjectLinkResponse(
                linkId,
                12L,
                "현재 배정 설비",
                workOrderId,
                equipmentId,
                new ObjectLinkResponse.RelatedObject(workOrderId, workOrderNo, 5L, "작업지시"),
                ObjectLinkResponse.Origin.RULE,
                "ACTIVE",
                3L,
                null,
                ObjectLinkResponse.ExpressionKind.OBSERVED,
                null);
    }

    private long objectType() {
        return objectType("설비");
    }

    private long objectType(String name) {
        String code = "gms-check-equipment-" + System.nanoTime();
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :name, 'PRODUCTION') returning id
                        """)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long object(long type, String name, String properties) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }

    private static ObjectDetailResponse workOrderDetail(long id, long type) {
        return new ObjectDetailResponse(
                id,
                type,
                "작업지시",
                "WO-503",
                Map.of("work_order_no", "WO-503", "status", "진행", "due_date", "2026-11-14"),
                Map.of("work_order_no", "작업지시 번호", "status", "상태", "due_date", "납기일"),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                9L,
                "MES",
                "mes.work_order",
                Map.of("work_order_no", "WO-503"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:00Z"),
                null);
    }

    private static ObjectDetailResponse detail(long id, long type) {
        return new ObjectDetailResponse(
                id,
                type,
                "설비",
                "프레스 PR-93",
                Map.of("equipment_code", "PR-93", "tonnage", 800, "line", "2공장 A라인"),
                Map.of("equipment_code", "설비 코드", "tonnage", "능력(톤)", "line", "라인"),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                8L,
                "MES",
                "mes.equipment",
                Map.of("equipment_code", "PR-93"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:00Z"),
                null);
    }
}

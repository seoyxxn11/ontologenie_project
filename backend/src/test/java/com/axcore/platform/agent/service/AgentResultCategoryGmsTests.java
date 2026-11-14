package com.axcore.platform.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 조회 결과 유형마다 Agent 답변이 구분해 말하는지 실제 GMS로 확인한다(146). AXCORE_AGENT_LLM=openai와 키를 넣은 PC에서만 돌고, 없으면
 * 건너뛴다.
 *
 * <p>조회 서비스는 가짜로 두고 유형마다 정해 둔 결과를 돌려준다. 데이터 없음·연결 없음·권한 없음·가려진 속성·조회 실패를 질문마다 3번씩 묻는다. 도구가 그 유형을
 * 돌려줬는지와, 답변에 그 유형의 말(예: 권한)이 들어 있고 다른 유형으로 바꿔 말하지 않았는지를 본다. 답변 문장은 실행마다 달라질 수 있어 핵심 낱말만 확인하고 전체는
 * 로그로 남긴다.
 */
@Slf4j
@SpringBootTest(properties = "test.context=agent-gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class AgentResultCategoryGmsTests {
    private static final LoginAccount PLANNER = new LoginAccount(7L, "gms-check", "PLANNER", false);
    private static final long PRESS = 701L;
    private static final AgentReferenceObject PRESS_OBJECT =
            new AgentReferenceObject(PRESS, "프레스 PR-01", "설비");
    private static final int RUNS = 3;

    @Autowired ObjectProvider<ChatModel> chatModels;
    @Autowired ModelSummaryService modelSummary;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired PropertyValueSampleService propertyValues;
    @MockitoBean AgentActionTools actionTools;
    @Autowired CompletionEstimator completionEstimator;
    @Autowired EquipmentCapabilityComparer capabilityComparer;

    @Value("${axcore.agent.reasoning-effort:none}")
    String reasoningEffort;

    private final AuditExecutionQueryService executions = mock(AuditExecutionQueryService.class);
    private final ObjectSearchQuery objectSearch = mock(ObjectSearchQuery.class);
    private final ObjectDetailQuery objectDetails = mock(ObjectDetailQuery.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);

    @Test
    void answersEachResultCategoryDifferently() {
        SoftAssertions softly = new SoftAssertions();
        // 가짜 조회 결과를 질문마다 바꾼다. 이미 오류를 던지도록 정한 메서드를 다시 부르지 않게 doReturn·doThrow로 정한다.
        doReturn(new PageImpl<>(List.of())).when(objectDetails).links(any(), eq(PRESS), any());
        // 실제 076처럼 원천에서 삭제된 객체 검색만 관리자가 아니면 권한 없음이고, 그 밖의 검색은 결과가 없다.
        doReturn(Page.empty()).when(objectSearch).search(any(), any());
        doThrow(
                        new BusinessException(
                                CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN")))
                .when(objectSearch)
                .search(
                        any(),
                        argThat(
                                request ->
                                        request != null
                                                && request.status()
                                                        == ObjectSearchRequest.Status
                                                                .DELETED_AT_SOURCE));
        doReturn(pressWithHiddenPrice()).when(objectDetails).detail(any(), eq(PRESS));
        for (int run = 1; run <= RUNS; run++) {
            // 데이터 없음: 실행 이력이 비었다.
            doReturn(new PageImpl<>(List.of()))
                    .when(executions)
                    .objectExecutions(any(), eq(PRESS), any(), any());
            ask(
                    softly,
                    "데이터 없음",
                    "PR-01 실행 이력 보여줘",
                    "실행 이력",
                    AgentResultCategory.NO_DATA,
                    answer ->
                            softly.assertThat(answer)
                                    .contains("없")
                                    .doesNotContain("권한", "확인하지 못", "조회할 수 없"));

            // 연결 없음: 관계가 하나도 없다.
            ask(
                    softly,
                    "연결 없음",
                    "PR-01에 연결된 작업지시 알려줘",
                    "연결된 작업지시",
                    AgentResultCategory.UNLINKED,
                    answer ->
                            softly.assertThat(answer)
                                    .containsPattern("연결.*없")
                                    .doesNotContain("권한", "확인하지 못", "조회할 수 없"));

            // 권한 없음: 이 사용자는 원천에서 삭제된 객체를 검색할 수 없다.
            askWithoutTarget(
                    softly,
                    "권한 없음",
                    "원천에서 삭제된 설비 중 PR-09를 찾아줘",
                    answer -> softly.assertThat(answer).contains("권한"));

            // 가려진 속성: 단가는 볼 권한이 없어 값이 빠져 있다.
            ask(
                    softly,
                    "가려진 속성",
                    "PR-01 단가 알려줘",
                    "단가",
                    AgentResultCategory.FOUND,
                    answer -> softly.assertThat(answer).contains("권한"));

            // 조회 실패: 실행 이력 조회 중 오류가 났다.
            doThrow(new IllegalStateException("실행 이력 DB에 연결하지 못함"))
                    .when(executions)
                    .objectExecutions(any(), eq(PRESS), any(), any());
            ask(
                    softly,
                    "조회 실패",
                    "PR-01 실행 이력 보여줘",
                    "실행 이력",
                    null,
                    answer ->
                            softly.assertThat(answer)
                                    .contains("확인")
                                    .doesNotContainPattern("(이력|기록|데이터|정보)[이가은는]? ?없"));
        }
        softly.assertAll();
    }

    /** 서버가 대상(PR-01)을 정한 상태로 묻는다. expected가 null이면 조회 실패(결과 분류 없음)를 기대한다. */
    private void ask(
            SoftAssertions softly,
            String name,
            String question,
            String purpose,
            AgentResultCategory expected,
            Consumer<String> checkAnswer) {
        AgentQuerySession session =
                new AgentQuerySession(PLANNER, 21L, recorder, Clock.systemUTC());
        session.allowObjects(List.of(PRESS), true);
        String answer =
                responder()
                        .answer(
                                question,
                                new QuestionInterpretation(
                                        purpose,
                                        List.of("PR-01"),
                                        null,
                                        null,
                                        List.of(PRESS_OBJECT),
                                        null),
                                session,
                                null);
        List<AgentQueryResult> results = session.results();
        log.info("결과 유형 {} | {} | 도구 {} | 답변: {}", name, question, results, answer);
        if (expected == null) {
            softly.assertThat(results).as(name).anyMatch(result -> !result.succeeded());
        } else {
            softly.assertThat(results)
                    .as(name)
                    .anyMatch(result -> result.succeeded() && result.resultCategory() == expected);
        }
        checkAnswer.accept(answer);
    }

    /** 대상을 정하지 못한 상태로 묻는다. AI가 객체 검색을 부른다. */
    private void askWithoutTarget(
            SoftAssertions softly, String name, String question, Consumer<String> checkAnswer) {
        AgentQuerySession session =
                new AgentQuerySession(PLANNER, 22L, recorder, Clock.systemUTC());
        session.allowObjects(List.of(), true);
        String answer =
                responder()
                        .answer(
                                question,
                                new QuestionInterpretation(
                                        "삭제된 설비 검색", List.of("PR-09"), null, null, List.of(), null),
                                session,
                                null);
        List<AgentQueryResult> results = session.results();
        log.info("결과 유형 {} | {} | 도구 {} | 답변: {}", name, question, results, answer);
        softly.assertThat(results)
                .as(name)
                .anyMatch(result -> result.resultCategory() == AgentResultCategory.DENIED);
        checkAnswer.accept(answer);
    }

    private AgentResponder responder() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("objectDetailQuery", objectDetails);
        return new AgentResponder(
                chatModels,
                executions,
                objectSearch,
                beans.getBeanProvider(ObjectDetailQuery.class),
                beans.getBeanProvider(ObjectEvidenceQuery.class),
                beans.getBeanProvider(ObjectPathPlanner.class),
                modelSummary,
                dataAccess,
                actionTools,
                completionEstimator,
                capabilityComparer,
                propertyValues,
                Clock.systemUTC(),
                reasoningEffort,
                false);
    }

    /** 실제 077처럼 가린 속성은 키를 남기고 값만 비운다. */
    private static ObjectDetailResponse pressWithHiddenPrice() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("equipment_code", "PR-01");
        properties.put("line", "2공장 A라인");
        properties.put("unit_price", null);
        return new ObjectDetailResponse(
                PRESS,
                3L,
                "설비",
                "프레스 PR-01",
                properties,
                Map.of("equipment_code", "설비 코드", "line", "라인", "unit_price", "단가"),
                List.of("unit_price"),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}

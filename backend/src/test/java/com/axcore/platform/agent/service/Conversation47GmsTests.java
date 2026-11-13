package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * 대화 47의 사용자 제공 원문을 이전 답변으로 재생한다. 질문 해석과 핵심 요약 답변은 실제 GMS를 호출하고, 객체 ID와 조회 응답은 격리한 시험 값이다. 운영 대화나
 * 운영 DB에는 접근하지 않는다.
 */
@Slf4j
@SpringBootTest(properties = "test.context=gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class Conversation47GmsTests {
    @Autowired QuestionInterpreter interpreter;
    @Autowired ObjectProvider<ChatModel> chatModels;

    @Value("${spring.ai.openai.chat.model}")
    String model;

    @Value("${axcore.agent.reasoning-effort:none}")
    String reasoningEffort;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-12T02:29:00Z"), ZoneOffset.UTC);
    private static final AgentReferenceObject P103 = new AgentReferenceObject(301L, "P103", "설비");
    private static final Map<String, AgentReferenceObject> OBJECTS =
            Map.of(
                    "FA-0001", new AgentReferenceObject(101L, "FA-0001", "고정자산"),
                    "FA-0002", new AgentReferenceObject(102L, "FA-0002", "고정자산"),
                    "FA-0003", new AgentReferenceObject(103L, "FA-0003", "고정자산"),
                    "P103", P103,
                    "JB2610-0138", new AgentReferenceObject(601L, "JB2610-0138", "작업지시"),
                    "DT-0409", new AgentReferenceObject(409L, "DT-0409", "비가동"));

    private static final String HELP =
            """
            안녕하세요. 설비·작업지시·자산 같은 업무 데이터를 찾아 드려요. 이렇게 물어보세요.

            대상 정보: "P101 상세 정보 알려줘"
            연결된 기록: "P103에 연결된 비가동 기록 보여줘"
            실행 이력: "P101 11월 실행 이력 보여줘"
            재배정 계획: "P103 남은 작업 재배정 계획 만들어줘" (권한이 있을 때)
            설비 코드나 이름을 함께 말하면 더 정확하게 찾아요.
            """;

    private static final List<ConversationTurn> ORIGINAL =
            List.of(
                    new ConversationTurn(
                            "FA-0001, FA-0002, FA-0003 장부가와 취득가를 표로 비교해줘",
                            """
                            자산 번호 장부가 취득가 장부가-취득가
                            FA-0001 84,000,000원 540,000,000원 -456,000,000원
                            FA-0002 128,000,000원 360,000,000원 -232,000,000원
                            FA-0003 268,000,000원 790,000,000원 -522,000,000원
                            모두 11-12 09:16 기준입니다.
                            """,
                            1),
                    new ConversationTurn(
                            "P103에 연결된 정비 지시 보여줘",
                            """
                            P103에 연결된 정비 지시는 4건입니다. (11-12 09:17 기준)
                            RP-26-004
                            RP-26-005
                            RP-26-012
                            RP-26-041
                            """,
                            2),
                    new ConversationTurn("아녀녕", HELP, 3),
                    new ConversationTurn(
                            "P103에 연결된 비가동 기록 보여줘",
                            """
                            P103에 연결된 비가동 기록은 10건입니다. (11-12 11:29 기준)
                            DT-0409: 08-31 06:00~09:00, 가이드 마모, 180분
                            DT-0410: 08-31 12:10~15:10, 윤활 계통 점검, 180분
                            DT-0416: 09-16 06:10~06:46, 치수 불량 확인, 36분
                            DT-0420: 09-23 00:50~01:25, 치수 불량 확인, 35분, 작업지시 JB2609-0130
                            DT-0421: 09-29 12:00~12:31, 블랭크 대기, 31분
                            DT-0422: 10-01 00:10~01:19, 코일 대기, 69분
                            DT-0427: 10-14 06:20~06:55, 금형 교체, 35분
                            DT-0428: 10-14 12:40~13:36, 금형 교체, 56분
                            DT-0431: 10-20 06:10~10:40, 윤활 계통 점검, 270분
                            DT-0460: 10-30 03:10 시작, 슬라이드 유압 누유, 종료 시각·비가동 시간 미등록, 작업지시 JB2610-0141
                            """,
                            4),
                    new ConversationTurn(
                            "가이드 마모 부분만 요약해서 알려줘",
                            """
                            질문을 정확히 이해하려면 몇 가지를 더 알려 주세요.
                            '가이드 마모'에 해당하는 대상을 찾지 못했습니다. 이름이나 코드를 다시 알려 주세요.
                            """,
                            5),
                    new ConversationTurn(
                            "JB2610-0138 작업지시 진행 상황 알려줘",
                            """
                            JB2610-0138 작업지시는 진행 중입니다.
                            계획 수량: 6,000개
                            생산 수량: 3,600개 (60%)
                            불량 수량: 52개
                            실제 시작: 2026-10-29 08:00
                            계획 종료일: 2026-10-31
                            실제 종료: 미완료
                            생산 라인: 1라인
                            우선순위: 낮음
                            (11-12 11:30 기준)
                            """,
                            5),
                    new ConversationTurn("방금 질문에 대한 후속 질문이 가능해?", HELP, 6),
                    new ConversationTurn(
                            "DT-0409: 08-31 06:00~09:00, 가이드 마모, 180분\n\n이 부분에 대해 요약해서 알려줘",
                            """
                            DT-0409는 설비 P103에서 발생한 비가동 기록입니다.
                            일시: 2026-08-31 06:00~09:00
                            비가동 시간: 180분
                            사유: 가이드 마모
                            사유 코드: BRK
                            당시 작업지시: 기록 없음
                            (11-12 11:33 기준)
                            """,
                            7));

    @Test
    void replaysOriginalQuestionsAndKeepsEquipmentForGuideWearSummary() {
        List<List<String>> expectedTargets =
                List.of(
                        List.of("FA-0001", "FA-0002", "FA-0003"),
                        List.of("P103"),
                        List.of(),
                        List.of("P103"),
                        List.of("P103"),
                        List.of("JB2610-0138"),
                        List.of(),
                        List.of("DT-0409"));
        QuestionInterpretation previous = null;
        PendingClarification pending = null;
        log.info("GMS 대화47 시험 모델: {}", model);
        for (int i = 0; i < ORIGINAL.size(); i++) {
            ConversationTurn turn = ORIGINAL.get(i);
            List<ConversationTurn> recent =
                    new ArrayList<>(ORIGINAL.subList(Math.max(0, i - 4), i));
            while (recent.stream().mapToInt(RecentTurns::tokens).sum() > 2000) {
                recent.removeFirst();
            }
            QuestionInterpretation asked =
                    interpreter.interpret(
                            turn.question(), Map.of(), pending, previous, recent, null);
            log.info("GMS 대화47 {}: 질문={}, 해석={}", i + 1, turn.question(), asked);
            if (i == 2 || i == 6) {
                assertThat(asked.scope()).isEqualTo(QuestionScope.HELP);
                continue;
            }
            assertThat(asked.scope()).isEqualTo(QuestionScope.BUSINESS);
            boolean newQuestion =
                    pending != null && !AgentAnswerProcessor.repliesToClarification(asked, pending);
            QuestionInterpretation continued =
                    newQuestion || previous == null ? asked : asked.continueFrom(previous);
            assertThat(continued.targets())
                    .as("질문 %s의 대상", i + 1)
                    .containsExactlyInAnyOrderElementsOf(expectedTargets.get(i));
            continued =
                    continued.withReferenceObjects(
                            expectedTargets.get(i).stream().map(OBJECTS::get).toList());
            assertThat(continued.missing()).isEmpty();
            if (i == 4) {
                assertThat(asked.targets()).isEmpty();
                assertThat(asked.kinds()).isEmpty();
                assertThat(continued.referenceObjects()).containsExactly(P103);
                assertThat(continued.purpose()).contains("가이드 마모");
                assertThat(AgentAnswerProcessor.targetsToResolve(asked, continued)).isEmpty();
                String answer = summarizeGuideWear(turn.question(), continued);
                log.info("GMS 대화47 수정 후 실제 요약 답변: {}", answer);
                assertThat(answer).contains("가이드 마모", "180", "06:00", "09:00");
                assertThat(answer).doesNotContain("대상을 찾지 못", "이름이나 코드를 다시");
                // 다음 질문은 사용자가 제공한 원래 되묻기 상태에서도 새 작업지시로 바뀌는지 확인한다.
                pending =
                        new PendingClarification(
                                turn.answer(),
                                new QuestionInterpretation(
                                        "가이드 마모 요약",
                                        List.of("가이드 마모"),
                                        null,
                                        null,
                                        List.of(),
                                        null),
                                List.of("가이드 마모"),
                                List.of());
                continue;
            }
            pending = null;
            previous = continued;
        }
    }

    private String summarizeGuideWear(String question, QuestionInterpretation interpretation) {
        ObjectDetailQuery details = mock(ObjectDetailQuery.class);
        when(details.links(any(), anyLong(), any())).thenReturn(new PageImpl<>(List.of()));
        List<ObjectLinkResponse> links = new ArrayList<>();
        String[][] rows = {
            {"0409", "가이드 마모", "2026-08-31T06:00:00", "2026-08-31T09:00:00", "180"},
            {"0410", "윤활 계통 점검", "2026-08-31T12:10:00", "2026-08-31T15:10:00", "180"},
            {"0416", "치수 불량 확인", "2026-09-16T06:10:00", "2026-09-16T06:46:00", "36"},
            {"0420", "치수 불량 확인", "2026-09-23T00:50:00", "2026-09-23T01:25:00", "35"},
            {"0421", "블랭크 대기", "2026-09-29T12:00:00", "2026-09-29T12:31:00", "31"},
            {"0422", "코일 대기", "2026-10-01T00:10:00", "2026-10-01T01:19:00", "69"},
            {"0427", "금형 교체", "2026-10-14T06:20:00", "2026-10-14T06:55:00", "35"},
            {"0428", "금형 교체", "2026-10-14T12:40:00", "2026-10-14T13:36:00", "56"},
            {"0431", "윤활 계통 점검", "2026-10-20T06:10:00", "2026-10-20T10:40:00", "270"},
            {"0460", "슬라이드 유압 누유", "2026-10-30T03:10:00", "", ""}
        };
        for (String[] row : rows) {
            long id = Long.parseLong(row[0]);
            String name = "DT-" + row[0];
            links.add(
                    new ObjectLinkResponse(
                            id,
                            12L,
                            "비가동 설비",
                            id,
                            P103.id(),
                            new ObjectLinkResponse.RelatedObject(id, name, 5L, "비가동"),
                            ObjectLinkResponse.Origin.RULE,
                            "ACTIVE",
                            null,
                            null,
                            ObjectLinkResponse.ExpressionKind.OBSERVED,
                            null));
            when(details.detail(any(), eq(id)))
                    .thenReturn(
                            new ObjectDetailResponse(
                                    id,
                                    5L,
                                    "비가동",
                                    name,
                                    Map.of(
                                            "downtime_id",
                                            name,
                                            "equipment_code",
                                            "P103",
                                            "reason",
                                            row[1],
                                            "started_at",
                                            row[2],
                                            "ended_at",
                                            row[3],
                                            "minutes",
                                            row[4]),
                                    Map.of(
                                            "reason",
                                            "사유",
                                            "started_at",
                                            "시작 시각",
                                            "ended_at",
                                            "종료 시각",
                                            "minutes",
                                            "비가동 시간(분)"),
                                    List.of(),
                                    ObjectSearchRequest.Status.ACTIVE,
                                    null,
                                    "MES",
                                    "mes.downtime",
                                    Map.of("downtime_id", name),
                                    null,
                                    null,
                                    null));
        }
        when(details.links(any(), eq(P103.id()), any())).thenReturn(new PageImpl<>(links));
        when(details.detail(any(), eq(P103.id())))
                .thenReturn(
                        new ObjectDetailResponse(
                                P103.id(),
                                1L,
                                "설비",
                                "P103",
                                Map.of("equipment_code", "P103"),
                                Map.of(),
                                List.of(),
                                ObjectSearchRequest.Status.ACTIVE,
                                null,
                                "MES",
                                "mes.equipment",
                                Map.of("code", "P103"),
                                null,
                                null,
                                null));
        StaticListableBeanFactory queries = new StaticListableBeanFactory();
        queries.addBean("details", details);
        AgentActionTools actions = mock(AgentActionTools.class);
        when(actions.forAnswer(any())).thenReturn(List.of());
        AgentResponder responder =
                new AgentResponder(
                        chatModels,
                        mock(AuditExecutionQueryService.class),
                        mock(ObjectSearchQuery.class),
                        queries.getBeanProvider(ObjectDetailQuery.class),
                        queries.getBeanProvider(ObjectEvidenceQuery.class),
                        queries.getBeanProvider(ObjectPathPlanner.class),
                        mock(ModelSummaryService.class),
                        mock(DataAccessPolicy.class),
                        actions,
                        mock(CompletionEstimator.class),
                        mock(PropertyValueSampleService.class),
                        CLOCK,
                        reasoningEffort,
                        false);
        AgentQuerySession session =
                new AgentQuerySession(
                        new LoginAccount(1L, "conversation47-test", "ADMIN", false),
                        47L,
                        mock(AgentQueryRecorder.class),
                        CLOCK);
        session.allowObjects(interpretation.referenceObjectIds(), true);
        String answer = responder.answer(question, interpretation, session, null);
        verify(details, atLeastOnce()).links(any(), eq(P103.id()), any());
        verify(details, atLeastOnce()).detail(any(), eq(409L));
        log.info(
                "GMS 대화47 요약 조회: {}",
                session.results().stream().map(AgentQueryResult::toolCode).toList());
        return answer;
    }
}

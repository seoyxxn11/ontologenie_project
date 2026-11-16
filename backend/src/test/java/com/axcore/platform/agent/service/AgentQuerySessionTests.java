package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentQuerySessionTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-06T01:00:00Z"), ZoneOffset.UTC);

    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session = new AgentQuerySession(ASKER, 11L, recorder, CLOCK);

    // 설비 상태처럼 값 종류가 정해진 속성과, 설비 코드처럼 값 종류를 주지 않는 속성.
    private static final List<AgentObjectListTools.PropertyItem> EQUIPMENT_PROPERTIES =
            List.of(
                    new AgentObjectListTools.PropertyItem(
                            "status", "상태", PropertyDataType.STRING, List.of("가동", "정비중", "고장")),
                    new AgentObjectListTools.PropertyItem(
                            "line", "라인", PropertyDataType.STRING, List.of("1라인", "2라인")),
                    new AgentObjectListTools.PropertyItem(
                            "equipment_code", "설비 코드", PropertyDataType.STRING, null));

    private static AgentObjectListTools.ObjectList equipmentList(Map<String, String> unmatched) {
        return new AgentObjectListTools.ObjectList(
                1, 0, false, List.of(), EQUIPMENT_PROPERTIES, unmatched);
    }

    private void list(
            String toolCode, Map<String, Object> conditions, AgentQuerySession.Outcome outcome) {
        session.run(
                toolCode, "객체 목록", Map.of("propertyValues", conditions), null, null, () -> outcome);
    }

    @Test
    void collectsStoredValuesOnlyFromCallsThatFoundSomething() {
        // 0건인 조회의 값은 저장 값에 없던 값일 수 있어 "이 값으로 찾았다"에 넣지 않는다(PropertyValueNotice).
        list(
                "OBJECT_LIST",
                Map.of("status", "정지 중"),
                new AgentQuerySession.Outcome(
                        equipmentList(Map.of("status", "정지 중")),
                        AgentResultCategory.NO_DATA,
                        List.of(),
                        null));
        list(
                "OBJECT_LIST",
                Map.of("status", List.of("정비중", "고장"), "line", " 1라인 "),
                new AgentQuerySession.Outcome(
                        equipmentList(Map.of()), AgentResultCategory.FOUND, List.of(), null));

        assertThat(session.filteredValues()).containsExactlyInAnyOrder("정비중", "고장", "1라인");
        // 0건이어도 그 유형의 값 종류는 기억한다.
        assertThat(session.storedValues()).contains("가동", "정비중", "고장", "1라인", "2라인");
    }

    @Test
    void collectsStoredValueInsteadOfValueAsTyped() {
        // 076은 "정비 중"을 저장 값 "정비중"으로 맞춰 거른다. 안내도 저장 값 모양으로 해야 한다(2026-11-16 리뷰).
        list(
                "OBJECT_LIST",
                Map.of("status", "정비 중"),
                new AgentQuerySession.Outcome(
                        equipmentList(Map.of()), AgentResultCategory.FOUND, List.of(), null));

        assertThat(session.filteredValues()).containsExactly("정비중");
    }

    @Test
    void leavesOutValuesTheQueryCouldNotMatch() {
        // 값 목록은 그중 하나만 맞아도 FOUND다. 저장 값에 없는 값은 실제로 거른 값이 아니다(2026-11-16 리뷰).
        list(
                "OBJECT_LIST",
                Map.of("status", List.of("정지 중", "정비중", "고장 상태")),
                new AgentQuerySession.Outcome(
                        equipmentList(Map.of("status", "정지 중, 고장 상태")),
                        AgentResultCategory.FOUND,
                        List.of(),
                        null));
        // 객체 집합 시작은 결과를 Map으로 돌려준다.
        list(
                "OBJECT_SET_START",
                Map.of("line", "1 라인", "status", "비가동"),
                new AgentQuerySession.Outcome(
                        Map.of("count", 1, "properties", EQUIPMENT_PROPERTIES),
                        AgentResultCategory.FOUND,
                        List.of(),
                        null));

        assertThat(session.filteredValues()).containsExactly("정비중", "1라인");
    }

    @Test
    void leavesOutConditionsOnPropertiesWithoutKnownValues() {
        // 설비 코드처럼 값 종류를 주지 않는 속성의 조건은 물은 상태와 짝지을 수 없다("'상한 초과'… 'P103'으로" 오탐, 2026-11-16 리뷰).
        list(
                "OBJECT_LIST",
                Map.of("equipment_code", "P103"),
                new AgentQuerySession.Outcome(
                        equipmentList(Map.of()), AgentResultCategory.FOUND, List.of(), null));

        assertThat(session.filteredValues()).isEmpty();
    }

    @Test
    void groupsFilteredValuesByTypeAndProperty() {
        // 설비 상태(유형 3)와 비가동의 설비 코드(유형 5)는 다른 속성이다. 설비 코드에도 값 종류가 오는 경우(설비 20대 이하)를 그대로 둔다.
        List<AgentObjectListTools.PropertyItem> downtime =
                List.of(
                        new AgentObjectListTools.PropertyItem(
                                "equipment_code",
                                "설비 코드",
                                PropertyDataType.STRING,
                                List.of("P101", "P103")));
        session.run(
                "OBJECT_LIST",
                "객체 목록",
                Map.of("objectTypeId", 3L, "propertyValues", Map.of("status", "정비중")),
                null,
                null,
                () ->
                        new AgentQuerySession.Outcome(
                                equipmentList(Map.of()),
                                AgentResultCategory.FOUND,
                                List.of(),
                                null));
        session.run(
                "OBJECT_LIST",
                "객체 목록",
                Map.of("objectTypeId", 5L, "propertyValues", Map.of("equipment_code", "P103")),
                null,
                null,
                () ->
                        new AgentQuerySession.Outcome(
                                new AgentObjectListTools.ObjectList(
                                        2, 0, false, List.of(), downtime, Map.of()),
                                AgentResultCategory.FOUND,
                                List.of(),
                                null));

        assertThat(session.filteredValuesByProperty())
                .containsExactly(List.of("정비중"), List.of("P103"));
        assertThat(session.filteredValues()).containsExactly("정비중", "P103");
    }

    @Test
    void keepsSuccessfulQueryWhenNoticeCollectionFails() {
        // 안내용 집계는 부가 기능이라 실패해도 성공한 조회를 실패로 바꾸지 않는다(2026-11-16 리뷰).
        Map<String, Object> reply =
                session.run(
                        "OBJECT_LIST",
                        "객체 목록",
                        Map.of("propertyValues", Map.of("status", "정비중")),
                        null,
                        null,
                        () ->
                                new AgentQuerySession.Outcome(
                                        new AgentObjectListTools.ObjectList(
                                                1,
                                                0,
                                                false,
                                                List.of(),
                                                List.of(
                                                        new AgentObjectListTools.PropertyItem(
                                                                null,
                                                                "이름 없음",
                                                                PropertyDataType.STRING,
                                                                List.of("정비중"))),
                                                Map.of()),
                                        AgentResultCategory.FOUND,
                                        List.of(),
                                        null));

        assertThat(reply).containsEntry("resultCategory", "FOUND");
        assertThat(session.filteredValues()).isEmpty();
    }

    @Test
    void collectsOnlyFromObjectListAndObjectSetStart() {
        // 관계 따라가기·목표 유형 찾기의 조건은 대상 쪽 속성("대기", "진행")이라 물은 상태와 비교하지 않는다(2026-11-16 리뷰).
        for (String toolCode : List.of("OBJECT_SET_SEARCH_AROUND", "OBJECT_SET_FIND")) {
            list(
                    toolCode,
                    Map.of("status", List.of("고장", "정비중")),
                    new AgentQuerySession.Outcome(
                            Map.of("count", 2, "properties", EQUIPMENT_PROPERTIES),
                            AgentResultCategory.FOUND,
                            List.of(),
                            null));
        }

        assertThat(session.filteredValues()).isEmpty();
        assertThat(session.storedValues()).isEmpty();
    }

    @Test
    void recordsEachCallInOrder() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_EXECUTIONS",
                        "실행 이력",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () ->
                                new AgentQuerySession.Outcome(
                                        List.of("row"),
                                        AgentResultCategory.FOUND,
                                        List.of(Map.of("objectId", 301L)),
                                        null));
        session.run(
                "OBJECT_EXECUTIONS",
                "실행 이력",
                Map.of("objectId", 302L),
                "object_instance",
                302L,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.NO_DATA, List.of(), null));

        assertThat(reply).containsEntry("resultCategory", "FOUND").containsKey("data");
        assertThat(session.results())
                .extracting(AgentQueryResult::resultCategory)
                .containsExactly(AgentResultCategory.FOUND, AgentResultCategory.NO_DATA);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        anyMap(),
                        eq("object_instance"),
                        eq(301L),
                        any(),
                        eq(CLOCK.instant()));
        verify(recorder).record(eq(ASKER), eq(11L), eq(2), anyMap(), any(), eq(302L), any(), any());
    }

    @Test
    void reportsFailedQueryWithoutCallingItNoData() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_EXECUTIONS",
                        "실행 이력",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () -> {
                            throw new IllegalStateException("DB 시간 초과");
                        });

        assertThat(reply).containsEntry("error", AgentQuerySession.QUERY_FAILED);
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.resultCategory()).isNull();
        assertThat(result.toMap()).containsEntry("status", "FAILED");
    }

    @Test
    void refusesToFinishWhenQueryWasNotRecorded() {
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());

        assertThatThrownBy(
                        () ->
                                session.run(
                                        "OBJECT_EXECUTIONS",
                                        "실행 이력",
                                        Map.of(),
                                        null,
                                        null,
                                        () ->
                                                new AgentQuerySession.Outcome(
                                                        List.of(),
                                                        AgentResultCategory.NO_DATA,
                                                        List.of(),
                                                        null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(session::requireRecorded).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tellsAiTheDataAsOfOfEachResult() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_DETAIL",
                        "설비 상세",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () ->
                                new AgentQuerySession.Outcome(
                                        Map.of("code", "PR-03"),
                                        AgentResultCategory.FOUND,
                                        List.of(Map.of("objectId", 301L)),
                                        Instant.parse("2026-11-09T00:40:00Z")));

        // 재배정 계획의 기준 시점을 조회한 데이터의 시점으로 채울 수 있게 AI에게도 알려 준다(160).
        // 답변에 한국 시간으로 말하게 같은 순간을 한국 시간 오프셋으로 적는다(145).
        assertThat(reply).containsEntry("dataAsOf", "2026-11-09T09:40+09:00");
    }

    @Test
    void reportsWhetherEveryQueryWasRecorded() {
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());

        assertThat(session.allRecorded()).isTrue();
        assertThatThrownBy(
                () ->
                        session.run(
                                "OBJECT_EXECUTIONS",
                                "실행 이력",
                                Map.of("objectId", 301L),
                                "object_instance",
                                301L,
                                () ->
                                        new AgentQuerySession.Outcome(
                                                List.of(),
                                                AgentResultCategory.NO_DATA,
                                                List.of(),
                                                null)));
        assertThat(session.allRecorded()).isFalse();
    }

    @Test
    void numbersServerLookupAndToolCallsTogether() {
        session.lookup(
                "TARGET_SEARCH",
                "대상 찾기",
                Map.of("q", "PR-03"),
                () ->
                        new AgentQuerySession.Outcome(
                                null, AgentResultCategory.FOUND, List.of(), null));
        session.run(
                "OBJECT_EXECUTIONS",
                "실행 이력",
                Map.of("objectId", 301L),
                "object_instance",
                301L,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.NO_DATA, List.of(), null));

        // 서버 조회도 같은 순번으로 기록하지만, 정보별 결과에는 사용자가 물은 도구 조회만 남는다(147).
        verify(recorder).record(eq(ASKER), eq(11L), eq(1), anyMap(), any(), any(), any(), any());
        verify(recorder).record(eq(ASKER), eq(11L), eq(2), anyMap(), any(), eq(301L), any(), any());
        assertThat(session.results())
                .extracting(AgentQueryResult::toolCode)
                .containsExactly("OBJECT_EXECUTIONS");
    }

    @Test
    void recordsFailedLookupAndStopsAnswer() {
        assertThatThrownBy(
                        () ->
                                session.lookup(
                                        "TARGET_SEARCH",
                                        "대상 찾기",
                                        Map.of("q", "PR-03"),
                                        () -> {
                                            throw new IllegalStateException("DB 오류");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 오류");

        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        anyMap(),
                        any(),
                        any(),
                        recorded.capture(),
                        any());
        assertThat(recorded.getValue().succeeded()).isFalse();
        assertThat(session.results()).isEmpty();
    }

    @Test
    void allowsObjectsFoundInThisAnswersResultsButNotInventedOnes() {
        session.allowObjects(List.of(301L));

        session.run(
                "OBJECT_SEARCH",
                "객체 검색",
                Map.of("q", "금형"),
                null,
                null,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", 415L)),
                                null));

        // 검색 결과에 나온 객체는 조회하고, 결과에 없던 ID는 계속 막는다(326).
        assertThat(detail(415L)).containsEntry("resultCategory", "FOUND");
        assertThat(detail(0L)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
    }

    @Test
    void stopsObjectLookupsAtTheLimitAndRecordsTheRefusal() {
        session.allowObjects(List.of(301L));

        for (int i = 0; i < AgentQuerySession.MAX_OBJECT_LOOKUPS; i++) {
            assertThat(detail(301L)).containsEntry("resultCategory", "FOUND");
        }

        // 한도를 넘으면 조회하지 않고, AI가 다른 방법을 고르거나 일부만 확인했다고 말하게 이유를 돌려준다. 데이터에 닿지 않았으므로 results에는
        // 넣지 않지만, 어디서 막혔는지 알 수 있게 거절을 실패 조회로 기록한다.
        assertThat(detail(301L)).isEqualTo(Map.of("error", AgentQuerySession.OBJECT_LOOKUP_LIMIT));
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder, times(AgentQuerySession.MAX_OBJECT_LOOKUPS + 1))
                .record(any(), anyLong(), anyInt(), any(), any(), any(), recorded.capture(), any());
        assertThat(recorded.getValue().succeeded()).isFalse();
        assertThat(recorded.getValue().errorMessage())
                .isEqualTo(AgentQuerySession.OBJECT_LOOKUP_LIMIT);
        assertThat(session.results()).hasSize(AgentQuerySession.MAX_OBJECT_LOOKUPS);
        // 객체 하나가 대상이 아닌 조회(검색·목록)는 한도와 관계없다.
        assertThat(
                        session.run(
                                "OBJECT_SEARCH",
                                "객체 검색",
                                Map.of("q", "설비"),
                                null,
                                null,
                                () ->
                                        new AgentQuerySession.Outcome(
                                                List.of(),
                                                AgentResultCategory.FOUND,
                                                List.of(),
                                                null)))
                .containsEntry("resultCategory", "FOUND");
    }

    /** 대상이 아닌 객체는 조회하지 않지만, 어디서 막혔는지 알 수 있게 거절 이유를 실패 조회로 기록한다. 답변의 results에는 넣지 않는다. */
    @Test
    void recordsRefusalOfObjectsOutsideThisAnswer() {
        session.allowObjects(List.of(301L));

        assertThat(detail(999L)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));

        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        anyMap(),
                        eq(AgentQuerySession.OBJECT_INSTANCE),
                        eq(999L),
                        recorded.capture(),
                        any());
        assertThat(recorded.getValue().succeeded()).isFalse();
        assertThat(recorded.getValue().errorMessage()).isEqualTo(AgentQuerySession.NOT_TARGET);
        assertThat(session.results()).isEmpty();
    }

    /** AI의 도구 호출은 답변마다 한도까지 센다. 넘은 호출은 거절을 기록하고 지금까지 결과로 답하라고 돌려준다. */
    @Test
    void refusesToolCallsOverTheLimit() {
        for (int i = 0; i < AgentQuerySession.MAX_TOOL_CALLS; i++) {
            assertThat(session.countToolCall("OBJECT_SEARCH", Map.of())).isNull();
        }

        assertThat(session.countToolCall("OBJECT_SEARCH", Map.of("toolInput", "{}")))
                .isEqualTo(Map.of("error", AgentQuerySession.TOOL_CALL_LIMIT));
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(any(), anyLong(), anyInt(), any(), any(), any(), recorded.capture(), any());
        assertThat(recorded.getValue().toolCode()).isEqualTo("OBJECT_SEARCH");
        assertThat(recorded.getValue().label()).isEqualTo(AgentQuerySession.TOOL_CALL_LIMIT_LABEL);
        assertThat(recorded.getValue().errorMessage()).isEqualTo(AgentQuerySession.TOOL_CALL_LIMIT);
        assertThat(session.results()).isEmpty();
    }

    @Test
    void doesNotCountRefusedObjectsTowardTheLimit() {
        session.allowObjects(List.of(301L));

        for (int i = 0; i < AgentQuerySession.MAX_OBJECT_LOOKUPS; i++) {
            assertThat(detail(0L)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
        }

        assertThat(detail(301L)).containsEntry("resultCategory", "FOUND");
    }

    private Map<String, Object> detail(long objectId) {
        return session.run(
                "OBJECT_DETAIL",
                "객체 상세",
                Map.of("objectId", objectId),
                "object_instance",
                objectId,
                () ->
                        new AgentQuerySession.Outcome(
                                Map.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", objectId)),
                                null));
    }
}

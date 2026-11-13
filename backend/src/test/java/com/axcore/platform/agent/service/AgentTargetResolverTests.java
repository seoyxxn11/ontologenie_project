package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

class AgentTargetResolverTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneOffset.UTC);

    private final ObjectSearchQuery objects = mock(ObjectSearchQuery.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session = new AgentQuerySession(ASKER, 11L, recorder, CLOCK);
    private final AgentTargetResolver resolver = new AgentTargetResolver(objects);

    @Test
    void picksObjectWithSameNameAmongSimilarOnes() {
        searchReturns("PR-03", object(301L, "PR-03"), object(331L, "PR-03A"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-03"), List.of(), session);

        assertThat(resolution.found())
                .containsExactly(new AgentReferenceObject(301L, "PR-03", "설비"));
        assertThat(resolution.issues()).isEmpty();
    }

    @Test
    void picksIdentifierPropertyWithSameValue() {
        searchReturns("PR-03", object(301L, "프레스 3호기", Map.of("code", "PR-03")));

        assertThat(resolver.resolve(List.of("PR-03"), List.of(), session).found())
                .extracting(AgentReferenceObject::id)
                .containsExactly(301L);
    }

    @Test
    void prefersObjectAlreadyInConversationAmongCandidates() {
        searchReturns("3호기", object(301L, "프레스 3호기"), object(309L, "조립 3호기"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(
                        List.of("3호기"), List.of(AgentReferenceObject.idOnly(301L)), session);

        assertThat(resolution.found()).extracting(AgentReferenceObject::id).containsExactly(301L);
    }

    @Test
    void reusesKnownObjectWithSameNameWithoutSearching() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("pr-03"), List.of(pr03), session);

        assertThat(resolution.found()).containsExactly(pr03);
        verifyNoInteractions(objects, recorder);
    }

    @Test
    void asksAgainInsteadOfPickingFirstWhenShownCandidatesShareName() {
        AgentReferenceObject first = new AgentReferenceObject(306L, "PR-03", "설비");
        AgentReferenceObject second = new AgentReferenceObject(406L, "PR-03", "설비");
        searchReturns("PR-03", object(306L, "PR-03"), object(406L, "PR-03"));

        // 이름이 같은 두 후보를 되물었는데 사용자가 같은 이름을 다시 말한 경우
        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-03"), List.of(first, second), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates()).containsExactly(first, second);
    }

    @Test
    void picksShownCandidateByNumber() {
        AgentReferenceObject first = new AgentReferenceObject(306L, "PR-03", "설비");
        AgentReferenceObject second = new AgentReferenceObject(406L, "PR-03", "설비");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("번호 406"), List.of(first, second), session);

        assertThat(resolution.found()).containsExactly(second);
        verifyNoInteractions(objects);
    }

    @Test
    void returnsCandidatesWhenSeveralObjectsMatch() {
        searchReturns("06번");
        searchReturns("06", object(306L, "PR-06"), object(406L, "CNC-06"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(
                        List.of("06번"), List.of(AgentReferenceObject.idOnly(301L)), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues())
                .containsExactly(
                        new AgentTargetResolver.TargetIssue(
                                "06번",
                                List.of(
                                        new AgentReferenceObject(306L, "PR-06", "설비"),
                                        new AgentReferenceObject(406L, "CNC-06", "설비"))));
    }

    @Test
    void reportsTargetThatMatchesNothing() {
        searchReturns("99번");
        searchReturns("99");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("99번"), List.of(), session);

        assertThat(resolution.issues())
                .containsExactly(new AgentTargetResolver.TargetIssue("99번", List.of()));
    }

    @Test
    void showsObjectsWithSameNameFirstWhenSeveralShareIt() {
        searchReturns(
                "PR-06",
                object(201L, "PR-06A"),
                object(202L, "PR-06B"),
                object(203L, "PR-06C"),
                object(204L, "PR-06D"),
                object(205L, "PR-06E"),
                object(306L, "PR-06"),
                object(406L, "PR-06"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-06"), List.of(), session);

        // 이름이 똑같은 두 객체가 비슷한 이름 뒤에 있어도 후보에서 빠지지 않는다.
        assertThat(resolution.issues().getFirst().candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(306L, 406L);
    }

    @Test
    void recordsEachSearchWithoutAddingToAnswerResults() {
        searchReturns("06번");
        searchReturns("06", object(306L, "PR-06"));

        resolver.resolve(List.of("06번"), List.of(), session);

        // 검색할 때마다 질문한 사용자의 권한으로 부르고 기록하지만, 사용자가 물은 정보가 아니므로 정보별 결과에는 넣지 않는다.
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(Map.of("q", "06번")),
                        isNull(),
                        isNull(),
                        recorded.capture(),
                        any());
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(2),
                        eq(Map.of("q", "06")),
                        isNull(),
                        isNull(),
                        recorded.capture(),
                        any());
        assertThat(recorded.getAllValues())
                .extracting(AgentQueryResult::toolCode, AgentQueryResult::resultCategory)
                .containsExactly(
                        tuple(AgentTargetResolver.TARGET_SEARCH, AgentResultCategory.NO_DATA),
                        tuple(AgentTargetResolver.TARGET_SEARCH, AgentResultCategory.FOUND));
        assertThat(session.results()).isEmpty();
    }

    /** 349: 센서 측정은 여러 열 키(설비 코드·측정 시각·항목)의 한 칸만 같다. 키 전체가 P103인 설비를 고른다. */
    @Test
    void picksObjectWholeKeyedByTargetOverOnesSharingOneKeyColumn() {
        searchReturns(
                "P103",
                sourced(2211L, "1라인 3호 프레스", "설비", Map.of("code", "P103")),
                sensorReading(3788L, "2026-08-03T09:00:00", "temp_c"),
                sensorReading(3789L, "2026-08-03T09:00:00", "vibration_mm_s"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("P103"), List.of(), session);

        assertThat(resolution.found())
                .containsExactly(new AgentReferenceObject(2211L, "1라인 3호 프레스", "설비"));
        assertThat(resolution.issues()).isEmpty();
    }

    /** 원천 키 전체가 P103인 객체가 여럿이면 되묻는다. 후보에는 그것들만 보여 주고 센서 측정은 빼낸다. */
    @Test
    void asksAgainWithOnlyWholeKeyedCandidatesWhenSeveralExist() {
        searchReturns(
                "P103",
                sourced(2211L, "1라인 3호 프레스", "설비", Map.of("code", "P103")),
                sensorReading(3788L, "2026-08-03T09:00:00", "temp_c"),
                sourced(5001L, "P103 금형", "금형", Map.of("die_code", "P103")));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("P103"), List.of(), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(2211L, 5001L);
    }

    /** 표시 이름이 같은 객체가 있으면 그것만 똑같은 후보다. 원천 키 한 칸이 같은 객체보다 먼저다. */
    @Test
    void prefersSameDisplayNameOverWholeKey() {
        searchReturns(
                "P103",
                sourced(2211L, "1라인 3호 프레스", "설비", Map.of("code", "P103")),
                sourced(6001L, "P103", "라인 설비", Map.of("line", "2라인"), Map.of("id", "6001")));

        assertThat(resolver.resolve(List.of("P103"), List.of(), session).found())
                .extracting(AgentReferenceObject::id)
                .containsExactly(6001L);
    }

    /** 라인마다 표시 이름이 P103인 설비가 있으면 되묻는다. 후보는 그 설비들만이다(장민화 리뷰 2). */
    @Test
    void asksAgainWithOnlySameDisplayNamedCandidates() {
        searchReturns(
                "P103",
                sourced(6001L, "P103", "설비", Map.of("line", "1라인"), Map.of("id", "6001")),
                sensorReading(3788L, "2026-08-03T09:00:00", "temp_c"),
                sourced(6002L, "P103", "설비", Map.of("line", "2라인"), Map.of("id", "6002")));

        assertThat(
                        resolver.resolve(List.of("P103"), List.of(), session)
                                .issues()
                                .getFirst()
                                .candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(6001L, 6002L);
    }

    /** "이미 다룬 객체가 하나면 그것"은 좁힌 후보 안에서 본다. 좁힌 후보 밖의 센서 측정을 다뤘어도 고르지 않는다. */
    @Test
    void appliesKnownObjectRuleWithinNarrowedCandidates() {
        searchReturns(
                "P103",
                sourced(6001L, "P103", "설비", Map.of("line", "1라인"), Map.of("id", "6001")),
                sourced(6002L, "P103", "설비", Map.of("line", "2라인"), Map.of("id", "6002")),
                sensorReading(3788L, "2026-08-03T09:00:00", "temp_c"));

        assertThat(
                        resolver.resolve(
                                        List.of("P103"),
                                        List.of(AgentReferenceObject.idOnly(6002L)),
                                        session)
                                .found())
                .extracting(AgentReferenceObject::id)
                .containsExactly(6002L);
        assertThat(
                        resolver.resolve(
                                        List.of("P103"),
                                        List.of(AgentReferenceObject.idOnly(3788L)),
                                        session)
                                .found())
                .isEmpty();
    }

    /** 원천 키를 볼 수 없는 사용자에게는 키 값이 가려져(null) 온다. 가린 값으로 고르지 않는다. */
    @Test
    void doesNotPickByMaskedKey() {
        Map<String, Object> masked = new HashMap<>();
        masked.put("emp_no", null);
        searchReturns(
                "P103",
                sourced(7001L, "작업자 7001", "직원", Map.of("line_code", "P103"), masked),
                sensorReading(3788L, "2026-08-03T09:00:00", "temp_c"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("P103"), List.of(), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates())
                .as("가린 키는 2단계에 걸리지 않아 속성 값 단계(3단계) 후보로 되묻는다")
                .extracting(AgentReferenceObject::id)
                .containsExactly(7001L, 3788L);
    }

    /** 372: 대표 속성이 비어 표시 이름이 P101인 설비를 name 속성 값으로 찾는다. 076이 0건일 때만 속성 값으로 다시 찾는다. */
    @Test
    void findsByPropertyValueWhenSearchFindsNothing() {
        String name = "1라인 1호 프레스";
        searchReturns(name);
        propertyValueReturns(
                name, sourced(2201L, "P101", "설비", Map.of("code", "P101", "name", name)));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of(name), List.of(), session);

        assertThat(resolution.found())
                .containsExactly(new AgentReferenceObject(2201L, "P101", "설비"));
        assertThat(resolution.issues()).isEmpty();
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(2),
                        eq(Map.of("q", name, "matchBy", AgentTargetResolver.PROPERTY_VALUE)),
                        isNull(),
                        isNull(),
                        recorded.capture(),
                        any());
        assertThat(recorded.getValue().resultCategory()).isEqualTo(AgentResultCategory.FOUND);
        assertThat(session.results()).isEmpty();
    }

    @Test
    void asksAgainWithCandidatesWhenSeveralObjectsHaveTheValue() {
        String name = "1라인 1호 프레스";
        searchReturns(name);
        propertyValueReturns(
                name,
                sourced(2201L, "P101", "설비", Map.of("code", "P101", "name", name)),
                sourced(5101L, "D101", "금형", Map.of("die_code", "D101", "press_name", name)));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of(name), List.of(), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(2201L, 5101L);
    }

    /** 흔한 값이 여러 유형에 많이 걸려도 찾지 못함으로 끝내지 않고 앞의 후보 5개로 되묻는다(372, 2026-11-12 결정). */
    @Test
    void asksAgainWithFirstCandidatesWhenCommonValueMatchesMany() {
        ObjectSearchResponse[] running = new ObjectSearchResponse[7];
        for (int index = 0; index < running.length; index++) {
            running[index] =
                    sourced(
                            900L + index,
                            "P10" + index,
                            index % 2 == 0 ? "설비" : "작업지시",
                            Map.of("status", "가동"));
        }
        searchReturns("가동");
        propertyValueReturns("가동", running);

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("가동"), List.of(), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(900L, 901L, 902L, 903L, 904L);
    }

    @Test
    void doesNotSearchPropertyValuesWhenSearchFindsCandidates() {
        searchReturns("P101", sourced(2201L, "P101", "설비", Map.of("code", "P101")));

        assertThat(resolver.resolve(List.of("P101"), List.of(), session).found())
                .extracting(AgentReferenceObject::id)
                .containsExactly(2201L);
        verify(objects, never()).findByPropertyValue(any(), any(), anyInt());
    }

    @Test
    void reportsNotFoundWhenNoPropertyHasTheValueEither() {
        searchReturns("없는 설비");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("없는 설비"), List.of(), session);

        assertThat(resolution.issues())
                .containsExactly(new AgentTargetResolver.TargetIssue("없는 설비", List.of()));
        verify(objects).findByPropertyValue(ASKER, DataPurpose.MODEL, "없는 설비", 100);
    }

    private void propertyValueReturns(String value, ObjectSearchResponse... rows) {
        when(objects.findByPropertyValue(ASKER, DataPurpose.MODEL, value, 100))
                .thenReturn(List.of(rows));
    }

    private static ObjectSearchResponse sensorReading(long id, String measuredAt, String metric) {
        return sourced(
                id,
                "P103 / " + measuredAt + " / " + metric,
                "설비 센서 측정",
                Map.of("equipment_code", "P103", "measured_at", measuredAt, "metric", metric));
    }

    private static ObjectSearchResponse sourced(
            long id, String name, String typeName, Map<String, Object> sourceKey) {
        return sourced(id, name, typeName, sourceKey, sourceKey);
    }

    private static ObjectSearchResponse sourced(
            long id,
            String name,
            String typeName,
            Map<String, Object> properties,
            Map<String, Object> sourceKey) {
        return new ObjectSearchResponse(
                id,
                1L,
                typeName,
                name,
                properties,
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                9L,
                sourceKey,
                null,
                null,
                null);
    }

    private void searchReturns(String query, ObjectSearchResponse... rows) {
        when(objects.search(
                        eq(ASKER),
                        eq(DataPurpose.MODEL),
                        eq(new ObjectSearchRequest(query, null, null, 0, 100))))
                .thenReturn(new PageImpl<>(List.of(rows)));
    }

    private static ObjectSearchResponse object(long id, String name) {
        return object(id, name, Map.of());
    }

    private static ObjectSearchResponse object(
            long id, String name, Map<String, Object> properties) {
        return new ObjectSearchResponse(
                id,
                1L,
                "설비",
                name,
                properties,
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                Map.of(),
                null,
                null,
                null);
    }
}

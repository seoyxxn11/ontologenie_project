package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;

/** 조회 도구가 결과에 나온 객체들의 원천 표 기준으로 기준 시점을 채우는지 본다(145). 표별 규칙은 {@link DataAsOfResolverTests}가 본다. */
class AgentToolDataAsOfTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Instant CONFIRMED = Instant.parse("2026-11-11T01:00:00Z");

    private final DataAsOfResolver dataAsOf = mock(DataAsOfResolver.class);
    private final AgentQuerySession session =
            new AgentQuerySession(
                    ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC(), dataAsOf);
    private final ObjectSearchQuery search = mock(ObjectSearchQuery.class);
    private final ObjectDetailQuery details = mock(ObjectDetailQuery.class);
    private final ObjectEvidenceQuery evidence = mock(ObjectEvidenceQuery.class);
    private final AuditExecutionQueryService executions = mock(AuditExecutionQueryService.class);

    @Test
    void searchUsesTablesOfFoundObjectsAndTellsAiInKoreanTime() {
        when(search.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(found(301L), found(302L))));
        when(dataAsOf.forObjects(List.of(301L, 302L))).thenReturn(CONFIRMED);

        Map<String, Object> reply = queryTools().objectSearch("PR", null, null);

        assertThat(session.results().getFirst().dataAsOf()).isEqualTo(CONFIRMED);
        // 답변에 한국 시간으로 말하게 같은 순간을 한국 시간 오프셋으로 알려 준다.
        assertThat(reply).containsEntry("dataAsOf", "2026-11-11T10:00+09:00");
    }

    @Test
    void givesBusinessAsOfOfObjectsToAiInKoreanTime() {
        // 한국 시간 2026-09-30 00:30은 UTC로 전날이다. UTC로 넘기면 AI가 기준일을 09-29로 말한다.
        Instant businessAsOf = Instant.parse("2026-09-29T15:30:00Z");
        when(search.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(found(301L, businessAsOf))));
        when(details.detail(ASKER, DataPurpose.MODEL, 301L)).thenReturn(detail(301L, businessAsOf));

        Map<String, Object> searched = queryTools().objectSearch("PR", null, null);
        Map<String, Object> detailed =
                new AgentObjectDetailTools(session, details).objectDetail(301L);

        AgentQueryTools.ObjectSummary summary =
                (AgentQueryTools.ObjectSummary)
                        ((List<?>) ((Map<?, ?>) searched.get("data")).get("objects")).getFirst();
        assertThat(summary.dataAsOf()).isEqualTo("2026-09-30T00:30+09:00");
        assertThat(((AgentObjectDetailTools.ObjectDetail) detailed.get("data")).dataAsOf())
                .isEqualTo("2026-09-30T00:30+09:00");
    }

    @Test
    void searchWithoutResultsHasNoDataAsOf() {
        when(search.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        queryTools().objectSearch("없는 설비", null, null);

        // 어느 표를 봤는지 객체로 알 수 없어 비운다.
        assertThat(session.results().getFirst().dataAsOf()).isNull();
    }

    @Test
    void detailUsesTableOfThatObject() {
        when(details.detail(ASKER, DataPurpose.MODEL, 301L)).thenReturn(detail(301L));
        when(dataAsOf.forObjects(List.of(301L))).thenReturn(CONFIRMED);

        new AgentObjectDetailTools(session, details).objectDetail(301L);

        assertThat(session.results().getFirst().dataAsOf()).isEqualTo(CONFIRMED);
    }

    @Test
    void linksUseTablesOfBothEndsAndUnlinkedUsesBaseObjectOnly() {
        when(details.links(any(), any(), anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(link(91L, 401L))))
                .thenReturn(new PageImpl<>(List.of()));
        when(dataAsOf.forObjects(any())).thenReturn(CONFIRMED);
        AgentObjectDetailTools tools = new AgentObjectDetailTools(session, details);

        tools.objectLinks(301L, null, null, null);
        tools.objectLinks(301L, null, null, null);

        verify(dataAsOf).forObjects(Set.of(301L, 401L));
        verify(dataAsOf).forObjects(Set.of(301L));
        assertThat(session.results()).allMatch(result -> CONFIRMED.equals(result.dataAsOf()));
    }

    @Test
    void evidenceUsesBothEndsOfTheLink() {
        when(evidence.linkEvidence(ASKER, DataPurpose.MODEL, 301L, 91L))
                .thenReturn(
                        new ObjectLinkEvidenceResponse(
                                Map.of(), null, "일치 값", null, null, null, List.of(), false));
        when(dataAsOf.forLink(91L)).thenReturn(CONFIRMED);

        new AgentObjectEvidenceTools(session, evidence).linkEvidence(301L, 91L);

        assertThat(session.results().getFirst().dataAsOf()).isEqualTo(CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotResolveDataAsOfWhenEvidenceIsRestrictedOrAbsent(boolean restricted) {
        when(evidence.linkEvidence(ASKER, DataPurpose.MODEL, 301L, 91L))
                .thenReturn(
                        new ObjectLinkEvidenceResponse(
                                null, null, null, null, null, null, List.of(), restricted));
        when(dataAsOf.forLink(91L)).thenReturn(CONFIRMED);

        var reply = new AgentObjectEvidenceTools(session, evidence).linkEvidence(301L, 91L);

        assertThat(reply)
                .containsEntry("resultCategory", restricted ? "DENIED" : "NO_DATA")
                .containsEntry("dataAsOf", null);
        assertThat(session.results().getFirst().dataAsOf()).isNull();
        verify(dataAsOf, never()).forLink(anyLong());
    }

    @Test
    void executionHistoryStaysWithoutDataAsOf() {
        when(executions.objectExecutions(any(), anyLong(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        queryTools().objectExecutions(301L, null, null);

        // 플랫폼이 직접 남긴 기록이라 원천 표가 없다(정책 문서 4.2).
        assertThat(session.results().getFirst().dataAsOf()).isNull();
        verify(dataAsOf, never()).forObjects(any());
    }

    @Test
    void keepsResultWhenDataAsOfCannotBeRead() {
        when(details.detail(ASKER, DataPurpose.MODEL, 301L)).thenReturn(detail(301L));
        when(dataAsOf.forObjects(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));

        Map<String, Object> reply = new AgentObjectDetailTools(session, details).objectDetail(301L);

        // 기준 시점만 비우고 조회 결과는 그대로 쓴다.
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        assertThat(session.results().getFirst().succeeded()).isTrue();
        assertThat(session.results().getFirst().dataAsOf()).isNull();
    }

    private AgentQueryTools queryTools() {
        return new AgentQueryTools(session, executions, search);
    }

    private static ObjectSearchResponse found(long id) {
        return found(id, null);
    }

    private static ObjectSearchResponse found(long id, Instant businessAsOf) {
        return new ObjectSearchResponse(
                id,
                3L,
                "설비",
                "PR-" + id,
                Map.of(),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                1L,
                Map.of("id", id),
                null,
                null,
                businessAsOf);
    }

    private static ObjectDetailResponse detail(long id) {
        return detail(id, null);
    }

    private static ObjectDetailResponse detail(long id, Instant businessAsOf) {
        return new ObjectDetailResponse(
                id,
                3L,
                "설비",
                "PR-" + id,
                Map.of(),
                Map.of(),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                1L,
                null,
                null,
                null,
                null,
                null,
                businessAsOf);
    }

    private static ObjectLinkResponse link(long linkId, long relatedId) {
        return new ObjectLinkResponse(
                linkId,
                12L,
                "현재 배정 설비",
                relatedId,
                301L,
                new ObjectLinkResponse.RelatedObject(relatedId, "WO-" + relatedId, 5L, "작업지시"),
                ObjectLinkResponse.Origin.RULE,
                "ACTIVE",
                3L,
                null,
                ObjectLinkResponse.ExpressionKind.OBSERVED,
                null);
    }
}

package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.service.EquipmentCapabilityComparer.Comparison;
import com.axcore.platform.agent.service.EquipmentCapabilityComparer.Result;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentEquipmentCapabilityToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Instant NOW = Instant.parse("2026-10-30T05:00:00Z");

    private final EquipmentCapabilityComparer comparer = mock(EquipmentCapabilityComparer.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final DataAsOfResolver dataAsOf = mock(DataAsOfResolver.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.fixed(NOW, ZoneOffset.UTC), dataAsOf);
    private final AgentEquipmentCapabilityTools tools =
            new AgentEquipmentCapabilityTools(session, comparer);

    /** 공정 순서를 정규화해 넘기고, 읽은 객체를 출처로, 조회 기록(AGENT_QUERY)으로 남긴다. */
    @Test
    void comparesWithNormalizedSequencesAndRecordsReadObjects() {
        Result result =
                new Result(
                        "JB2610-0141",
                        "BRK-210R",
                        "진행",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        when(comparer.compare(eq(ASKER), anyLong(), any(), any()))
                .thenReturn(
                        new Comparison(
                                AgentResultCategory.FOUND,
                                result,
                                new LinkedHashSet<>(List.of(141L, 2210L))));
        when(dataAsOf.forObjects(Set.of(141L, 2210L))).thenReturn(NOW);

        Map<String, Object> reply =
                tools.compareEquipmentCapability(141L, List.of(2210L), List.of("20.0", "30"));

        verify(comparer).compare(ASKER, 141L, List.of(2210L), Set.of("20", "30"));
        assertThat(reply).containsEntry("resultCategory", "FOUND").containsEntry("data", result);
        AgentQueryResult recorded = session.results().getFirst();
        assertThat(recorded.toolCode())
                .isEqualTo(AgentEquipmentCapabilityTools.COMPARE_EQUIPMENT_CAPABILITY);
        assertThat(recorded.sources())
                .containsExactly(Map.of("objectId", 141L), Map.of("objectId", 2210L));
        assertThat(session.assignedEquipment(141L)).as("비교한 작업지시는 기록한다").isEmpty();
        assertThat(session.assignedEquipment(999L)).isNull();
    }

    /** 비교한 공정이 배정된 설비를 이번 답변에 남긴다. 재배정 계획 저장에서 정지 설비를 확인한다. */
    @Test
    void remembersEquipmentTheComparedOperationsAreOn() {
        Result result =
                new Result(
                        "JB2610-0141",
                        "BRK-210R",
                        "진행",
                        List.of(
                                new EquipmentCapabilityComparer.OperationRequirement(
                                        "20", "중단", "P103", 2212L, true, "PRS", null),
                                new EquipmentCapabilityComparer.OperationRequirement(
                                        "30", "중단", "P103", 2212L, true, "PRS", null),
                                new EquipmentCapabilityComparer.OperationRequirement(
                                        "40", "대기", null, null, true, "INS", null)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        when(comparer.compare(eq(ASKER), anyLong(), any(), any()))
                .thenReturn(
                        new Comparison(
                                AgentResultCategory.FOUND,
                                result,
                                new LinkedHashSet<>(List.of(141L))));

        tools.compareEquipmentCapability(141L, List.of(2210L), null);

        assertThat(session.assignedEquipment(141L)).containsExactly(2212L);
    }

    /** 후보 설비를 비우면 서버가 찾게 빈 목록으로 비교기를 부른다(418). */
    @Test
    void letsServerFindCandidatesWhenEquipmentIsEmpty() {
        Result result =
                new Result(
                        "JB2610-0141",
                        "BRK-210R",
                        "진행",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        when(comparer.compare(eq(ASKER), anyLong(), any(), any()))
                .thenReturn(
                        new Comparison(
                                AgentResultCategory.FOUND,
                                result,
                                new LinkedHashSet<>(List.of(141L))));

        tools.compareEquipmentCapability(141L, null, null);
        tools.compareEquipmentCapability(141L, List.of(), null);

        verify(comparer, org.mockito.Mockito.times(2)).compare(ASKER, 141L, List.of(), Set.of());
    }

    /** 이번 답변의 대상이 아닌 객체면 비교하지 않는다. 빈 값·중복은 비교하지 않고 실패로 돌려준다. */
    @Test
    void rejectsNonTargetsAndBadInput() {
        session.allowObjects(List.of(141L, 2210L));

        assertThat(tools.compareEquipmentCapability(141L, List.of(9999L), null))
                .containsEntry("error", AgentQuerySession.NOT_TARGET);
        assertThat(tools.compareEquipmentCapability(141L, Arrays.asList(2210L, null), null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("빈 값"));
        assertThat(tools.compareEquipmentCapability(141L, List.of(2210L, 2210L), null))
                .hasEntrySatisfying("error", error -> assertThat((String) error).contains("두 번"));
        verifyNoInteractions(comparer);
    }
}

package com.axcore.platform.agent.service;

import static com.axcore.platform.agent.service.ObjectPathPlannerTests.DOWNTIME;
import static com.axcore.platform.agent.service.ObjectPathPlannerTests.EQUIPMENT;
import static com.axcore.platform.agent.service.ObjectPathPlannerTests.OPERATION;
import static com.axcore.platform.agent.service.ObjectPathPlannerTests.SALES_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.service.ObjectPathPlanner.ObjectPaths;
import com.axcore.platform.agent.service.ObjectPathPlanner.PathCandidate;
import com.axcore.platform.agent.service.ObjectPathPlanner.PathStep;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.service.ModelSummaryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentObjectPathToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final ModelSummaryService models = mock(ModelSummaryService.class);
    private final DataAccessPolicy policy = mock(DataAccessPolicy.class);
    private final DataAccess access = mock(DataAccess.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.systemUTC());
    private final AgentObjectPathTools tools =
            new AgentObjectPathTools(session, new ObjectPathPlanner(models, policy));

    @Test
    void returnsPathCandidatesInAskersScopeAndRecordsCall() {
        givenModel(ObjectPathPlannerTests.s4Model());

        Map<String, Object> reply = tools.objectPaths("설비", "수주");

        verify(models).summarize(access);
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        ObjectPaths data = (ObjectPaths) reply.get("data");
        assertThat(data.fromTypeId()).isEqualTo(EQUIPMENT);
        assertThat(data.fromTypeName()).isEqualTo("설비");
        assertThat(data.toTypeId()).isEqualTo(SALES_ORDER);
        assertThat(data.toTypeName()).isEqualTo("수주");
        assertThat(data.candidates()).hasSize(2);
        assertThat(data.truncated()).isFalse();
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentObjectPathTools.OBJECT_PATHS);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.sources()).isEmpty();
        assertThat(result.dataAsOf()).isNull();
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(Map.of("fromType", "설비", "toType", "수주")),
                        isNull(),
                        isNull(),
                        eq(result),
                        any(Instant.class));
    }

    @Test
    void givesSameCandidatesForSameInput() {
        givenModel(ObjectPathPlannerTests.s4Model());

        Object first = tools.objectPaths("설비", "수주").get("data");
        Object second = tools.objectPaths("설비", "수주").get("data");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void acceptsTypeCodeOrIdInsteadOfName() {
        givenModel(ObjectPathPlannerTests.s4Model());

        Object byName = tools.objectPaths("설비", "수주").get("data");

        assertThat(tools.objectPaths("equipment", String.valueOf(SALES_ORDER)).get("data"))
                .isEqualTo(byName);
    }

    @Test
    void leavesOutPathsThroughTypesTheAskerCannotSee() {
        // 모델 요약은 볼 수 없는 유형(작업지시 공정)과 그 유형에 걸린 관계 유형을 빼고 준다.
        ModelSummaryResponse full = ObjectPathPlannerTests.s4Model();
        givenModel(
                ObjectPathPlannerTests.model(
                        full.objectTypes().stream().filter(type -> type.id() != OPERATION).toList(),
                        full.linkTypes().stream()
                                .filter(
                                        link ->
                                                link.sourceTypeId() != OPERATION
                                                        && link.targetTypeId() != OPERATION)
                                .toList()));

        ObjectPaths data = (ObjectPaths) tools.objectPaths("설비", "수주").get("data");

        assertThat(data.candidates())
                .singleElement()
                .extracting(PathCandidate::steps)
                .satisfies(
                        steps ->
                                assertThat(steps)
                                        .extracting(PathStep::toTypeId)
                                        .doesNotContain(OPERATION)
                                        .contains(DOWNTIME));
    }

    @Test
    void treatsInvisibleTypeAsUnknownInput() {
        ModelSummaryResponse full = ObjectPathPlannerTests.s4Model();
        givenModel(
                ObjectPathPlannerTests.model(
                        full.objectTypes().stream().filter(type -> type.id() != OPERATION).toList(),
                        List.of()));

        Map<String, Object> reply = tools.objectPaths("설비", "작업지시 공정");

        assertThat(reply.get("error"))
                .asString()
                .contains("입력 오류")
                .contains("볼 수 있는 객체 유형에 '작업지시 공정'가 없습니다");
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }

    @Test
    void asksForTypeIdWhenNameMatchesSeveralTypes() {
        ModelSummaryResponse full = ObjectPathPlannerTests.s4Model();
        List<ObjectTypeSummary> types = new ArrayList<>(full.objectTypes());
        types.add(ObjectPathPlannerTests.type(9L, "설비", "설비 코드가 같은 유형"));
        givenModel(ObjectPathPlannerTests.model(types, full.linkTypes()));

        Map<String, Object> reply = tools.objectPaths("설비", "수주");

        assertThat(reply.get("error"))
                .asString()
                .contains("입력 오류")
                .contains("'설비'에 맞는 객체 유형이 여럿입니다");
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }

    @Test
    void rejectsMissingType() {
        givenModel(ObjectPathPlannerTests.s4Model());

        assertThat(tools.objectPaths(null, "수주").get("error"))
                .asString()
                .contains("fromType가 없습니다");
        assertThat(tools.objectPaths("설비", "  ").get("error")).asString().contains("toType가 없습니다");
    }

    @Test
    void rejectsSameStartAndEndType() {
        givenModel(ObjectPathPlannerTests.s4Model());

        Map<String, Object> reply = tools.objectPaths("설비", "EQUIPMENT");

        assertThat(reply.get("error")).asString().contains("출발 유형과 도착 유형이 같습니다");
    }

    @Test
    void reportsNoDataWhenTypesAreNotConnected() {
        ModelSummaryResponse full = ObjectPathPlannerTests.s4Model();
        givenModel(ObjectPathPlannerTests.model(full.objectTypes(), List.of()));

        Map<String, Object> reply = tools.objectPaths("설비", "수주");

        assertThat(reply).containsEntry("resultCategory", "NO_DATA");
        assertThat(((ObjectPaths) reply.get("data")).candidates()).isEmpty();
        assertThat(session.results().getFirst().resultCategory())
                .isEqualTo(AgentResultCategory.NO_DATA);
    }

    private void givenModel(ModelSummaryResponse model) {
        when(policy.forModel(ASKER)).thenReturn(access);
        when(models.summarize(access)).thenReturn(model);
    }
}

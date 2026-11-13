package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.axcore.platform.agent.service.ObjectPathPlanner.FanOut;
import com.axcore.platform.agent.service.ObjectPathPlanner.PathCandidate;
import com.axcore.platform.agent.service.ObjectPathPlanner.PathStep;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.service.ModelSummaryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObjectPathPlannerTests {
    static final long EQUIPMENT = 1L;
    static final long OPERATION = 2L;
    static final long WORK_ORDER = 3L;
    static final long SALES_ORDER = 4L;
    static final long DOWNTIME = 5L;

    /** S4 시연 온톨로지의 일부. 설비에서 수주로 가는 길은 작업지시 공정과 비가동 두 갈래다. */
    static ModelSummaryResponse s4Model() {
        return model(
                List.of(
                        type(EQUIPMENT, "EQUIPMENT", "설비"),
                        type(OPERATION, "WORK_ORDER_OPERATION", "작업지시 공정"),
                        type(WORK_ORDER, "WORK_ORDER", "작업지시"),
                        type(SALES_ORDER, "SALES_ORDER", "수주"),
                        type(DOWNTIME, "DOWNTIME", "비가동")),
                List.of(
                        link(10L, "work_order_operation_equipment_code", OPERATION, EQUIPMENT),
                        link(11L, "work_order_operation_wo_no", OPERATION, WORK_ORDER),
                        link(12L, "work_order_so_ref", WORK_ORDER, SALES_ORDER),
                        link(13L, "downtime_equipment_code", DOWNTIME, EQUIPMENT),
                        link(14L, "downtime_wo_no", DOWNTIME, WORK_ORDER)));
    }

    @Test
    void findsShortestPathsWithDirectionsAndFanOut() {
        List<PathCandidate> candidates =
                ObjectPathPlanner.candidates(s4Model(), EQUIPMENT, SALES_ORDER);

        assertThat(candidates)
                .extracting(ObjectPathPlannerTests::linkIds)
                .containsExactly(List.of(10L, 11L, 12L), List.of(13L, 14L, 12L));
        PathCandidate viaOperation = candidates.getFirst();
        assertThat(viaOperation.hops()).isEqualTo(3);
        assertThat(viaOperation.steps())
                .extracting(PathStep::direction)
                .containsExactly("IN", "OUT", "OUT");
        assertThat(viaOperation.steps().getFirst())
                .isEqualTo(
                        new PathStep(
                                10L,
                                "work_order_operation_equipment_code",
                                "work_order_operation_equipment_code 이름",
                                "IN",
                                EQUIPMENT,
                                "설비",
                                OPERATION,
                                "작업지시 공정",
                                LinkCardinality.MANY_TO_ONE,
                                FanOut.MANY));
        // 설비 하나에 공정은 여럿이지만, 공정에서 작업지시·수주로는 하나로 간다.
        assertThat(viaOperation.steps())
                .extracting(PathStep::fanOut)
                .containsExactly(FanOut.MANY, FanOut.ONE, FanOut.ONE);
        assertThat(viaOperation.fanOut()).isEqualTo(FanOut.MANY);
    }

    @Test
    void returnsSameCandidatesForSameModelRegardlessOfOrder() {
        ModelSummaryResponse model = s4Model();
        List<PathCandidate> first = ObjectPathPlanner.candidates(model, EQUIPMENT, SALES_ORDER);

        List<ObjectTypeSummary> types = new ArrayList<>(model.objectTypes());
        List<LinkTypeSummary> links = new ArrayList<>(model.linkTypes());
        Collections.shuffle(types, new Random(7));
        Collections.shuffle(links, new Random(7));
        Collections.reverse(links);

        assertThat(ObjectPathPlanner.candidates(model, EQUIPMENT, SALES_ORDER)).isEqualTo(first);
        assertThat(ObjectPathPlanner.candidates(model(types, links), EQUIPMENT, SALES_ORDER))
                .isEqualTo(first);
    }

    @Test
    void keepsAtMostFiveCandidatesShortestFirst() {
        List<ObjectTypeSummary> types = new ArrayList<>();
        List<LinkTypeSummary> links = new ArrayList<>();
        types.add(type(1L, "A", "A"));
        types.add(type(2L, "B", "B"));
        links.add(link(100L, "A_B", 1L, 2L));
        for (long i = 1; i <= 6; i++) {
            types.add(type(10L + i, "C" + i, "C" + i));
            links.add(link(300L + i, "C_B_" + i, 10L + i, 2L));
            links.add(link(200L + i, "A_C_" + i, 1L, 10L + i));
        }

        List<PathCandidate> candidates = ObjectPathPlanner.candidates(model(types, links), 1L, 2L);

        assertThat(candidates).extracting(PathCandidate::hops).containsExactly(1, 2, 2, 2, 2);
        assertThat(candidates)
                .extracting(candidate -> candidate.steps().getFirst().linkTypeId())
                .containsExactly(100L, 201L, 202L, 203L, 204L);
    }

    @Test
    void searchesUpToFourHops() {
        assertThat(ObjectPathPlanner.candidates(chain(4), 1L, 5L))
                .singleElement()
                .extracting(PathCandidate::hops)
                .isEqualTo(4);
        assertThat(ObjectPathPlanner.candidates(chain(5), 1L, 6L)).isEmpty();
    }

    @Test
    void neverVisitsSameTypeTwiceInOnePath() {
        ModelSummaryResponse model =
                model(
                        List.of(type(1L, "A", "A"), type(2L, "B", "B"), type(3L, "C", "C")),
                        List.of(
                                link(1L, "A_B", 1L, 2L),
                                link(2L, "B_A", 2L, 1L),
                                link(3L, "B_C", 2L, 3L),
                                link(4L, "B_SELF", 2L, 2L)));

        List<PathCandidate> candidates = ObjectPathPlanner.candidates(model, 1L, 3L);

        assertThat(candidates)
                .extracting(ObjectPathPlannerTests::linkIds)
                .containsExactly(List.of(1L, 3L), List.of(2L, 3L));
        for (PathCandidate candidate : candidates) {
            Set<Long> types = new HashSet<>();
            types.add(candidate.steps().getFirst().fromTypeId());
            candidate.steps().forEach(step -> assertThat(types.add(step.toTypeId())).isTrue());
        }
    }

    @Test
    void skipsLinkTypesWhoseEndTypeIsNotVisible() {
        // 모델 요약에 작업지시 공정 유형이 없으면 그 유형을 지나는 관계 유형이 남아 있어도 쓰지 않는다.
        ModelSummaryResponse model = s4Model();
        ModelSummaryResponse withoutOperation =
                model(
                        model.objectTypes().stream()
                                .filter(type -> type.id() != OPERATION)
                                .toList(),
                        model.linkTypes());

        assertThat(ObjectPathPlanner.candidates(withoutOperation, EQUIPMENT, SALES_ORDER))
                .extracting(ObjectPathPlannerTests::linkIds)
                .containsExactly(List.of(13L, 14L, 12L));
    }

    @Test
    void createsPlannerOnlyWhenEnabled() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withBean(ModelSummaryService.class, () -> mock(ModelSummaryService.class))
                        .withBean(DataAccessPolicy.class, () -> mock(DataAccessPolicy.class))
                        .withUserConfiguration(ObjectPathPlanner.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectPathPlanner.class));
        runner.withPropertyValues("axcore.agent.object-paths.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ObjectPathPlanner.class));
        runner.withPropertyValues("axcore.agent.object-paths.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ObjectPathPlanner.class));
    }

    private static List<Long> linkIds(PathCandidate candidate) {
        return candidate.steps().stream().map(PathStep::linkTypeId).toList();
    }

    /** 1 → 2 → … → length+1 으로 이어진 한 줄 모델. */
    private static ModelSummaryResponse chain(int length) {
        List<ObjectTypeSummary> types = new ArrayList<>();
        List<LinkTypeSummary> links = new ArrayList<>();
        for (long id = 1; id <= length + 1; id++) {
            types.add(type(id, "T" + id, "T" + id));
            if (id > 1) {
                links.add(link(id, "L" + id, id - 1, id));
            }
        }
        return model(types, links);
    }

    static ModelSummaryResponse model(List<ObjectTypeSummary> types, List<LinkTypeSummary> links) {
        return new ModelSummaryResponse(types, List.of(), links, List.of());
    }

    static ObjectTypeSummary type(long id, String code, String name) {
        return new ObjectTypeSummary(id, code, name, null);
    }

    static LinkTypeSummary link(long id, String code, long source, long target) {
        return new LinkTypeSummary(
                id, code, code + " 이름", source, target, LinkCardinality.MANY_TO_ONE, null);
    }
}

package com.axcore.platform.agent.service;

import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.service.ModelSummaryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 객체 유형 사이의 경로 후보를 찾는다(S15P31C106-381). 객체 유형을 노드, 관계 유형을 엣지로 보고 가까운 경로부터 BFS로 찾는다. 객체 데이터는 읽지 않는다.
 * 홉마다 AI가 관계를 고르면 엉뚱한 관계로 빠지므로(S4) 갈 수 있는 경로를 먼저 알려 준다.
 *
 * <p>질문한 사용자의 모델 요약(043)만 쓰므로 볼 수 없는 유형과 그 유형에 걸린 관계 유형은 경로에 나오지 않는다. 이 빈이 있을 때만 AI에게 도구를 준다.
 */
@Component
@ConditionalOnProperty(prefix = "axcore.agent.object-paths", name = "enabled", havingValue = "true")
public class ObjectPathPlanner {
    static final int MAX_HOPS = 4;
    static final int MAX_CANDIDATES = 5;

    private final ModelSummaryService models;
    private final DataAccessPolicy dataAccess;

    public ObjectPathPlanner(ModelSummaryService models, DataAccessPolicy dataAccess) {
        this.models = models;
        this.dataAccess = dataAccess;
    }

    /** 관계 하나를 따라갈 때 한 객체에 연결될 수 있는 상대 수. MANY면 여러 건으로 갈라진다. */
    public enum FanOut {
        ONE,
        MANY
    }

    /** 경로의 한 단계. direction은 관계 조회(OBJECT_LINKS)의 값과 같다. OUT은 관계 유형의 출발 쪽에서 도착 쪽으로, IN은 그 반대로 간다. */
    public record PathStep(
            Long linkTypeId,
            String linkTypeCode,
            String linkTypeName,
            String direction,
            Long fromTypeId,
            String fromTypeName,
            Long toTypeId,
            String toTypeName,
            LinkCardinality cardinality,
            FanOut fanOut) {}

    /** 경로 후보 하나. fanOut은 한 단계라도 MANY면 MANY다. */
    public record PathCandidate(int hops, FanOut fanOut, List<PathStep> steps) {}

    /** truncated는 {@value MAX_HOPS}단계 안에 보낸 후보 말고도 경로가 더 있다는 뜻이다. */
    public record ObjectPaths(
            Long fromTypeId,
            String fromTypeName,
            Long toTypeId,
            String toTypeName,
            List<PathCandidate> candidates,
            boolean truncated) {}

    record PathSearch(List<PathCandidate> candidates, boolean truncated) {}

    /**
     * 유형은 ID, 코드, 이름 중 하나로 받는다. 볼 수 없는 유형은 없는 유형과 같게 다뤄 입력 오류로 돌려준다.
     *
     * @throws AgentQuerySession.InvalidInput 유형을 찾지 못했거나 출발·도착이 같을 때
     */
    ObjectPaths plan(LoginAccount asker, String fromType, String toType) {
        ModelSummaryResponse summary = models.summarize(dataAccess.forModel(asker));
        ObjectTypeSummary from = resolve(summary.objectTypes(), fromType, "fromType");
        ObjectTypeSummary to = resolve(summary.objectTypes(), toType, "toType");
        if (from.id().equals(to.id())) {
            throw new AgentQuerySession.InvalidInput("출발 유형과 도착 유형이 같습니다.", null);
        }
        PathSearch search = search(summary, from.id(), to.id());
        return new ObjectPaths(
                from.id(),
                from.name(),
                to.id(),
                to.name(),
                search.candidates(),
                search.truncated());
    }

    static ObjectTypeSummary resolve(List<ObjectTypeSummary> types, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentQuerySession.InvalidInput(field + "가 없습니다.", null);
        }
        String text = value.strip();
        List<ObjectTypeSummary> matches =
                types.stream()
                        .filter(
                                type ->
                                        String.valueOf(type.id()).equals(text)
                                                || text.equalsIgnoreCase(type.code())
                                                || text.equals(type.name()))
                        .toList();
        if (matches.isEmpty()) {
            List<String> similar = similarTypeNames(types, text);
            String hint =
                    similar.isEmpty()
                            ? ""
                            : " 이름이 비슷한 유형: "
                                    + String.join(", ", similar)
                                    + ". 이 중 하나의 이름이나 ID로 다시 부른다.";
            throw new AgentQuerySession.InvalidInput(
                    "볼 수 있는 객체 유형에 '%s'가 없습니다. 모델 요약의 유형 이름이나 ID를 넣는다.".formatted(text) + hint,
                    null);
        }
        if (matches.size() > 1) {
            throw new AgentQuerySession.InvalidInput(
                    "'%s'에 맞는 객체 유형이 여럿입니다. 유형 ID를 넣는다.".formatted(text), null);
        }
        return matches.getFirst();
    }

    static final int MAX_SIMILAR_TYPES = 5;

    /**
     * 넣은 말을 이름이나 코드에 품은 유형, 또는 이름이 넣은 말 안에 든 유형이다. AI가 "공정"처럼 모델에 없는 유형 이름을 지어내고 멈춘 일이 있었다(GMS R1).
     * 볼 수 있는 유형 안에서만 고르고, 이름 순으로 {@value MAX_SIMILAR_TYPES}개까지다.
     */
    static List<String> similarTypeNames(List<ObjectTypeSummary> types, String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        return types.stream()
                .filter(
                        type ->
                                type.name().contains(text)
                                        || text.contains(type.name())
                                        || (type.code() != null
                                                && type.code()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(lowered)))
                .map(type -> type.name() + "(#" + type.id() + ")")
                .sorted()
                .limit(MAX_SIMILAR_TYPES)
                .toList();
    }

    private record Edge(LinkTypeSummary link, String direction, long from, long to) {}

    /**
     * 짧은 경로부터 최대 {@value MAX_CANDIDATES}개를 돌려준다. 같은 길이에서는 단계별 관계 유형 ID, OUT·IN 순서다. 입력 순서와 관계없이 같은
     * 모델이면 같은 결과가 나온다. 한 경로에서 같은 유형을 두 번 지나지 않는다.
     *
     * <p>같은 길이 안의 순서는 업무 의미와 관계가 없다. 시험 데이터(유형 27개)에서 상대 수가 적은 경로나 거치는 유형의 관계 수가 적은 경로를 앞에 두는 방법도
     * 비교했지만 뜻 없는 경로가 오히려 앞에 오는 쌍이 있어 바꾸지 않았다. 대신 잘렸으면 truncated로 알린다.
     */
    static PathSearch search(ModelSummaryResponse summary, long fromTypeId, long toTypeId) {
        Map<Long, String> typeNames = new HashMap<>();
        summary.objectTypes().forEach(type -> typeNames.put(type.id(), type.name()));
        Map<Long, List<Edge>> edges = new HashMap<>();
        for (LinkTypeSummary link : summary.linkTypes()) {
            long source = link.sourceTypeId();
            long target = link.targetTypeId();
            // 모델 요약은 양 끝을 볼 수 있는 관계 유형만 주지만, 유형 목록에 없는 끝이 있으면 그 관계는 쓰지 않는다.
            if (source == target
                    || !typeNames.containsKey(source)
                    || !typeNames.containsKey(target)) {
                continue;
            }
            edges.computeIfAbsent(source, id -> new ArrayList<>())
                    .add(new Edge(link, "OUT", source, target));
            edges.computeIfAbsent(target, id -> new ArrayList<>())
                    .add(new Edge(link, "IN", target, source));
        }
        Comparator<Edge> order =
                Comparator.<Edge>comparingLong(edge -> edge.link().id())
                        .thenComparing(edge -> edge.direction().equals("IN"));
        edges.values().forEach(list -> list.sort(order));

        List<List<Edge>> found = new ArrayList<>();
        List<List<Edge>> frontier = List.of(List.of());
        for (int hops = 1; hops <= MAX_HOPS && !frontier.isEmpty(); hops++) {
            List<List<Edge>> next = new ArrayList<>();
            for (List<Edge> path : frontier) {
                long end = path.isEmpty() ? fromTypeId : path.getLast().to();
                Set<Long> visited = new HashSet<>();
                visited.add(fromTypeId);
                path.forEach(edge -> visited.add(edge.to()));
                for (Edge edge : edges.getOrDefault(end, List.of())) {
                    if (visited.contains(edge.to())) {
                        continue;
                    }
                    List<Edge> extended = new ArrayList<>(path);
                    extended.add(edge);
                    if (edge.to() == toTypeId) {
                        if (found.size() == MAX_CANDIDATES) {
                            return result(found, typeNames, true);
                        }
                        found.add(extended);
                    } else if (hops < MAX_HOPS) {
                        next.add(extended);
                    }
                }
            }
            frontier = next;
        }
        return result(found, typeNames, false);
    }

    private static PathSearch result(
            List<List<Edge>> found, Map<Long, String> typeNames, boolean truncated) {
        return new PathSearch(
                found.stream().map(steps -> candidate(steps, typeNames)).toList(), truncated);
    }

    private static PathCandidate candidate(List<Edge> edges, Map<Long, String> typeNames) {
        List<PathStep> steps =
                edges.stream()
                        .map(
                                edge -> {
                                    LinkCardinality cardinality = edge.link().cardinality();
                                    return new PathStep(
                                            edge.link().id(),
                                            edge.link().code(),
                                            edge.link().name(),
                                            edge.direction(),
                                            edge.from(),
                                            typeNames.get(edge.from()),
                                            edge.to(),
                                            typeNames.get(edge.to()),
                                            cardinality,
                                            fanOut(cardinality, edge.direction()));
                                })
                        .toList();
        FanOut fanOut =
                steps.stream().anyMatch(step -> step.fanOut() == FanOut.MANY)
                        ? FanOut.MANY
                        : FanOut.ONE;
        return new PathCandidate(steps.size(), fanOut, steps);
    }

    /** 카디널리티가 없으면 여러 건일 수 있다고 본다. */
    private static FanOut fanOut(LinkCardinality cardinality, String direction) {
        if (cardinality == null) {
            return FanOut.MANY;
        }
        boolean one =
                direction.equals("OUT")
                        ? cardinality.destinationIsOne()
                        : cardinality.sourceIsOne();
        return one ? FanOut.ONE : FanOut.MANY;
    }
}

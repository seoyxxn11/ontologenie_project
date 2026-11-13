package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문에 나온 대상 이름·코드를 실제 객체로 찾는다(147). AI에게 맡기지 않고 서버가 질문한 사용자의 권한으로 076 객체 검색을 불러, 볼 수 없는 객체는 찾지 않는다.
 * 076이 0건이면 같은 권한으로 문자 속성 값이 똑같은 객체를 한 번 더 찾는다(372). 하나로 정해지지 않으면 후보를 돌려주어 사용자에게 되묻게 한다. 검색은 조회 기록에
 * 남기지만 정보별 결과에는 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AgentTargetResolver {
    /** AI의 객체 검색 도구(OBJECT_SEARCH)와 실행 이력에서 구별하는 조회 코드(326). */
    static final String TARGET_SEARCH = "TARGET_SEARCH";

    static final String LABEL = "대상 찾기";

    /** 속성 값으로 다시 찾은 조회를 076 검색과 구별하는 기록 값(372). */
    static final String PROPERTY_VALUE = "PROPERTY_VALUE";

    /** 되물을 때 보여 줄 후보 수. */
    static final int MAX_CANDIDATES = 5;

    /** 076은 표시 이름이나 조회 가능한 식별 값이 정확히 일치하는 객체를 먼저 돌려준다. 후보는 허용 최대 건수까지 읽는다. */
    private static final int SEARCH_SIZE = 100;

    /** "06번"처럼 번호에 붙인 말. 그대로 찾아 없으면 떼고 다시 찾는다. */
    private static final String NUMBER_SUFFIX = "번";

    /** 같은 이름 후보를 되물을 때 붙인 구별 번호로 고른 말. 예: 번호 306 */
    private static final Pattern CHOSEN_NUMBER = Pattern.compile("번호\\s*(\\d{1,18})");

    private final ObjectSearchQuery objects;

    /** 하나로 정하지 못한 대상. 후보가 비었으면 찾지 못한 것이다. */
    public record TargetIssue(String target, List<AgentReferenceObject> candidates) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", target);
            map.put("candidates", candidates.stream().map(AgentReferenceObject::toMap).toList());
            return map;
        }
    }

    public record Resolution(List<AgentReferenceObject> found, List<TargetIssue> issues) {}

    /**
     * known은 대화에서 이미 다룬 객체(화면 참고 객체, 이전 답변의 대상, 되물을 때 보여 준 후보)다. 그중 이름이 같은 것이 하나뿐이면 다시 찾지 않고, 되물을 때
     * 보여 준 "번호 N"으로 고르면 그것으로 정한다. 후보가 여럿이어도 그중 하나만 known이면 그것으로 정한다.
     */
    public Resolution resolve(
            List<String> targets, List<AgentReferenceObject> known, AgentQuerySession session) {
        Set<Long> knownIds =
                known.stream().map(AgentReferenceObject::id).collect(Collectors.toSet());
        Map<Long, AgentReferenceObject> found = new LinkedHashMap<>();
        List<TargetIssue> issues = new ArrayList<>();
        for (String target : targets) {
            Optional<AgentReferenceObject> chosen =
                    chosenByNumber(target, known).or(() -> onlyKnownNamed(target, known));
            if (chosen.isPresent()) {
                found.putIfAbsent(chosen.get().id(), chosen.get());
                continue;
            }
            String query = target;
            List<ObjectSearchResponse> candidates = search(query, session);
            if (candidates.isEmpty() && target.endsWith(NUMBER_SUFFIX)) {
                String shorter =
                        target.substring(0, target.length() - NUMBER_SUFFIX.length()).strip();
                if (!shorter.isEmpty()) {
                    query = shorter;
                    candidates = search(query, session);
                }
            }
            if (candidates.isEmpty()) {
                candidates = searchByPropertyValue(target, session);
            }
            Set<String> names = new HashSet<>(List.of(target, query));
            Optional<ObjectSearchResponse> picked = pick(candidates, names, knownIds);
            if (picked.isPresent()) {
                AgentReferenceObject object = AgentReferenceObject.from(picked.get());
                found.putIfAbsent(object.id(), object);
            } else {
                // 이름이 똑같은 객체가 여럿이면 그것들을 후보로 보여 준다.
                List<ObjectSearchResponse> exact = sameNamed(candidates, names);
                issues.add(
                        new TargetIssue(
                                target,
                                (exact.isEmpty() ? candidates : exact)
                                        .stream()
                                                .limit(MAX_CANDIDATES)
                                                .map(AgentReferenceObject::from)
                                                .toList()));
            }
        }
        return new Resolution(List.copyOf(found.values()), issues);
    }

    /**
     * 똑같은 후보({@link #sameNamed}, 단계별로 좁힌 목록)가 하나면 그것, 아니면 그 목록(없으면 검색 결과 전체)에서 이미 대상이던 것이 하나면 그것,
     * 후보가 하나뿐이면 그것을 고른다.
     */
    static Optional<ObjectSearchResponse> pick(
            List<ObjectSearchResponse> candidates, Set<String> names, Set<Long> knownIds) {
        List<ObjectSearchResponse> exact = sameNamed(candidates, names);
        if (exact.size() == 1) {
            return Optional.of(exact.getFirst());
        }
        List<ObjectSearchResponse> pool = exact.isEmpty() ? candidates : exact;
        List<ObjectSearchResponse> known =
                pool.stream().filter(candidate -> knownIds.contains(candidate.id())).toList();
        if (known.size() == 1) {
            return Optional.of(known.getFirst());
        }
        return pool.size() == 1 ? Optional.of(pool.getFirst()) : Optional.empty();
    }

    /** 이름이 같은 객체를 대화에서 이미 하나만 다뤘으면 그것. 같은 이름이 여럿이면 고르지 않는다. */
    private static Optional<AgentReferenceObject> onlyKnownNamed(
            String target, List<AgentReferenceObject> known) {
        List<AgentReferenceObject> named =
                known.stream().filter(object -> target.equalsIgnoreCase(object.name())).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** 되물을 때 같은 이름 후보를 구별하려고 보여 준 "번호 N"으로 골랐으면 그 후보. 이름을 아는 객체(보여 준 후보)만 고른다. */
    private static Optional<AgentReferenceObject> chosenByNumber(
            String target, List<AgentReferenceObject> known) {
        Matcher number = CHOSEN_NUMBER.matcher(target.strip());
        if (!number.matches()) {
            return Optional.empty();
        }
        long id = Long.parseLong(number.group(1));
        return known.stream()
                .filter(object -> object.id() == id && object.name() != null)
                .findFirst();
    }

    /**
     * 이름이 똑같은 후보. 가장 좁은 기준부터 보고, 맞는 것이 있으면 그 기준에서 멈춘다(349). 고르기와 되물을 때 보여 줄 후보 모두 이 목록을 쓴다.
     *
     * <ol>
     *   <li>표시 이름이 같다.
     *   <li>원천 키가 열 하나이고 그 값이 같다. "P103"은 설비(키 code)와 센서 측정(키 equipment_code·measured_at·metric)에
     *       똑같이 걸리지만, 키 전체가 P103인 것은 설비뿐이다. 가린 키 값은 null이라 같다고 보지 않는다.
     *   <li>보이는 속성 값 중 하나가 같다.
     * </ol>
     */
    private static List<ObjectSearchResponse> sameNamed(
            List<ObjectSearchResponse> candidates, Set<String> names) {
        List<ObjectSearchResponse> displayed =
                candidates.stream().filter(candidate -> sameDisplayName(candidate, names)).toList();
        if (!displayed.isEmpty()) {
            return displayed;
        }
        List<ObjectSearchResponse> keyed =
                candidates.stream().filter(candidate -> wholeKey(candidate, names)).toList();
        if (!keyed.isEmpty()) {
            return keyed;
        }
        return candidates.stream().filter(candidate -> sameProperty(candidate, names)).toList();
    }

    private static boolean sameDisplayName(ObjectSearchResponse candidate, Set<String> names) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(candidate.displayName()));
    }

    private static boolean wholeKey(ObjectSearchResponse candidate, Set<String> names) {
        Map<String, Object> key = candidate.sourceKey();
        return key != null
                && key.size() == 1
                && key.values().iterator().next() instanceof String value
                && names.stream().anyMatch(value::equalsIgnoreCase);
    }

    /**
     * 076은 표시 이름과 식별 속성으로 검색하지만 응답에는 어느 속성이 식별 속성인지 없다. 그래서 보이는 속성 값 전체와 비교한다. 숨긴 속성은 응답에서 이미 가려져
     * 있다.
     */
    private static boolean sameProperty(ObjectSearchResponse candidate, Set<String> names) {
        if (candidate.properties() == null) {
            return false;
        }
        return candidate.properties().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(value -> names.stream().anyMatch(value::equalsIgnoreCase));
    }

    private List<ObjectSearchResponse> search(String query, AgentQuerySession session) {
        return lookup(
                Map.of("q", query),
                session,
                () ->
                        objects.search(
                                        session.asker(),
                                        DataPurpose.MODEL,
                                        new ObjectSearchRequest(query, null, null, 0, SEARCH_SIZE))
                                .getContent());
    }

    /**
     * 076은 표시 이름과 식별 속성만 본다. 대표 속성이 비어 표시 이름이 원천 키(P101)이면 설비 이름('1라인 1호 프레스')으로 찾지 못한다. 그래서 076이
     * 0건일 때만 문자 속성 값이 똑같은 객체를 다시 찾는다(372). '가동'처럼 흔한 값이 여럿에 걸려도 076과 같이 후보를 보여 주며 되묻는다. 찾지 못했다고 답하면
     * 같은 이름의 설비가 여럿일 때 고를 기회가 없어서다.
     */
    private List<ObjectSearchResponse> searchByPropertyValue(
            String value, AgentQuerySession session) {
        return lookup(
                Map.of("q", value, "matchBy", PROPERTY_VALUE),
                session,
                () ->
                        objects.findByPropertyValue(
                                session.asker(), DataPurpose.MODEL, value, SEARCH_SIZE));
    }

    private List<ObjectSearchResponse> lookup(
            Map<String, Object> input,
            AgentQuerySession session,
            Supplier<List<ObjectSearchResponse>> query) {
        List<ObjectSearchResponse> rows = new ArrayList<>();
        session.lookup(
                TARGET_SEARCH,
                LABEL,
                input,
                () -> {
                    rows.addAll(query.get());
                    return new AgentQuerySession.Outcome(
                            null,
                            rows.isEmpty()
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            rows.stream()
                                    .limit(MAX_CANDIDATES)
                                    .map(row -> Map.<String, Object>of("objectId", row.id()))
                                    .toList(),
                            null);
                });
        return rows;
    }
}

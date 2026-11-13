package com.axcore.platform.ontology.service;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.service.ValueTransformer;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.domain.LinkMatchGroup;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.dto.LinkRecomputeResult;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.repository.RuleLinkRepository;
import com.axcore.platform.ontology.repository.RuleLinkRepository.LinkableObject;
import com.axcore.platform.ontology.repository.RuleLinkRepository.StoredLink;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인된 규칙으로 객체 연결을 다시 계산한다(FN-ONT-05, Jira 181, 결정 문서 2026-11-05 9.4). 화면·외부 API는 없다. 전체 읽기(197),
 * CDC 소비자(198), 규칙 승인 반영(189)이 객체·규칙을 쓴 같은 트랜잭션 안에서 부른다.
 *
 * <p>계산 단위는 <b>관계 유형</b>이다. 바뀐 객체·규칙이 닿는 관계 유형마다 아래를 한다.
 *
 * <ol>
 *   <li>관계 유형 잠금을 잡는다(트랜잭션 끝까지, 관계 유형 ID 순서). ERP·MES 소비자와 규칙 승인이 같은 관계 유형을 동시에 계산하지 않게 한다.
 *   <li>잠근 <b>뒤에</b> 그 관계 유형의 ACTIVE 규칙과 객체를 읽는다. 기다리는 사이 커밋된 객체·새 개정본을 보고 계산한다. 그래서 부르는 쪽 트랜잭션은
 *       READ COMMITTED(기본값)여야 한다. 그보다 높으면 잠금 뒤에도 남의 커밋이 보이지 않는다.
 *   <li>규칙마다 출발·도착 유형의 활성 객체 전부로 비교값 묶음을 만들고 성립하는 쌍을 구해 합친다(같은 쌍이면 지금 그 연결의 근거인 규칙 코드가 여전히 원할 때 그
 *       코드, 아니면 규칙 ID가 작은 쪽이 근거). 합친 쌍에도 관계 수 제약을 다시 적용한다.
 *   <li>그 관계 유형의 기존 연결 전부와 맞춘다. 합친 쌍에 없는 RULE 연결은 INACTIVE, 있는 쌍은 만들거나 되살린다.
 * </ol>
 *
 * <p>쌍을 정하는 기준
 *
 * <ul>
 *   <li>객체마다 규칙의 비교 조건대로 속성 값을 변환해 비교값(조건 순서대로의 문자열 목록)을 만든다. 값이 비었거나 단일 값이 아니거나 변환에 실패하면 비교값이 없고,
 *       그 객체는 이 규칙으로 연결되지 않는다(분류와 issue는 SYNC 검사가 한다). 기준은 검사 엔진과 같다.
 *   <li>비교값이 같은 출발 묶음과 도착 묶음을 잇는다. 자기 자신을 잇는 쌍을 제외한 뒤 관계 수를 센다. 도착이 ONE인 관계에서 도착이 둘 이상이거나, 출발이 ONE인
 *       관계에서 같은 도착을 가리키는 출발이 둘 이상이면 어느 쌍인지 정할 수 없어 잇지 않는다.
 *   <li>묶음에는 연결 대상 객체만 넣는다({@link com.axcore.platform.ontology.repository.LinkableObjects}, 검사와 같은
 *       정의, 387). 원천에서 지운 객체(DELETED_AT_SOURCE)와 꺼진 매핑에서 온 객체는 빠지므로 그 객체의 규칙 연결은 INACTIVE가 된다.
 * </ul>
 *
 * <p>주로 바꾸는 것은 RULE 연결이다. 조건을 읽지 못해 건너뛴 규칙이 근거인 연결은 양 끝 객체가 활성인 동안 그대로 둔다. 성립하지 않게 된 연결은 지우지 않고
 * INACTIVE로, 다시 성립하면 같은 행을 ACTIVE로 되살린다. 문서(DOCUMENT) 연결은 건드리지 않는다(결정 U6). 수동(MANUAL) 연결은 규칙이 잇지
 * 못하는 출발의 예외라, 규칙이 그 출발을 잇거나 양 끝이 지워지거나 승인 당시 비교 값이 바뀌면 끄고, 규칙이 같은 쌍을 이으면 규칙 연결로 바꾼다(366, {@link
 * #manualLinksToInactivate}). issue는 만들지 않는다(결정 문서 8장).
 *
 * <p>MVP에서는 관계 유형마다 양 끝 유형의 활성 객체와 그 관계 유형의 연결을 모두 읽는다(비교값이 변환 결과라 SQL로 좁히기 어렵다). 객체가 많아지면 비교값을 미리
 * 저장하는 칸을 둔다. 잠금 순서를 지키려면 <b>트랜잭션당 한 번, 객체를 다 쓴 뒤 마지막에</b> 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkRecomputeService {

    /**
     * 수동 연결 근거({@code object_link.evidence})에 저장 당시 출발 객체의 비교 속성 값을 남기는 키. 값은 {속성 코드: 값}이다. 이 값이
     * 바뀌면 수동 연결을 끈다(366).
     */
    public static final String SOURCE_VALUES = "sourceValues";

    private final LinkMappingRepository rules;
    private final LinkMappingConditionRepository conditions;
    private final LinkTypeRepository linkTypes;
    private final ObjectTypeRepository objectTypes;
    private final PropertyDefinitionRepository properties;
    private final RuleLinkRepository ruleLinks;
    private final ValueTransformer transformer;

    /**
     * 바뀐 객체(추가·수정·원천 삭제)의 유형이 출발이나 도착인 관계 유형의 연결을 다시 계산한다. 198이 원천 트랜잭션 하나를 반영한 뒤 부른다. 없는 ID는 무시한다.
     * 조건을 읽지 못한 규칙은 건너뛰고 결과에 남긴다(CDC 반영을 멈추지 않는다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LinkRecomputeResult recomputeObjects(Collection<Long> objectIds) {
        Objects.requireNonNull(objectIds, "objectIds");
        return recomputeObjectTypes(ruleLinks.objectTypeIdsOf(Set.copyOf(objectIds)));
    }

    /**
     * 객체 유형이 출발이나 도착인 관계 유형의 연결을 다시 계산한다. 197이 표 전체를 다시 읽은 뒤 그 매핑의 유형으로 부른다(객체 ID를 모두 넘기지 않아도 된다).
     * 조건을 읽지 못한 규칙은 건너뛰고 결과에 남긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LinkRecomputeResult recomputeObjectTypes(Collection<Long> objectTypeIds) {
        Objects.requireNonNull(objectTypeIds, "objectTypeIds");
        if (objectTypeIds.isEmpty()) {
            return LinkRecomputeResult.empty();
        }
        Set<Long> typeIds = Set.copyOf(objectTypeIds);
        Set<Long> linkTypeIds =
                linkTypes.findBySourceTypeIdInOrTargetTypeIdIn(typeIds, typeIds).stream()
                        .map(LinkType::getId)
                        .collect(Collectors.toSet());
        return run(linkTypeIds, Set.of());
    }

    /**
     * 규칙 개정본이 속한 관계 유형의 연결을 다시 계산한다. 189가 새 개정본을 승인한 트랜잭션에서 부른다(결정 U6: 옛 개정본 연결도 새 개정본 기준으로 바꾼다).
     *
     * @throws LinkRuleNotComputableException 넘긴 개정본이 ACTIVE인데 조건을 읽지 못함. 승인을 되돌리도록 부르는 쪽 트랜잭션을
     *     롤백시킨다. 189는 저장 전에 조건 형식을 검사해 이 예외가 사용자에게 500으로 나가지 않게 한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LinkRecomputeResult recomputeRules(Collection<Long> linkMappingIds) {
        Objects.requireNonNull(linkMappingIds, "linkMappingIds");
        Set<Long> ids = new LinkedHashSet<>(linkMappingIds);
        List<LinkMapping> requested = rules.findAllById(ids);
        // 없는 ID나 이미 폐기한 개정본을 넘기면 아무것도 계산하지 않고 지나가므로, 승인 경로에서는 실수로 보고 막는다.
        Set<Long> activeIds =
                requested.stream()
                        .filter(rule -> rule.getStatus() == LinkMappingStatus.ACTIVE)
                        .map(LinkMapping::getId)
                        .collect(Collectors.toSet());
        for (Long id : ids) {
            if (!activeIds.contains(id)) {
                throw LinkRuleNotComputableException.notActive(id);
            }
        }
        return run(
                requested.stream().map(LinkMapping::getLinkTypeId).collect(Collectors.toSet()),
                requested.stream().map(LinkMapping::getId).collect(Collectors.toSet()));
    }

    /**
     * @param strictRuleIds 조건을 읽지 못하면 예외로 끝낼 규칙 개정본(승인 경로)
     */
    private LinkRecomputeResult run(Set<Long> linkTypeIds, Set<Long> strictRuleIds) {
        if (linkTypeIds.isEmpty()) {
            return LinkRecomputeResult.empty();
        }
        TreeSet<Long> ordered = new TreeSet<>(linkTypeIds);
        for (Long linkTypeId : ordered) {
            ruleLinks.lockLinkType(linkTypeId);
        }
        // 잠근 뒤에 규칙을 읽는다. 기다리는 동안 커밋된 새 개정본을 보고 계산하기 위해서다.
        List<LinkMapping> active =
                rules.findByStatusAndLinkTypeIdInOrderById(LinkMappingStatus.ACTIVE, ordered);
        Map<Long, List<LinkMappingCondition>> conditionsByRule =
                active.isEmpty()
                        ? Map.of()
                        : conditions
                                .findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
                                        active.stream().map(LinkMapping::getId).toList())
                                .stream()
                                .collect(
                                        Collectors.groupingBy(
                                                LinkMappingCondition::getLinkMappingId));
        Map<Long, LinkType> linkTypeById =
                linkTypes.findAllById(ordered).stream()
                        .collect(Collectors.toMap(LinkType::getId, Function.identity()));
        Map<Long, String> typeCodes =
                objectTypes
                        .findAllById(
                                linkTypeById.values().stream()
                                        .flatMap(
                                                t ->
                                                        Stream.of(
                                                                t.getSourceTypeId(),
                                                                t.getTargetTypeId()))
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(ObjectType::getId, ObjectType::getCode));
        Map<Long, String> propertyCodes =
                properties
                        .findAllById(
                                conditionsByRule.values().stream()
                                        .flatMap(List::stream)
                                        .flatMap(
                                                c ->
                                                        Stream.of(
                                                                c.getSourcePropertyId(),
                                                                c.getTargetPropertyId()))
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        PropertyDefinition::getId, PropertyDefinition::getCode));
        Map<Long, List<LinkMapping>> activeByLinkType =
                active.stream().collect(Collectors.groupingBy(LinkMapping::getLinkTypeId));

        Accumulator result = new Accumulator();
        for (Long linkTypeId : ordered) {
            LinkType linkType = linkTypeById.get(linkTypeId);
            if (linkType == null) {
                continue;
            }
            List<RulePlan> plans = new ArrayList<>();
            Set<Long> skippedRuleIds = new LinkedHashSet<>();
            for (LinkMapping rule : activeByLinkType.getOrDefault(linkTypeId, List.of())) {
                RulePlan plan =
                        RulePlan.of(
                                rule,
                                linkType,
                                conditionsByRule.getOrDefault(rule.getId(), List.of()),
                                propertyCodes,
                                typeCodes);
                if (plan != null) {
                    plans.add(plan);
                    continue;
                }
                if (strictRuleIds.contains(rule.getId())) {
                    throw new LinkRuleNotComputableException(rule.getId(), rule.getCode());
                }
                log.warn(
                        "연결 규칙 {}(개정본 ID {})의 조건을 읽지 못해 연결을 계산하지 않았습니다.",
                        rule.getCode(),
                        rule.getId());
                skippedRuleIds.add(rule.getId());
            }
            result.skipped.addAll(skippedRuleIds);
            Set<String> skippedCodes =
                    activeByLinkType.getOrDefault(linkTypeId, List.of()).stream()
                            .filter(rule -> skippedRuleIds.contains(rule.getId()))
                            .map(LinkMapping::getCode)
                            .collect(Collectors.toSet());
            recompute(linkType, plans, skippedCodes, result);
        }
        return result.toResult();
    }

    private void recompute(
            LinkType linkType, List<RulePlan> plans, Set<String> skippedCodes, Accumulator result) {
        List<LinkableObject> sources = ruleLinks.activeObjects(linkType.getSourceTypeId());
        List<LinkableObject> targets =
                linkType.getTargetTypeId().equals(linkType.getSourceTypeId())
                        ? sources
                        : ruleLinks.activeObjects(linkType.getTargetTypeId());
        Map<Long, LinkableObject> sourcesById =
                sources.stream().collect(Collectors.toMap(LinkableObject::id, Function.identity()));
        Map<Long, LinkableObject> targetsById =
                targets.stream().collect(Collectors.toMap(LinkableObject::id, Function.identity()));

        Map<Pair, List<Desired>> wants = new LinkedHashMap<>();
        for (RulePlan plan : plans) {
            plan.pairs(sources, targets, transformer)
                    .forEach(
                            (pair, evidence) ->
                                    wants.computeIfAbsent(pair, p -> new ArrayList<>())
                                            .add(new Desired(plan, evidence)));
        }
        dropAmbiguous(wants, linkType.getCardinality());

        List<StoredLink> existing = ruleLinks.linksOf(linkType.getId());
        Map<Long, String> ruleCodeOfLink = ruleCodes(existing);
        Map<Pair, StoredLink> byPair = new HashMap<>();
        for (StoredLink link : existing) {
            byPair.put(new Pair(link.sourceObjectId(), link.targetObjectId()), link);
        }
        // 같은 쌍을 여러 규칙이 원하면, 지금 그 연결의 근거인 규칙 코드가 여전히 원할 때 그 코드를 근거로 둔다. 없으면 규칙 ID가 작은 쪽.
        // 다른 규칙의 새 개정본을 승인했다고 근거가 엉뚱한 코드로 넘어가지 않게 한다(181 리뷰).
        Map<Pair, Desired> desired = new LinkedHashMap<>();
        for (Map.Entry<Pair, List<Desired>> entry : wants.entrySet()) {
            StoredLink current = byPair.get(entry.getKey());
            String currentCode =
                    current == null || current.linkMappingId() == null
                            ? null
                            : ruleCodeOfLink.get(current.linkMappingId());
            desired.put(
                    entry.getKey(),
                    entry.getValue().stream()
                            .filter(want -> want.plan().rule().getCode().equals(currentCode))
                            .findFirst()
                            .orElse(entry.getValue().getFirst()));
        }

        List<Long> toInactivate = new ArrayList<>();
        for (StoredLink link : existing) {
            if (!link.isRule() || !link.active()) {
                continue;
            }
            if (desired.containsKey(new Pair(link.sourceObjectId(), link.targetObjectId()))) {
                continue;
            }
            // 건너뛴 규칙의 연결은 계산하지 못했으므로 두지만, 양 끝 객체가 원천에서 지워졌으면 끈다(9.4).
            boolean skipped = skippedCodes.contains(ruleCodeOfLink.get(link.linkMappingId()));
            if (skipped && link.endsActive()) {
                continue;
            }
            toInactivate.add(link.id());
        }
        ruleLinks.inactivateRuleLinks(toInactivate);
        result.inactivated.addAll(toInactivate);

        List<Long> manualToInactivate =
                manualLinksToInactivate(existing, desired.keySet(), sourcesById, linkType);
        ruleLinks.inactivateManualLinks(manualToInactivate);
        result.inactivated.addAll(manualToInactivate);

        for (Map.Entry<Pair, Desired> entry : desired.entrySet()) {
            Pair pair = entry.getKey();
            Desired want = entry.getValue();
            StoredLink link = byPair.get(pair);
            if (link != null && link.isDocument()) {
                continue; // 문서 연결은 그대로 둔다.
            }
            long ruleId = want.plan().ruleId();
            boolean sameActiveRule =
                    link != null && link.active() && Objects.equals(link.linkMappingId(), ruleId);
            // 변환 전 값을 추가하는 이유만으로 기존 활성 연결을 다시 저장하지 않는다.
            if (sameActiveRule && Objects.equals(link.evidence(), want.evidence())) {
                continue;
            }
            Map<String, Object> evidence =
                    want.plan().withRawValues(want.evidence(), sourcesById.get(pair.source()));
            if (sameActiveRule && Objects.equals(link.evidence(), evidence)) {
                continue;
            }
            evidence.put(
                    "targetRawValues",
                    want.plan().rawValues(targetsById.get(pair.target()), false));
            if (link == null) {
                result.activated.add(
                        ruleLinks.insertRuleLink(
                                linkType.getId(), pair.source(), pair.target(), ruleId, evidence));
                continue;
            }
            if (sameActiveRule && Objects.equals(link.evidence(), evidence)) {
                continue;
            }
            if (link.isManual()) {
                // 규칙이 이 쌍을 이으면 수동 연결은 규칙 연결이 된다(366). 이후로는 규칙이 지킨다. 승인자(confirmed_by)는 남긴다.
                ruleLinks.convertManualToRuleLink(link.id(), ruleId, evidence);
            } else {
                ruleLinks.confirmRuleLink(link.id(), ruleId, evidence);
            }
            (link.active() ? result.updated : result.activated).add(link.id());
        }
    }

    /**
     * 이번 계산 뒤 끌 활성 수동 연결(366). 수동 연결은 규칙이 잇지 못하는 출발에 관리자가 정한 예외라, 아래 경우에는 더 믿지 않는다.
     *
     * <ul>
     *   <li>양 끝 객체 중 하나가 원천에서 지워졌다.
     *   <li>규칙이 그 출발을 다른 도착과 잇는다(규칙이 기준). 출발이 하나뿐인 관계(ONE_TO_ONE·ONE_TO_MANY)면 규칙이 그 도착을 다른 출발과 이을
     *       때도 같다. 관계 수 제약을 지키기 위해서다. 같은 쌍이면 끄지 않고 규칙 연결로 바꾼다.
     *   <li>저장 때 남긴 출발 비교 속성 값({@code evidence.sourceValues}, {@link
     *       ConfirmedLinkService#saveManual})이 지금 값과 다르다. 고쳐진 값으로 다시 판단해야 한다. 끄면 다음 SYNC 검사가 그 출발을
     *       다시 판정해 문제로 올린다.
     * </ul>
     */
    private static List<Long> manualLinksToInactivate(
            List<StoredLink> existing,
            Set<Pair> desired,
            Map<Long, LinkableObject> sourcesById,
            LinkType linkType) {
        Set<Long> ruleSources = desired.stream().map(Pair::source).collect(Collectors.toSet());
        Set<Long> ruleTargets =
                linkType.getCardinality().sourceIsOne()
                        ? desired.stream().map(Pair::target).collect(Collectors.toSet())
                        : Set.of();
        List<Long> inactivate = new ArrayList<>();
        for (StoredLink link : existing) {
            if (!link.isManual() || !link.active()) {
                continue;
            }
            if (desired.contains(new Pair(link.sourceObjectId(), link.targetObjectId()))) {
                continue;
            }
            if (!link.endsActive()
                    || ruleSources.contains(link.sourceObjectId())
                    || ruleTargets.contains(link.targetObjectId())
                    || sourceValuesChanged(link, sourcesById.get(link.sourceObjectId()))) {
                inactivate.add(link.id());
            }
        }
        return inactivate;
    }

    private static boolean sourceValuesChanged(StoredLink link, LinkableObject source) {
        if (!(link.evidence().get(SOURCE_VALUES) instanceof Map<?, ?> saved) || saved.isEmpty()) {
            return false;
        }
        if (source == null) {
            return true;
        }
        Map<String, Object> current = source.properties() == null ? Map.of() : source.properties();
        return saved.entrySet().stream()
                .anyMatch(
                        e ->
                                !Objects.equals(
                                        e.getValue(), current.get(String.valueOf(e.getKey()))));
    }

    /**
     * 규칙마다 관계 수 제약을 지켜도, 같은 관계 유형의 규칙이 둘 이상이면 합친 결과가 어길 수 있다(규칙 A는 WO1→EQ1, 규칙 B는 WO1→EQ2). 합친 쌍에서
     * 어느 쌍인지 정할 수 없는 출발·도착은 모두 뺀다.
     */
    private static void dropAmbiguous(Map<Pair, ?> desired, LinkCardinality cardinality) {
        boolean destinationIsOne = cardinality.destinationIsOne();
        boolean sourceIsOne = cardinality.sourceIsOne();
        Map<Long, Long> targetsPerSource =
                desired.keySet().stream()
                        .collect(Collectors.groupingBy(Pair::source, Collectors.counting()));
        Map<Long, Long> sourcesPerTarget =
                desired.keySet().stream()
                        .collect(Collectors.groupingBy(Pair::target, Collectors.counting()));
        desired.keySet()
                .removeIf(
                        pair ->
                                destinationIsOne && targetsPerSource.get(pair.source()) > 1
                                        || sourceIsOne && sourcesPerTarget.get(pair.target()) > 1);
    }

    private Map<Long, String> ruleCodes(List<StoredLink> existing) {
        Set<Long> ids =
                existing.stream()
                        .map(StoredLink::linkMappingId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rules.findAllById(ids).stream()
                .collect(Collectors.toMap(LinkMapping::getId, LinkMapping::getCode));
    }

    /** 승인 경로(189)에서 넘긴 규칙의 조건을 읽지 못했다. 부르는 쪽 트랜잭션을 롤백시킨다. */
    public static class LinkRuleNotComputableException extends IllegalStateException {

        public LinkRuleNotComputableException(long linkMappingId, String code) {
            super("연결 규칙 " + code + "(개정본 ID " + linkMappingId + ")의 조건을 읽지 못해 연결을 계산할 수 없습니다.");
        }

        private LinkRuleNotComputableException(String message) {
            super(message);
        }

        static LinkRuleNotComputableException notActive(long linkMappingId) {
            return new LinkRuleNotComputableException(
                    "연결 규칙 개정본 ID " + linkMappingId + "은(는) 없거나 ACTIVE가 아니라 연결을 계산할 수 없습니다.");
        }
    }

    private record Pair(long source, long target) {}

    private record Desired(RulePlan plan, Map<String, Object> evidence) {}

    /** 관계 유형마다 연결 하나를 한 번만 다루므로 같은 연결이 두 목록에 들어가지 않는다. */
    private static final class Accumulator {
        private final Set<Long> activated = new LinkedHashSet<>();
        private final Set<Long> updated = new LinkedHashSet<>();
        private final Set<Long> inactivated = new LinkedHashSet<>();
        private final Set<Long> skipped = new LinkedHashSet<>();

        LinkRecomputeResult toResult() {
            return new LinkRecomputeResult(
                    List.copyOf(activated),
                    List.copyOf(updated),
                    List.copyOf(inactivated),
                    List.copyOf(skipped));
        }
    }

    /** 규칙 하나를 계산하는 데 필요한 것. 조건을 읽지 못하면 만들지 않는다. */
    private record RulePlan(
            LinkMapping rule,
            LinkType linkType,
            List<Condition> conditions,
            Filter sourceFilter,
            Filter targetFilter) {

        static RulePlan of(
                LinkMapping rule,
                LinkType linkType,
                List<LinkMappingCondition> conditions,
                Map<Long, String> propertyCodes,
                Map<Long, String> typeCodes) {
            if (conditions.isEmpty()) {
                return null;
            }
            Filter source =
                    Filter.parse(rule.getSourceFilter(), typeCodes.get(linkType.getSourceTypeId()));
            Filter target =
                    Filter.parse(rule.getTargetFilter(), typeCodes.get(linkType.getTargetTypeId()));
            if (source == null || target == null) {
                return null;
            }
            List<Condition> parsed = new ArrayList<>();
            for (LinkMappingCondition condition : conditions) {
                String sourceCode = propertyCodes.get(condition.getSourcePropertyId());
                String targetCode = propertyCodes.get(condition.getTargetPropertyId());
                List<TransformStep> sourceSteps = steps(condition.getSourceTransforms());
                List<TransformStep> targetSteps = steps(condition.getTargetTransforms());
                if (sourceCode == null
                        || targetCode == null
                        || sourceSteps == null
                        || targetSteps == null) {
                    return null;
                }
                parsed.add(
                        new Condition(
                                condition.getSequenceNo(),
                                sourceCode,
                                targetCode,
                                sourceSteps,
                                targetSteps));
            }
            return new RulePlan(rule, linkType, List.copyOf(parsed), source, target);
        }

        long ruleId() {
            return rule.getId();
        }

        /** 이 규칙만으로 성립하는 쌍과 그 근거. */
        Map<Pair, Map<String, Object>> pairs(
                List<LinkableObject> sources,
                List<LinkableObject> targets,
                ValueTransformer transformer) {
            Map<List<String>, Set<Long>> sourcesByKey = group(sources, transformer, true);
            Map<List<String>, Set<Long>> targetsByKey = group(targets, transformer, false);
            Map<Pair, Map<String, Object>> pairs = new LinkedHashMap<>();
            for (Map.Entry<List<String>, Set<Long>> entry : sourcesByKey.entrySet()) {
                Set<Long> targetIds = targetsByKey.get(entry.getKey());
                if (targetIds == null) {
                    continue;
                }
                var matches = new LinkMatchGroup<>(entry.getValue(), targetIds, true);
                Map<String, Object> evidence = evidence(entry.getKey());
                for (Long source : entry.getValue()) {
                    if (mismatchReason(source, matches) != null) {
                        continue;
                    }
                    for (Long target : targetIds) {
                        if (matches.includes(source, target)) {
                            pairs.put(new Pair(source, target), evidence);
                        }
                    }
                }
            }
            return pairs;
        }

        private Map<List<String>, Set<Long>> group(
                List<LinkableObject> objects, ValueTransformer transformer, boolean source) {
            Filter filter = source ? sourceFilter : targetFilter;
            Map<List<String>, Set<Long>> grouped = new LinkedHashMap<>();
            for (LinkableObject object : objects) {
                if (!filter.matches(object)) {
                    continue;
                }
                List<String> key = key(object, transformer, source);
                if (key != null) {
                    grouped.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(object.id());
                }
            }
            return grouped;
        }

        private String mismatchReason(long source, LinkMatchGroup<Long> matches) {
            int targetCount = matches.targetCount(source);
            if (targetCount == 0) {
                return "비교값이 같은 도착 객체가 없음";
            }
            LinkCardinality cardinality = linkType.getCardinality();
            if (cardinality.destinationIsOne() && targetCount > 1) {
                return "비교값이 같은 도착 객체가 둘 이상";
            }
            if (cardinality.sourceIsOne() && matches.hasCompetingSources(source)) {
                return "같은 도착을 가리키는 출발 객체가 둘 이상";
            }
            return null;
        }

        private List<String> key(
                LinkableObject object, ValueTransformer transformer, boolean source) {
            return evaluate(object, transformer, source).key();
        }

        // 검사 엔진(RuleEvaluationEngine)과 같은 기준: 빈 값·단일 값이 아님·변환 실패면 비교값이 없다.
        private KeyResult evaluate(
                LinkableObject object, ValueTransformer transformer, boolean source) {
            Map<String, Object> values =
                    object.properties() == null ? Map.of() : object.properties();
            List<String> raws = new ArrayList<>(conditions.size());
            List<String> key = new ArrayList<>(conditions.size());
            for (Condition condition : conditions) {
                Object raw = values.get(source ? condition.sourceCode() : condition.targetCode());
                if (raw == null || raw instanceof String text && text.isBlank()) {
                    return KeyResult.missing();
                }
                if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) {
                    raws.add(String.valueOf(raw));
                    return KeyResult.failed(raws, "비교할 값이 단일 값이 아님");
                }
                raws.add(String.valueOf(raw));
                String value;
                try {
                    value =
                            transformer.transform(
                                    String.valueOf(raw),
                                    source ? condition.sourceSteps() : condition.targetSteps());
                } catch (ValueTransformer.TransformException e) {
                    return KeyResult.failed(raws, "조건 " + condition.sequenceNo() + " 변환 실패");
                }
                if (value == null || value.isBlank()) {
                    return KeyResult.missing();
                }
                key.add(value);
            }
            return new KeyResult(List.copyOf(key), List.copyOf(raws), null);
        }

        private Map<String, Object> evidence(List<String> key) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < conditions.size(); i++) {
                values.put(String.valueOf(conditions.get(i).sequenceNo()), key.get(i));
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("ruleCode", rule.getCode());
            evidence.put("revision", rule.getRevision());
            evidence.put("matchedValues", values);
            return evidence;
        }

        private Map<String, Object> withRawValues(
                Map<String, Object> matchedEvidence, LinkableObject source) {
            Map<String, Object> evidence = new LinkedHashMap<>(matchedEvidence);
            evidence.put("rawValues", rawValues(source, true));
            return evidence;
        }

        private Map<String, String> rawValues(LinkableObject object, boolean source) {
            Map<String, String> rawValues = new LinkedHashMap<>();
            for (Condition condition : conditions) {
                rawValues.put(
                        String.valueOf(condition.sequenceNo()),
                        String.valueOf(
                                object.properties()
                                        .get(
                                                source
                                                        ? condition.sourceCode()
                                                        : condition.targetCode())));
            }
            return rawValues;
        }

        private static List<TransformStep> steps(List<Map<String, Object>> stored) {
            if (stored == null) {
                return List.of();
            }
            List<TransformStep> steps = new ArrayList<>();
            try {
                for (Map<String, Object> step : stored) {
                    steps.add(
                            new TransformStep(
                                    TransformOperation.valueOf(String.valueOf(step.get("op"))),
                                    step.get("arg")));
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
            return List.copyOf(steps);
        }
    }

    /** 객체 하나의 비교값 계산 결과. 값이 비었으면 key·failure 모두 없고, 실패면 failure에 이유가 있다. */
    private record KeyResult(List<String> key, List<String> raws, String failure) {

        static KeyResult missing() {
            return new KeyResult(null, List.of(), null);
        }

        static KeyResult failed(List<String> raws, String failure) {
            return new KeyResult(null, List.copyOf(raws), failure);
        }

        boolean isMissing() {
            return key == null && failure == null;
        }
    }

    private record Condition(
            int sequenceNo,
            String sourceCode,
            String targetCode,
            List<TransformStep> sourceSteps,
            List<TransformStep> targetSteps) {}

    /**
     * 출발·도착 객체 조건. 형식이 아직 정해지지 않아 두 가지만 읽는다: 빈 조건 {@code {}}(모두), 그리고 AI 후보 형식 {@code {"property":
     * "유형코드.속성코드" 또는 "속성코드", "equals": "값"}}(속성 값의 문자열이 같을 때만). 유형코드를 붙였다면 그 쪽(출발·도착) 유형의 코드와 같아야
     * 한다. 그 밖이면 null이고, 그 규칙은 계산하지 않는다.
     */
    private record Filter(String propertyCode, String equals) {

        static final Filter ALL = new Filter(null, null);

        static Filter parse(Map<String, Object> stored, String sideTypeCode) {
            if (stored == null || stored.isEmpty()) {
                return ALL;
            }
            if (stored.size() != 2
                    || !(stored.get("property") instanceof String property)
                    || !(stored.get("equals") instanceof String equals)
                    || property.isBlank()) {
                return null;
            }
            int dot = property.lastIndexOf('.');
            if (dot < 0) {
                return new Filter(property, equals);
            }
            if (!property.substring(0, dot).equals(sideTypeCode) || dot == property.length() - 1) {
                return null;
            }
            return new Filter(property.substring(dot + 1), equals);
        }

        boolean matches(LinkableObject object) {
            if (propertyCode == null) {
                return true;
            }
            Object value =
                    object.properties() == null ? null : object.properties().get(propertyCode);
            return value != null && equals.equals(String.valueOf(value));
        }
    }
}

package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.dto.RuleListRequest;
import com.axcore.platform.ontology.dto.RuleRevisionResponse;
import com.axcore.platform.ontology.dto.RuleSummaryResponse;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 규칙 개정 이력(API 046)과 개정본 상세(API 047, FN-RUL-01). 읽기만 한다.
 *
 * <p>개정본은 만든 뒤 내용을 바꾸지 않고(DB 트리거) 새 개정본을 만들 때 이전 것을 RETIRED로 남긴다. 그래서 이력은 같은 코드의 개정본을 개정 번호 순으로 보여
 * 주면 된다. 개정본 저장·폐기는 규칙 반영(189 {@code LinkRuleApplier})이 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleRevisionService {

    private final LinkMappingRepository linkMappingRepository;
    private final LinkMappingConditionRepository conditionRepository;
    private final LinkTypeRepository linkTypeRepository;
    private final PropertyDefinitionRepository propertyRepository;

    /**
     * 연결 규칙 목록. 규칙 코드마다 최신 개정본 하나를 코드 순으로 돌려준다. 규칙 수는 관계 유형 수 정도라 페이지를 나누지 않는다. 관계 유형 이름은 지금 이름이다.
     */
    public List<RuleSummaryResponse> rules(RuleListRequest request) {
        List<LinkMapping> latest =
                linkMappingRepository.findLatestRevisions(request.linkTypeId(), request.status());
        Map<Long, String> names = linkTypeNames(latest);
        return latest.stream()
                .map(rule -> RuleSummaryResponse.of(rule, names.get(rule.getLinkTypeId())))
                .toList();
    }

    /** API 046. 그 코드의 개정본이 하나도 없으면 404다. */
    public Page<RuleRevisionResponse> revisions(String code, Pageable pageable) {
        Page<LinkMapping> page = linkMappingRepository.findByCodeOrderByRevisionAsc(code, pageable);
        if (page.getTotalElements() == 0) {
            throw notFound(code, null);
        }
        Map<Long, List<LinkMappingCondition>> conditions =
                conditionRepository
                        .findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
                                page.map(LinkMapping::getId).getContent())
                        .stream()
                        .collect(Collectors.groupingBy(LinkMappingCondition::getLinkMappingId));
        Map<Long, String> linkTypeNames = linkTypeNames(page.getContent());
        Map<Long, String> propertyNames =
                propertyNames(conditions.values().stream().flatMap(List::stream).toList());
        return page.map(
                rule ->
                        RuleRevisionResponse.of(
                                rule,
                                linkTypeNames.get(rule.getLinkTypeId()),
                                conditions.getOrDefault(rule.getId(), List.of()),
                                propertyNames));
    }

    /** API 047. */
    public RuleRevisionResponse revision(String code, long revision) {
        // 개정 번호 칸은 integer다. 그보다 큰 번호의 개정본은 없다.
        if (revision > Integer.MAX_VALUE) {
            throw notFound(code, revision);
        }
        LinkMapping rule =
                linkMappingRepository
                        .findByCodeAndRevision(code, (int) revision)
                        .orElseThrow(() -> notFound(code, revision));
        List<LinkMappingCondition> conditions =
                conditionRepository.findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
                        List.of(rule.getId()));
        return RuleRevisionResponse.of(
                rule,
                linkTypeNames(List.of(rule)).get(rule.getLinkTypeId()),
                conditions,
                propertyNames(conditions));
    }

    /** 규칙들이 가리키는 관계 유형의 지금 이름을 한 번에 읽는다. */
    private Map<Long, String> linkTypeNames(Collection<LinkMapping> rules) {
        return linkTypeRepository
                .findAllById(rules.stream().map(LinkMapping::getLinkTypeId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(LinkType::getId, LinkType::getName, (a, b) -> a));
    }

    /** 비교 항목들이 가리키는 출발·도착 속성의 지금 이름을 한 번에 읽는다. */
    private Map<Long, String> propertyNames(Collection<LinkMappingCondition> conditions) {
        List<Long> ids =
                conditions.stream()
                        .flatMap(c -> Stream.of(c.getSourcePropertyId(), c.getTargetPropertyId()))
                        .distinct()
                        .toList();
        return propertyRepository.findAllById(ids).stream()
                .collect(
                        Collectors.toMap(
                                PropertyDefinition::getId,
                                PropertyDefinition::getName,
                                (a, b) -> a));
    }

    private static BusinessException notFound(String code, Long revision) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                revision == null
                        ? Map.of("resource", "link_mapping", "code", code)
                        : Map.of("resource", "link_mapping", "code", code, "revision", revision));
    }
}

package com.axcore.platform.ontology.service;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectLinkErrorCode;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.dto.ConfirmedLinkRequest;
import com.axcore.platform.ontology.dto.SavedLink;
import com.axcore.platform.ontology.dto.SavedLink.Outcome;
import com.axcore.platform.ontology.repository.ConfirmedLinkRepository;
import com.axcore.platform.ontology.repository.ConfirmedLinkRepository.LinkEnd;
import com.axcore.platform.ontology.repository.ConfirmedLinkRepository.PairLink;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.repository.RuleLinkRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 확정한 연결을 {@code object_link}에 저장한다(Jira 329, FN-ONT-05). 화면·외부 API는 없고 내부에서만 부른다.
 *
 * <ul>
 *   <li>{@link #saveManual}: 관리자가 문제(issue)에서 정한 수동 연결(058, 366). origin=MANUAL. 규칙이 이미 이은 출발은 받지
 *       않고, 저장 당시 출발 비교 속성 값을 근거에 남긴다. 재계산은 그 값이 바뀌거나 규칙이 그 출발을 이으면 이 연결을 끈다
 *   <li>{@link #saveDocument}: 문서 연결 후보 승인(DOCUMENT_LINK 반영기). origin=DOCUMENT
 * </ul>
 *
 * <p>순서:
 *
 * <ol>
 *   <li>양 끝 객체를 ID 순서로 공유 잠금하고 존재·유형·연결 대상인지({@link
 *       com.axcore.platform.ontology.repository.LinkableObjects}: ACTIVE이고 꺼진 매핑에서 오지 않음, 387)를 본다.
 *       관계 유형 잠금보다 <b>먼저</b> 잡는다. 수집(197·198)은 객체를 고친 뒤 마지막에 관계 유형을 잠그므로, 순서를 맞춰야 서로 기다리다 교착하지 않는다.
 *   <li>규칙 재계산과 같은 관계 유형 잠금({@link RuleLinkRepository#lockLinkType})을 잡는다. 그래서 같은 쌍을 동시에 넣다가 {@code
 *       uq_object_link}에 걸리지 않고, 먼저 커밋한 쪽의 연결을 보고 판단한다. 잠근 뒤 양 끝을 다시 읽어, 기다리는 사이 끝난 매핑 끄기를 본다.
 *   <li>쌍의 기존 행을 본다. 활성 행이 있으면 거절한다(같은 쌍의 문서 연결만 그대로 성공). 비활성 행은 규칙 연결이었어도 확정 연결로 되살린다(관리자 확정이 우선).
 *   <li>관계 수 제약을 모든 출처의 활성 연결로 검사한다.
 * </ol>
 *
 * <p>모두 부르는 쪽 트랜잭션 안에서만 돈다(MANDATORY). 보류 건 상태 변경·감사 기록은 부르는 쪽이 같은 트랜잭션에서 한다. 감사에 넣을 전후 내용은 {@link
 * SavedLink#change()}로 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class ConfirmedLinkService {

    private static final String MANUAL = "MANUAL";
    private static final String DOCUMENT = "DOCUMENT";
    private static final String EXPRESSION_KIND = "expression_kind";
    private static final Set<String> EXPRESSION_KINDS = Set.of("OBSERVED", "ESTIMATED");

    private final LinkTypeRepository linkTypes;
    private final RuleLinkRepository ruleLinks;
    private final ConfirmedLinkRepository links;
    private final LinkMappingRepository rules;
    private final LinkMappingConditionRepository conditions;
    private final PropertyDefinitionRepository properties;

    /**
     * 관리자가 고른 도착 객체와 연결한다. 같은 쌍에 활성 연결이 있으면 {@code LINK_ALREADY_EXISTS}, 규칙이 이미 그 출발을 이었으면 {@code
     * LINK_SOURCE_LINKED_BY_RULE}(둘 다 409)다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavedLink saveManual(ConfirmedLinkRequest request) {
        return save(MANUAL, request);
    }

    /**
     * 승인한 문서 연결 후보를 저장한다. {@code evidence.expression_kind}는 OBSERVED·ESTIMATED만 받는다(결정 U8, 부정 표현은
     * 연결하지 않음). 같은 쌍의 활성 문서 연결이 이미 있으면 그대로 두고 {@code ALREADY_LINKED}로 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavedLink saveDocument(ConfirmedLinkRequest request) {
        Object kind = request.evidence().get(EXPRESSION_KIND);
        if (!(kind instanceof String text) || !EXPRESSION_KINDS.contains(text)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "evidence." + EXPRESSION_KIND);
            details.put("value", kind);
            throw new BusinessException(ObjectLinkErrorCode.LINK_EVIDENCE_INVALID, details);
        }
        return save(DOCUMENT, request);
    }

    private SavedLink save(String origin, ConfirmedLinkRequest request) {
        LinkType linkType =
                linkTypes
                        .findById(request.linkTypeId())
                        .orElseThrow(() -> notFound("link_type", request.linkTypeId()));
        long sourceId = request.sourceObjectId();
        long targetId = request.targetObjectId();
        if (sourceId == targetId) {
            throw new BusinessException(
                    ObjectLinkErrorCode.LINK_SELF_REFERENCE, Map.of("objectId", sourceId));
        }

        // 1. 양 끝 객체(공유 잠금) → 2. 관계 유형 잠금. 순서는 클래스 설명.
        Map<Long, LinkEnd> ends = new LinkedHashMap<>();
        for (LinkEnd end : links.lockObjects(sourceId, targetId)) {
            ends.put(end.id(), end);
        }
        requireEnd(ends.get(sourceId), sourceId, linkType.getSourceTypeId(), "source");
        requireEnd(ends.get(targetId), targetId, linkType.getTargetTypeId(), "target");
        ruleLinks.lockLinkType(linkType.getId());
        // 매핑 끄기(387)는 매핑을 바꾼 뒤 이 관계 유형 잠금을 잡고 재계산한다. 잠금을 기다리는 사이 끝난 매핑 끄기를 보도록 다시 읽는다.
        // 반대로 이쪽이 먼저 잠그면 매핑 끄기의 재계산이 이 연결을 보고 끈다.
        Map<Long, LinkEnd> current = new LinkedHashMap<>();
        for (LinkEnd end : links.lockObjects(sourceId, targetId)) {
            current.put(end.id(), end);
        }
        requireEnd(current.get(sourceId), sourceId, linkType.getSourceTypeId(), "source");
        requireEnd(current.get(targetId), targetId, linkType.getTargetTypeId(), "target");

        // 3. 쌍의 기존 행
        PairLink existing = links.findPair(linkType.getId(), sourceId, targetId).orElse(null);
        if (existing != null && existing.active()) {
            if (DOCUMENT.equals(origin) && DOCUMENT.equals(existing.origin())) {
                return new SavedLink(existing.id(), Outcome.ALREADY_LINKED, null, null);
            }
            throw new BusinessException(
                    ObjectLinkErrorCode.LINK_ALREADY_EXISTS,
                    Map.of("linkId", existing.id(), "origin", existing.origin()));
        }

        // 수동 연결은 규칙이 잇지 못한 출발의 예외다. 규칙이 이미 그 출발을 이었으면 받지 않는다(366). 받아도 재계산이 곧 끈다.
        Map<String, Object> evidence = request.evidence();
        if (MANUAL.equals(origin)) {
            if (links.countActiveRuleFromSource(linkType.getId(), sourceId) > 0) {
                throw new BusinessException(
                        ObjectLinkErrorCode.LINK_SOURCE_LINKED_BY_RULE,
                        Map.of("sourceObjectId", sourceId, "linkTypeId", linkType.getId()));
            }
            evidence = withSourceValues(evidence, linkType.getId(), sourceId);
        }

        // 4. 관계 수 제약(모든 출처). 쌍의 행은 활성이 아니므로 세지 않는다.
        LinkCardinality cardinality = linkType.getCardinality();
        if (targetSideIsOne(cardinality)
                && links.countActiveFromSource(linkType.getId(), sourceId) > 0) {
            throw cardinalityExceeded(cardinality, "source", sourceId);
        }
        if (sourceSideIsOne(cardinality)
                && links.countActiveToTarget(linkType.getId(), targetId) > 0) {
            throw cardinalityExceeded(cardinality, "target", targetId);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("origin", origin);
        after.put("status", "ACTIVE");
        after.put("confirmedBy", request.confirmedBy());
        after.put("proposalItemId", request.proposalItemId());
        if (existing == null) {
            long id =
                    links.insert(
                            linkType.getId(),
                            sourceId,
                            targetId,
                            origin,
                            request.confirmedBy(),
                            request.proposalItemId(),
                            evidence);
            Map<String, Object> inserted = new LinkedHashMap<>();
            inserted.put("linkTypeId", linkType.getId());
            inserted.put("sourceObjectId", sourceId);
            inserted.put("targetObjectId", targetId);
            inserted.putAll(after);
            return new SavedLink(
                    id,
                    Outcome.CREATED,
                    null,
                    ChangeEntry.insert("object_link", Map.of("id", id), inserted));
        }
        links.reactivate(
                existing.id(), origin, request.confirmedBy(), request.proposalItemId(), evidence);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("origin", existing.origin());
        before.put("status", "INACTIVE");
        before.put("linkMappingId", existing.linkMappingId());
        after.put("linkMappingId", null);
        return new SavedLink(
                existing.id(),
                Outcome.REACTIVATED,
                existing.origin(),
                ChangeEntry.update("object_link", Map.of("id", existing.id()), before, after));
    }

    /**
     * 수동 연결 근거에 저장 당시 출발 객체의 비교 속성 값({@link LinkRecomputeService#SOURCE_VALUES})을 더한다(366). 비교 속성은
     * 그 관계 유형의 반영 중인 규칙들이 출발 쪽에서 비교하는 속성이다. 재계산은 이 값이 바뀌면 수동 연결을 끈다. 반영 중인 규칙이 없으면 더하지 않는다.
     */
    private Map<String, Object> withSourceValues(
            Map<String, Object> evidence, long linkTypeId, long sourceObjectId) {
        List<Long> ruleIds =
                rules
                        .findByStatusAndLinkTypeIdInOrderById(
                                LinkMappingStatus.ACTIVE, List.of(linkTypeId))
                        .stream()
                        .map(LinkMapping::getId)
                        .toList();
        if (ruleIds.isEmpty()) {
            return evidence;
        }
        Set<Long> propertyIds =
                conditions
                        .findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(ruleIds)
                        .stream()
                        .map(LinkMappingCondition::getSourcePropertyId)
                        .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Object> current = links.properties(sourceObjectId);
        Map<String, Object> values = new TreeMap<>();
        for (PropertyDefinition property : properties.findAllById(propertyIds)) {
            values.put(property.getCode(), current.get(property.getCode()));
        }
        if (values.isEmpty()) {
            return evidence;
        }
        Map<String, Object> merged = new LinkedHashMap<>(evidence);
        merged.put(LinkRecomputeService.SOURCE_VALUES, values);
        return merged;
    }

    private static void requireEnd(LinkEnd end, long objectId, long expectedTypeId, String side) {
        if (end == null) {
            throw notFound("object_instance", objectId);
        }
        if (end.objectTypeId() != expectedTypeId) {
            throw new BusinessException(
                    ObjectLinkErrorCode.LINK_END_TYPE_MISMATCH,
                    Map.of(
                            "end",
                            side,
                            "objectId",
                            objectId,
                            "expectedObjectTypeId",
                            expectedTypeId,
                            "actualObjectTypeId",
                            end.objectTypeId()));
        }
        if (!end.active()) {
            throw new BusinessException(
                    ObjectLinkErrorCode.LINK_END_NOT_ACTIVE,
                    Map.of("end", side, "objectId", objectId));
        }
    }

    /** 출발 하나가 도착 하나에만 이어질 수 있다(ONE_TO_ONE·MANY_TO_ONE). */
    private static boolean targetSideIsOne(LinkCardinality cardinality) {
        return cardinality == LinkCardinality.ONE_TO_ONE
                || cardinality == LinkCardinality.MANY_TO_ONE;
    }

    /** 도착 하나에 출발 하나만 이어질 수 있다(ONE_TO_ONE·ONE_TO_MANY). */
    private static boolean sourceSideIsOne(LinkCardinality cardinality) {
        return cardinality == LinkCardinality.ONE_TO_ONE
                || cardinality == LinkCardinality.ONE_TO_MANY;
    }

    private static BusinessException cardinalityExceeded(
            LinkCardinality cardinality, String side, long objectId) {
        return new BusinessException(
                ObjectLinkErrorCode.LINK_CARDINALITY_EXCEEDED,
                Map.of("cardinality", cardinality.name(), "end", side, "objectId", objectId));
    }

    private static BusinessException notFound(String resource, long id) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", resource, "id", id));
    }
}

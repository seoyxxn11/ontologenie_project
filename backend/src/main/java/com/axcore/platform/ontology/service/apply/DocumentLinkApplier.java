package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectLinkErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.dto.ConfirmedLinkRequest;
import com.axcore.platform.ontology.dto.SavedLink;
import com.axcore.platform.ontology.repository.ConfirmedLinkRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.service.ConfirmedLinkService;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * DOCUMENT_LINK 후보 반영(Jira 329). 메모·정비 기록 같은 문서 객체와 글 속에 언급된 객체를 문서 연결(origin=DOCUMENT)로 잇는다.
 *
 * <p>payload(AI 형식, {@code ai/docs/api-internal.md}):
 *
 * <ul>
 *   <li>관계 유형: {@code linkTypeCode} 또는 {@code linkTypeId}
 *   <li>출발(문서): {@code documentId}(문서 객체의 원천 키 값) 또는 {@code sourceObjectId}
 *   <li>도착: {@code targetObjectRef}({@code 원천표:키값}, 예: {@code mes.equipment:P201}) 또는 {@code
 *       targetObjectId}
 *   <li>{@code expressionKind}: OBSERVED·ESTIMATED(결정 U8). 근거로 {@code spanStart}·{@code
 *       spanEnd}·{@code mentionText}를 함께 남긴다
 * </ul>
 *
 * <p>원천 키로 찾는 객체는 관계 유형의 출발·도착 유형이고, 키 컬럼이 하나인 매핑으로 만든 객체여야 한다. 저장·검증은 {@link
 * ConfirmedLinkService}가 한다. 같은 쌍의 문서 연결이 이미 있으면 그 연결로 성공한다.
 */
@Component
@RequiredArgsConstructor
class DocumentLinkApplier implements ProposalApplier {

    private static final String FIELD = "payload";
    private static final Set<String> EXPRESSION_KINDS = Set.of("OBSERVED", "ESTIMATED");

    private final ApplySupport support;
    private final LinkTypeRepository linkTypes;
    private final ConfirmedLinkRepository confirmedLinks;
    private final ConfirmedLinkService service;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.DOCUMENT_LINK;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        String expressionKind = support.text(payload, "expressionKind", FIELD);
        if (!EXPRESSION_KINDS.contains(expressionKind)) {
            throw ApplySupport.invalid(FIELD + ".expressionKind", "OBSERVED 또는 ESTIMATED여야 합니다.");
        }
        LinkType linkType = linkType(payload);
        long sourceId = sourceObject(payload, linkType);
        long targetId = targetObject(payload, linkType);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("expression_kind", expressionKind);
        putIfPresent(evidence, "document_id", payload.get("documentId"));
        putIfPresent(evidence, "span_start", payload.get("spanStart"));
        putIfPresent(evidence, "span_end", payload.get("spanEnd"));
        putIfPresent(evidence, "quote", payload.get("mentionText"));

        SavedLink saved;
        try {
            saved =
                    service.saveDocument(
                            new ConfirmedLinkRequest(
                                    linkType.getId(),
                                    sourceId,
                                    targetId,
                                    command.decidedBy(),
                                    command.proposalItemId(),
                                    evidence));
        } catch (BusinessException e) {
            throw applyError(e);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", saved.outcome().name());
        summary.put("linkTypeCode", linkType.getCode());
        summary.put("sourceObjectId", sourceId);
        summary.put("targetObjectId", targetId);
        summary.put("expressionKind", expressionKind);
        summary.put("previousOrigin", saved.previousOrigin());
        return new ApplyResult(
                "object_link",
                saved.linkId(),
                List.of(),
                summary,
                saved.change() == null ? List.of() : List.of(saved.change()));
    }

    private LinkType linkType(Map<String, Object> payload) {
        Long id = optionalId(payload, "linkTypeId");
        if (id != null) {
            return linkTypes
                    .findById(id)
                    .orElseThrow(
                            () ->
                                    ApplySupport.missing(
                                            FIELD + ".linkTypeId",
                                            String.valueOf(id),
                                            "LINK_TYPE"));
        }
        String code = support.text(payload, "linkTypeCode", FIELD);
        return linkTypes
                .findByCode(code)
                .orElseThrow(
                        () -> ApplySupport.missing(FIELD + ".linkTypeCode", code, "LINK_TYPE"));
    }

    /** 문서 객체. {@code sourceObjectId} 또는 {@code documentId}(관계 유형 출발 유형의 원천 키 값). */
    private long sourceObject(Map<String, Object> payload, LinkType linkType) {
        Long id = optionalId(payload, "sourceObjectId");
        if (id != null) {
            return id;
        }
        String documentId = support.text(payload, "documentId", FIELD);
        return single(
                confirmedLinks.findBySingleSourceKey(linkType.getSourceTypeId(), null, documentId),
                FIELD + ".documentId",
                documentId);
    }

    /** 언급된 객체. {@code targetObjectId} 또는 {@code targetObjectRef}({@code 원천표:키값}). */
    private long targetObject(Map<String, Object> payload, LinkType linkType) {
        Long id = optionalId(payload, "targetObjectId");
        if (id != null) {
            return id;
        }
        String reference = support.text(payload, "targetObjectRef", FIELD);
        int colon = reference.indexOf(':');
        if (colon <= 0 || colon == reference.length() - 1) {
            throw ApplySupport.invalid(
                    FIELD + ".targetObjectRef", "'원천표:키값' 형식이어야 합니다(예: mes.equipment:P201).");
        }
        return single(
                confirmedLinks.findBySingleSourceKey(
                        linkType.getTargetTypeId(),
                        reference.substring(0, colon),
                        reference.substring(colon + 1)),
                FIELD + ".targetObjectRef",
                reference);
    }

    private static long single(List<Long> found, String field, String reference) {
        if (found.isEmpty()) {
            // 문서·대상 표를 아직 적재하지 않았거나 원천에 없는 키다. 적재 뒤 다시 승인한다.
            throw ApplySupport.missing(field, reference, "OBJECT");
        }
        if (found.size() > 1) {
            throw ApplySupport.invalid(field, "같은 원천 키의 객체가 여럿이라 하나로 정할 수 없습니다: " + reference);
        }
        return found.getFirst();
    }

    private static Long optionalId(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number) || number.longValue() < 1) {
            throw ApplySupport.invalid(FIELD + "." + key, "양의 정수 ID여야 합니다.");
        }
        return number.longValue();
    }

    private static void putIfPresent(Map<String, Object> evidence, String key, Object value) {
        if (value != null) {
            evidence.put(key, value);
        }
    }

    /**
     * 저장 서비스의 오류를 반영 오류 코드로 바꾼다(131이 APPLY_FAILED로 응답하고 원래 이유는 details에 남는다). 이미 연결된 쌍은 {@code
     * APPLY_TARGET_EXISTS}, 없는 객체는 {@code APPLY_REFERENCE_MISSING}, 나머지(유형 불일치·원천 삭제·관계 수)는 후보 내용이
     * 지금 데이터와 맞지 않는 것이라 {@code APPLY_PAYLOAD_INVALID}다.
     */
    private static BusinessException applyError(BusinessException e) {
        ApplyErrorCode code;
        if (e.getErrorCode() == ObjectLinkErrorCode.LINK_ALREADY_EXISTS) {
            code = ApplyErrorCode.APPLY_TARGET_EXISTS;
        } else if (e.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND) {
            code = ApplyErrorCode.APPLY_REFERENCE_MISSING;
        } else if (e.getErrorCode() instanceof ObjectLinkErrorCode) {
            code = ApplyErrorCode.APPLY_PAYLOAD_INVALID;
        } else {
            return e;
        }
        Map<String, Object> details = new LinkedHashMap<>(e.getDetails());
        details.put("field", FIELD);
        details.put("linkError", e.getErrorCode().code());
        BusinessException mapped = new BusinessException(code, e.getMessage(), details);
        mapped.initCause(e);
        return mapped;
    }
}

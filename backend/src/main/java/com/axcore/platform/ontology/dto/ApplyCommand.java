package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 승인한 후보 하나를 반영하라는 요청 (Jira 131 → 189).
 *
 * @param proposalItemId 반영해 만든 행의 {@code proposal_item_id}가 된다
 * @param itemKind {@code proposal_item.item_kind}
 * @param origin {@code proposal_item.origin}. 만든 행의 출처(AI_PROPOSAL·MANUAL)를 정한다
 * @param payload 반영할 최종 내용. 선택지를 골랐으면 그 선택지의 payload, 아니면 현재 revision의 payload. 정의는 code로 가리킨다
 * @param decidedBy 승인한 계정. 만든 행의 {@code created_by}, 문서 연결의 {@code confirmed_by}가 된다
 */
public record ApplyCommand(
        long proposalItemId,
        ProposalItemKind itemKind,
        ProposalOrigin origin,
        Map<String, Object> payload,
        long decidedBy) {

    public ApplyCommand {
        if (proposalItemId < 1) {
            throw new IllegalArgumentException("proposalItemId는 1 이상입니다.");
        }
        Objects.requireNonNull(itemKind, "itemKind");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(payload, "payload");
        if (decidedBy < 1) {
            throw new IllegalArgumentException("decidedBy는 1 이상입니다.");
        }
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}

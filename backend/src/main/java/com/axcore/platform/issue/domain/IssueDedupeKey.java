package com.axcore.platform.issue.domain;

import java.util.Objects;

/**
 * 열린 문제를 하나로 묶는 키. 문제 종류 + 규칙 + 원천 행으로 만든다.
 *
 * <p>규칙은 개정본 ID가 아니라 규칙 코드로 가리킨다. 규칙을 개정해도 같은 행의 같은 문제는 열린 문제 하나로 남는다.
 */
public record IssueDedupeKey(String value) {

    public static final int MAX_LENGTH = 300;

    public IssueDedupeKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("dedupeKey가 비어 있습니다.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("dedupeKey는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
    }

    /** 반영된 연결 규칙이 객체 하나에서 낸 문제. 예: {@code UNMATCHED:rule:W2E:object:501} */
    public static IssueDedupeKey forRule(IssueKind kind, String linkMappingCode, long objectId) {
        requireText(linkMappingCode, "linkMappingCode");
        return new IssueDedupeKey(kind.name() + ":rule:" + linkMappingCode + ":object:" + objectId);
    }

    /** 승인 전 후보 시험이 원천 행 하나에서 낸 문제. 예: {@code UNMATCHED:proposal:7:row:W23} */
    public static IssueDedupeKey forProposal(IssueKind kind, long proposalItemId, String rowKey) {
        requireText(rowKey, "rowKey");
        return new IssueDedupeKey(kind.name() + ":proposal:" + proposalItemId + ":row:" + rowKey);
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있습니다.");
        }
    }
}

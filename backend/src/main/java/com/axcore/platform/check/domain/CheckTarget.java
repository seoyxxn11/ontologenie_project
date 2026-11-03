package com.axcore.platform.check.domain;

/** 승인된 규칙 개정본 하나 또는 후보 revision 하나를 가리키는 검사 대상. */
public record CheckTarget(Long linkMappingId, Long proposalItemId, Integer proposalItemRevision) {

    public CheckTarget {
        // 검사 결과의 대상이 모호해지지 않도록 승인 규칙과 후보 중 하나만 받는다.
        if ((linkMappingId == null) == (proposalItemId == null)) {
            throw new IllegalArgumentException("승인 규칙과 후보 중 하나를 지정해야 합니다.");
        }

        if ((proposalItemId == null) != (proposalItemRevision == null)) {
            throw new IllegalArgumentException("후보 ID와 검사할 revision은 함께 지정해야 합니다.");
        }

        if (linkMappingId != null && linkMappingId < 1) {
            throw new IllegalArgumentException("승인 규칙 ID는 1 이상이어야 합니다.");
        }

        if (proposalItemId != null && proposalItemId < 1) {
            throw new IllegalArgumentException("후보 ID는 1 이상이어야 합니다.");
        }

        if (proposalItemRevision != null && proposalItemRevision < 1) {
            throw new IllegalArgumentException("후보 revision은 1 이상이어야 합니다.");
        }
    }

    public static CheckTarget forLinkMapping(long linkMappingId) {
        // 승인 규칙 ID 자체가 내용이 불변인 규칙 개정본을 가리킨다.
        return new CheckTarget(linkMappingId, null, null);
    }

    public static CheckTarget forProposal(long proposalItemId, int revision) {
        // 후보가 수정되어도 이번 검사 대상으로 받은 revision을 유지한다.
        return new CheckTarget(null, proposalItemId, revision);
    }
}

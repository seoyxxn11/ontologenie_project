package com.axcore.platform.issue.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 정합성 문제 처리 상태. 신규 → 확인중 → 조치완료·재검증대기 → 해결(FN-ISS-03).
 *
 * <p>재검증이 실패하면 조치완료·재검증대기에서 확인중으로 돌아갈 수 있다. 해결은 끝 상태다. 같은 문제가 다시 나오면 새 문제로 등록된다.
 */
public enum IssueStatus {
    NEW,
    CHECKING,
    FIXED_PENDING_REVERIFY,
    RESOLVED;

    /** 관리자가 이 상태에서 바꿀 수 있는 다음 상태. */
    public Set<IssueStatus> nextStatuses() {
        return switch (this) {
            case NEW -> EnumSet.of(CHECKING);
            case CHECKING -> EnumSet.of(FIXED_PENDING_REVERIFY);
            case FIXED_PENDING_REVERIFY -> EnumSet.of(CHECKING, RESOLVED);
            case RESOLVED -> EnumSet.noneOf(IssueStatus.class);
        };
    }

    public boolean canMoveTo(IssueStatus next) {
        return nextStatuses().contains(next);
    }
}

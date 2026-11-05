package com.axcore.platform.issue.domain;

/** 정합성 문제 종류. 검사 결과의 미연결·중복·조건 위반 분류와 같다. */
public enum IssueKind {
    UNMATCHED,
    DUPLICATE,
    VIOLATION
}

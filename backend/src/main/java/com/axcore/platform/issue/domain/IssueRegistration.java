package com.axcore.platform.issue.domain;

/** 문제 등록 결과. created가 false면 같은 키의 열린 문제가 이미 있어 그 ID를 돌려준 것이다. */
public record IssueRegistration(long issueId, boolean created) {}

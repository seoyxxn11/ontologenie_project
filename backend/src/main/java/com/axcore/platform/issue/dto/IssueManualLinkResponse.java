package com.axcore.platform.issue.dto;

import com.axcore.platform.ontology.dto.SavedLink;

/** API 058 연결 저장 결과와 감사 실행 ID. */
public record IssueManualLinkResponse(
        long issueId, long linkId, SavedLink.Outcome outcome, long actionExecutionId) {}

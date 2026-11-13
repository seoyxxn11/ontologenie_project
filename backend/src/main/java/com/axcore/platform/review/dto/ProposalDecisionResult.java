package com.axcore.platform.review.dto;

import java.util.List;

/** API 054 execution result, separate from the API 055 decision history row. */
public record ProposalDecisionResult(
        long decisionId,
        String decision,
        String reviewStatus,
        String applyStatus,
        List<Long> appliedEntityIds,
        long actionExecutionId) {}

package com.axcore.platform.review.dto;

import java.time.Instant;

/**
 * 제한 컬럼의 값 확인 접수 결과(S15P31C106-447). 원천은 배치가 읽으므로 결과는 결과 조회로 받는다.
 *
 * @param requestId 값 확인 작업 ID
 * @param joined 같은 관리자의 같은 요청이 이미 대기·실행 중이어서 그 작업에 합쳤는가
 * @param expiresAt 결과를 읽지 않아도 지우는 시각
 */
public record RestrictedValueAcceptedResponse(
        long requestId, String status, boolean joined, Instant requestedAt, Instant expiresAt) {}

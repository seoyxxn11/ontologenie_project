package com.axcore.platform.check.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * API 060 응답. 검사 실행 필드에 합류·재사용 여부를 더한다.
 *
 * @param joined 대기·실행 중인 같은 기준의 검사에 합류했는지(HTTP 202)
 * @param reused 늘 false다. 사람이 요청한 검사는 끝난 결과를 다시 쓰지 않는다(389). 프런트가 이 필드를 검사해 남겨 둔다
 */
public record CheckRunRequestResponse(
        @JsonUnwrapped CheckRunResponse run, boolean joined, boolean reused) {}

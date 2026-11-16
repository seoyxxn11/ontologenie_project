package com.axcore.platform.review.dto;

import java.util.List;

/**
 * 제한 컬럼의 값 확인 결과(S15P31C106-447). SUCCEEDED일 때만 값과 건수가 있다. 이 응답을 한 번 돌려주면 서버는 결과를 지운다.
 *
 * @param values 서로 다른 값(빈 값 제외, 이름순, 최대 5개). 어느 행의 값인지는 담지 않는다
 * @param totalCount 전체 행 수
 * @param emptyCount 값이 비어 있는 행 수
 * @param distinctCount 서로 다른 값의 수
 * @param errorMessage FAILED일 때 이유
 */
public record RestrictedValueResultResponse(
        long requestId,
        long sourceResourceId,
        String column,
        String status,
        List<String> values,
        Long totalCount,
        Long emptyCount,
        Long distinctCount,
        String errorMessage) {}

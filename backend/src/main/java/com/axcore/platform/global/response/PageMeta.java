package com.axcore.platform.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

/** 목록 응답의 페이지 정보. scope는 조회 범위를 알려 줘야 하는 목록만 채운다(예: 096 실행·감사 이력의 ALL·MINE). 비어 있으면 응답에 나오지 않는다. */
public record PageMeta(
        int page,
        int size,
        long totalElements,
        boolean hasNext,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scope) {

    public PageMeta(int page, int size, long totalElements, boolean hasNext) {
        this(page, size, totalElements, hasNext, null);
    }

    public static PageMeta from(Page<?> page) {
        return from(page, null);
    }

    public static PageMeta from(Page<?> page, String scope) {
        return new PageMeta(
                page.getNumber(), page.getSize(), page.getTotalElements(), page.hasNext(), scope);
    }
}

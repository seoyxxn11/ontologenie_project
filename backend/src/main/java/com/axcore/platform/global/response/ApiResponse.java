package com.axcore.platform.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.data.domain.Page;

/** 성공 응답 공통 형식. 목록 조회만 meta를 담는다 (개발 가이드 8.2). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, PageMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<List<T>> ofPage(Page<T> page) {
        return new ApiResponse<>(page.getContent(), PageMeta.from(page));
    }

    /** 조회 범위(meta.scope)를 함께 알려 주는 목록. */
    public static <T> ApiResponse<List<T>> ofPage(Page<T> page, String scope) {
        return new ApiResponse<>(page.getContent(), PageMeta.from(page, scope));
    }
}

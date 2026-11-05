package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.Min;

/** API 037 조회 조건. sourceMappingId를 비우면 거르지 않는다. */
public record PropertyMappingSearchRequest(
        @Min(1) Long sourceMappingId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

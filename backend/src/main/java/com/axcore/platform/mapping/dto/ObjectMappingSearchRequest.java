package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.Min;

/** API 034 조회 조건. 비어 있는 조건으로는 거르지 않는다. */
public record ObjectMappingSearchRequest(
        @Min(1) Long sourceResourceId,
        @Min(1) Long objectTypeId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

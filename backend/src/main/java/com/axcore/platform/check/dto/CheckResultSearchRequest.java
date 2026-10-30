package com.axcore.platform.check.dto;

import jakarta.validation.constraints.Min;

/** API 062의 선택 필터와 페이지 조건. ID를 생략하면 해당 필터를 적용하지 않는다. */
public record CheckResultSearchRequest(
        @Min(value = 1, message = "linkMappingId는 1 이상이어야 합니다.") Long linkMappingId,
        @Min(value = 1, message = "proposalItemId는 1 이상이어야 합니다.") Long proposalItemId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

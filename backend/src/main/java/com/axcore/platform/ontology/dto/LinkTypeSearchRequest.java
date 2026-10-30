package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Min;

/** API 030 조회 조건. q는 코드·이름에서 대소문자 없이 찾는다. */
public record LinkTypeSearchRequest(
        String q,
        @Min(value = 1, message = "sourceTypeId는 1 이상이어야 합니다.") Long sourceTypeId,
        @Min(value = 1, message = "targetTypeId는 1 이상이어야 합니다.") Long targetTypeId,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

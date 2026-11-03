package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.ResourceKind;
import jakarta.validation.constraints.Min;

/** API 014 조회 조건. q는 자원 이름(resource_key)에서 대소문자 없이 찾는다. */
public record SourceResourceSearchRequest(
        ResourceKind resourceKind,
        Boolean isReadable,
        String q,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}

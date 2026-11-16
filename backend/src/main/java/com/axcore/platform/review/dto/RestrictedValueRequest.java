package com.axcore.platform.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 제한 컬럼의 값 확인 요청(S15P31C106-447). 그 제안이 가리키는 제한 컬럼만 받는다. */
public record RestrictedValueRequest(
        @NotNull @Min(1) Long sourceResourceId, @NotBlank @Size(max = 200) String column) {}

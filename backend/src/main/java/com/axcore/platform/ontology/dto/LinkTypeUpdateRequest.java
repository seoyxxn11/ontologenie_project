package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** API 032. 모든 칸을 보내는 전체 수정이다. 코드·양 끝 유형은 규칙·연결·보류 건이 없을 때만 바뀐다. */
public record LinkTypeUpdateRequest(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "영문 대문자로 시작하고 대문자·숫자·밑줄만 쓸 수 있습니다.")
                String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(1) Long sourceTypeId,
        @NotNull @Min(1) Long targetTypeId,
        @NotNull
                @Pattern(
                        regexp = "ONE_TO_ONE|ONE_TO_MANY|MANY_TO_ONE|MANY_TO_MANY",
                        message = "허용되지 않는 cardinality 값입니다.")
                String cardinality,
        @NotBlank String description,
        @NotNull @PositiveOrZero Long rowVersion) {}

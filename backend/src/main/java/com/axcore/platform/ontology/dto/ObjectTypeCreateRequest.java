package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** API 022. 대표 속성은 속성을 등록한 뒤 수정(024)에서 지정하므로 여기서 받지 않는다. */
public record ObjectTypeCreateRequest(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "영문 대문자로 시작하고 대문자·숫자·밑줄만 쓸 수 있습니다.")
                String code,
        @NotBlank @Size(max = 200) String name,
        String description,
        List<@NotBlank String> synonyms,
        @Size(max = 100) String accessGroup,
        @Null(message = "등록할 때는 지정할 수 없습니다. 속성을 등록한 뒤 수정에서 지정합니다.") Long displayPropertyId) {}

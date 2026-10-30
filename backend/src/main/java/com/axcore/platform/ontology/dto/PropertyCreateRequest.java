package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** API 027. 자료형·민감도·기준 시점 역할은 허용 값을 문자열로 검사한다. */
public record PropertyCreateRequest(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "[a-z][a-z0-9_]*", message = "영문 소문자로 시작하고 소문자·숫자·밑줄만 쓸 수 있습니다.")
                String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull
                @Pattern(
                        regexp = "STRING|INTEGER|DECIMAL|DATE|TIME|DATETIME|BOOLEAN|JSON",
                        message = "허용되지 않는 dataType 값입니다.")
                String dataType,
        @NotNull Boolean isRequired,
        Boolean isIdentifier,
        @Size(max = 50) String unit,
        Map<String, Object> constraints,
        @Pattern(regexp = "NORMAL|RESTRICTED", message = "허용되지 않는 sensitivity 값입니다.")
                String sensitivity,
        @Pattern(regexp = "AS_OF|EVENT_TIME", message = "허용되지 않는 temporalRole 값입니다.")
                String temporalRole,
        String description) {}

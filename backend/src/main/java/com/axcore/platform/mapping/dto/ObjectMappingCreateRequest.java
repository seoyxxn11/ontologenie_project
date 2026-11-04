package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** API 035. rowFilter는 지금 빈 조건({})만 받는다. */
public record ObjectMappingCreateRequest(
        @NotNull Long sourceResourceId,
        @NotNull Long objectTypeId,
        @NotEmpty List<@NotBlank String> keyColumns,
        Map<String, Object> rowFilter,
        Boolean isActive) {}

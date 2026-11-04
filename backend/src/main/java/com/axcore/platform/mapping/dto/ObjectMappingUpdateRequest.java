package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;

/** API 036. 원천 자원·객체 유형·키 컬럼은 늘 보낸다. isActive를 빼면 그대로 둔다. */
public record ObjectMappingUpdateRequest(
        @NotNull Long sourceResourceId,
        @NotNull Long objectTypeId,
        @NotEmpty List<@NotBlank String> keyColumns,
        Map<String, Object> rowFilter,
        Boolean isActive,
        @NotNull @PositiveOrZero Long rowVersion) {}

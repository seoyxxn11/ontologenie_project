package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/** API 039. 등록과 같은 값을 모두 보내 통째로 바꾼다. 변환 단계를 빼면 빈 목록으로 바꾼다. */
public record PropertyMappingUpdateRequest(
        @NotNull Long sourceMappingId,
        @NotNull Long sourceResourceId,
        @NotNull Long propertyDefinitionId,
        Long sourceColumnId,
        String sourcePath,
        List<@NotNull TransformStepPayload> transformSteps,
        @NotNull @PositiveOrZero Long rowVersion) {}

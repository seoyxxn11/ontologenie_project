package com.axcore.platform.mapping.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** API 038. 지금은 표 컬럼(sourceColumnId)만 받고 문서 경로(sourcePath)는 받지 않는다. 변환 단계를 빼면 빈 목록이다. */
public record PropertyMappingCreateRequest(
        @NotNull Long sourceMappingId,
        @NotNull Long sourceResourceId,
        @NotNull Long propertyDefinitionId,
        Long sourceColumnId,
        String sourcePath,
        List<@NotNull TransformStepPayload> transformSteps) {}

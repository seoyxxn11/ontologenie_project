package com.axcore.platform.mapping.dto;

import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** API 명세의 PropertyMapping (037). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PropertyMappingResponse(
        Long id,
        Long sourceMappingId,
        Long sourceResourceId,
        Long propertyDefinitionId,
        Long sourceColumnId,
        String sourcePath,
        List<TransformStepPayload> transformSteps,
        long rowVersion) {

    public static PropertyMappingResponse from(PropertySourceMapping mapping) {
        return new PropertyMappingResponse(
                mapping.getId(),
                mapping.getSourceMappingId(),
                mapping.getSourceResourceId(),
                mapping.getPropertyDefinitionId(),
                mapping.getSourceColumnId(),
                mapping.getSourcePath(),
                mapping.getTransformSteps().stream().map(TransformStepPayload::from).toList(),
                mapping.getRowVersion());
    }
}

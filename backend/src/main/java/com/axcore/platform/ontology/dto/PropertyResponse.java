package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.domain.TemporalRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** API 명세의 Property (026~028). */
public record PropertyResponse(
        Long id,
        Long objectTypeId,
        String code,
        String name,
        PropertyDataType dataType,
        boolean isRequired,
        boolean isIdentifier,
        String unit,
        Map<String, Object> constraints,
        PropertySensitivity sensitivity,
        TemporalRole temporalRole,
        long rowVersion,
        DefinitionOrigin origin,
        Long proposalItemId,
        String description) {

    public static PropertyResponse from(PropertyDefinition property) {
        return new PropertyResponse(
                property.getId(),
                property.getObjectTypeId(),
                property.getCode(),
                property.getName(),
                property.getDataType(),
                property.isRequired(),
                property.isIdentifier(),
                property.getUnit(),
                Collections.unmodifiableMap(new LinkedHashMap<>(property.getConstraints())),
                property.getSensitivity(),
                property.getTemporalRole(),
                property.getRowVersion(),
                property.getOrigin(),
                property.getProposalItemId(),
                property.getDescription());
    }
}

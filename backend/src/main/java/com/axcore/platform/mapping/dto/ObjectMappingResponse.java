package com.axcore.platform.mapping.dto;

import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API 명세의 ObjectMapping (034·035). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ObjectMappingResponse(
        Long id,
        Long sourceResourceId,
        Long objectTypeId,
        List<String> keyColumns,
        Map<String, Object> rowFilter,
        long rowVersion,
        DefinitionOrigin origin,
        Long proposalItemId,
        boolean isActive) {

    public static ObjectMappingResponse from(ObjectSourceMapping mapping) {
        return new ObjectMappingResponse(
                mapping.getId(),
                mapping.getSourceResourceId(),
                mapping.getObjectTypeId(),
                List.copyOf(mapping.getKeyColumns()),
                new LinkedHashMap<>(mapping.getRowFilter()),
                mapping.getRowVersion(),
                mapping.getOrigin(),
                mapping.getProposalItemId(),
                mapping.isActive());
    }
}

package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.ObjectType;
import java.util.List;

/** API 명세의 OntologyType (021~024). */
public record ObjectTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        List<String> synonyms,
        String accessGroup,
        Long displayPropertyId,
        DefinitionOrigin origin,
        long rowVersion,
        Long proposalItemId) {

    public static ObjectTypeResponse from(ObjectType objectType) {
        return new ObjectTypeResponse(
                objectType.getId(),
                objectType.getCode(),
                objectType.getName(),
                objectType.getDescription(),
                List.copyOf(objectType.getSynonyms()),
                objectType.getAccessGroup(),
                objectType.getDisplayPropertyId(),
                objectType.getOrigin(),
                objectType.getRowVersion(),
                objectType.getProposalItemId());
    }
}

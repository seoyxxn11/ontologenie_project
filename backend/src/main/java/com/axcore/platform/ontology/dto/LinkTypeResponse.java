package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.LinkType;

/** API 명세의 LinkType (030~032). */
public record LinkTypeResponse(
        Long id,
        String code,
        String name,
        Long sourceTypeId,
        Long targetTypeId,
        LinkCardinality cardinality,
        String description,
        long rowVersion,
        DefinitionOrigin origin,
        Long proposalItemId) {

    public static LinkTypeResponse from(LinkType linkType) {
        return new LinkTypeResponse(
                linkType.getId(),
                linkType.getCode(),
                linkType.getName(),
                linkType.getSourceTypeId(),
                linkType.getTargetTypeId(),
                linkType.getCardinality(),
                linkType.getDescription(),
                linkType.getRowVersion(),
                linkType.getOrigin(),
                linkType.getProposalItemId());
    }
}

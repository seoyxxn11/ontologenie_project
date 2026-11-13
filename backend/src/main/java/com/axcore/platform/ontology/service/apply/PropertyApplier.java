package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PROPERTY 후보 반영(Jira 189 1단계). {@code objectTypeCode}의 유형에 속성 하나를 만든다. */
@Component
@RequiredArgsConstructor
class PropertyApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final ApplyWriter writer;
    private final PropertyDefinitionRepository properties;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.PROPERTY;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        ObjectType type =
                support.objectType(
                        support.text(payload, "objectTypeCode", FIELD), FIELD + ".objectTypeCode");
        String code = support.text(payload, "code", FIELD);
        if (properties.existsByObjectTypeIdAndCode(type.getId(), code)) {
            throw ApplySupport.exists(FIELD + ".code", type.getCode() + "." + code);
        }
        ApplyWriter.Written written =
                writer.property(
                        type,
                        payload,
                        FIELD,
                        DefinitionSource.fromProposal(command.origin(), command.proposalItemId()));
        return new ApplyResult(
                "property_definition",
                written.id(),
                List.of(),
                Map.of("objectTypeCode", type.getCode(), "propertyCode", code),
                List.of(written.change()));
    }
}

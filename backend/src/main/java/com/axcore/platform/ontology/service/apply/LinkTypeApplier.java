package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LINK_TYPE 후보 반영(Jira 189 1단계). 만드는 일은 {@link ApplyWriter#linkType}이 하고, AI 관계 후보(LINK_RULE)에 함께 온
 * {@code linkType}도 같은 메서드로 만든다.
 */
@Component
@RequiredArgsConstructor
class LinkTypeApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplyWriter writer;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.LINK_TYPE;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        ApplyWriter.Written written =
                writer.linkType(
                        command.payload(),
                        FIELD,
                        DefinitionSource.fromProposal(command.origin(), command.proposalItemId()));
        return new ApplyResult(
                "link_type",
                written.id(),
                List.of(),
                Map.of("linkTypeCode", command.payload().get("code")),
                List.of(written.change()));
    }
}

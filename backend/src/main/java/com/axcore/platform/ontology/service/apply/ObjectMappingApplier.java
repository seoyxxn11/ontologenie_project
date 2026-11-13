package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * OBJECT_MAPPING 후보 반영(Jira 189 1단계). {@code objectTypeCode}의 유형을 {@code sourceId}·{@code
 * resourceKey} 표에서 만드는 켜진 매핑을 추가하고 그 표의 전체 다시 읽기를 요청한다. 같은 표·유형의 켜진 매핑이 이미 있으면 {@code
 * APPLY_TARGET_EXISTS}다.
 */
@Component
@RequiredArgsConstructor
class ObjectMappingApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final ApplyWriter writer;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.OBJECT_MAPPING;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        ObjectType type =
                support.objectType(
                        support.text(payload, "objectTypeCode", FIELD), FIELD + ".objectTypeCode");
        ApplyWriter.WrittenMapping written =
                writer.objectMapping(
                        type,
                        payload,
                        FIELD,
                        DefinitionSource.fromProposal(command.origin(), command.proposalItemId()));
        return new ApplyResult(
                "object_source_mapping",
                written.id(),
                List.of(written.sourceResourceId()),
                Map.of(
                        "objectTypeCode",
                        type.getCode(),
                        "sourceResourceId",
                        written.sourceResourceId()),
                List.of(written.change()));
    }
}

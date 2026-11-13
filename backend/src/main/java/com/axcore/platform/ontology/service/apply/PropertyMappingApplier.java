package com.axcore.platform.ontology.service.apply;

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
 * PROPERTY_MAPPING 후보 반영(Jira 189 1단계). {@code objectTypeCode}·{@code propertyCode}의 속성을 {@code
 * sourceColumn} {sourceId, resourceKey, column} 컬럼에 잇는다. 부모는 그 표에서 이 유형을 만드는 켜진 객체 매핑이다. 속성 매핑 표에는
 * 출처 칸이 없다.
 */
@Component
@RequiredArgsConstructor
class PropertyMappingApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final ApplyWriter writer;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.PROPERTY_MAPPING;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        // payload 형식을 먼저 보고, 그다음 가리키는 정의를 찾는다.
        String typeCode = support.text(payload, "objectTypeCode", FIELD);
        String propertyCode = support.text(payload, "propertyCode", FIELD);
        Map<String, Object> sourceColumn = support.optionalObject(payload, "sourceColumn", FIELD);
        if (sourceColumn == null) {
            throw ApplySupport.invalid(FIELD + ".sourceColumn", "원천 컬럼을 지정해야 합니다.");
        }
        List<Map<String, Object>> steps = support.transformSteps(payload, FIELD);
        ObjectType type = support.objectType(typeCode, FIELD + ".objectTypeCode");
        ApplyWriter.WrittenMapping written =
                writer.propertyMapping(
                        type,
                        support.property(type, propertyCode, FIELD + ".propertyCode"),
                        sourceColumn,
                        steps,
                        FIELD);
        return new ApplyResult(
                "property_source_mapping",
                written.id(),
                List.of(written.sourceResourceId()),
                Map.of("objectTypeCode", type.getCode(), "propertyCode", propertyCode),
                List.of(written.change()));
    }
}

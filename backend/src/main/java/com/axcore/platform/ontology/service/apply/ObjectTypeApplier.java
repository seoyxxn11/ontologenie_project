package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.dto.ObjectTypeCreateRequest;
import com.axcore.platform.ontology.dto.ObjectTypeResponse;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.service.ObjectTypeService;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * OBJECT_TYPE 후보 반영(Jira 189 1단계). 객체 유형 칸만 있으면 유형 하나를 만든다. AI 묶음 후보(API-105)는 {@code
 * properties[]}와 {@code objectMapping}을 함께 담으므로 유형 → 속성 → 객체 매핑 → 속성 매핑 순서로 한 번에 만든다. 하나라도 실패하면 묶음
 * 전체가 롤백된다. {@code displayPropertyCode}가 있으면 묶음 속성 중 그 코드를 대표 속성으로 지정한다(S15P31C106-370). 대표 속성이 없으면
 * 수집한 객체의 표시 이름이 원천 키 값이 된다.
 */
@Component
@RequiredArgsConstructor
class ObjectTypeApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final ApplyWriter writer;
    private final ObjectTypeService objectTypeService;
    private final ObjectTypeRepository objectTypes;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.OBJECT_TYPE;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        DefinitionSource source =
                DefinitionSource.fromProposal(command.origin(), command.proposalItemId());
        String code = support.text(payload, "code", FIELD);
        if (objectTypes.existsByCode(code)) {
            throw ApplySupport.exists(FIELD + ".code", code);
        }
        // 속성에 원천 컬럼이 있으면 그 부모가 될 객체 매핑(키 컬럼)이 같이 와야 한다. 새 유형이라 기존 매핑도 없다.
        // AI는 식별 키를 찾지 못한 표에는 객체 매핑을 만들지 않으므로, 먼저 승인할 후보가 없다는 것을 분명히 알린다.
        if (payload.get("objectMapping") == null
                && support.optionalObjects(payload, "properties", FIELD).stream()
                        .anyMatch(property -> property.get("sourceColumn") != null)) {
            throw ApplySupport.invalid(
                    FIELD + ".objectMapping", "속성에 원천 컬럼이 있으면 객체 매핑(키 컬럼)이 함께 있어야 합니다.");
        }
        ObjectTypeCreateRequest request =
                support.valid(
                        new ObjectTypeCreateRequest(
                                code,
                                support.text(payload, "name", FIELD),
                                support.optionalText(payload, "description", FIELD),
                                support.optionalTexts(payload, "synonyms", FIELD),
                                support.optionalText(payload, "accessGroup", FIELD),
                                null),
                        FIELD);
        ObjectTypeResponse created =
                support.call(FIELD, () -> objectTypeService.create(request, source));
        ObjectType type = objectTypes.findById(created.id()).orElseThrow();

        List<ChangeEntry> changes = new ArrayList<>();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", code);
        after.put("name", request.name());
        changes.add(ChangeEntry.insert("object_type", Map.of("id", type.getId()), after));

        // 묶음: 속성 → 객체 매핑 → 속성 매핑(선행 순서, docs/proposal-apply.md)
        List<Map<String, Object>> bundled = support.optionalObjects(payload, "properties", FIELD);
        for (int i = 0; i < bundled.size(); i++) {
            changes.add(
                    writer.property(type, bundled.get(i), FIELD + ".properties[" + i + "]", source)
                            .change());
        }
        Set<Long> reload = new LinkedHashSet<>();
        Long objectMappingId = null;
        Map<String, Object> mapping = support.optionalObject(payload, "objectMapping", FIELD);
        if (mapping != null) {
            ApplyWriter.WrittenMapping written =
                    writer.objectMapping(type, mapping, FIELD + ".objectMapping", source);
            objectMappingId = written.id();
            reload.add(written.sourceResourceId());
            changes.add(written.change());
        }
        int propertyMappings = 0;
        for (int i = 0; i < bundled.size(); i++) {
            String field = FIELD + ".properties[" + i + "]";
            Map<String, Object> sourceColumn =
                    support.optionalObject(bundled.get(i), "sourceColumn", field);
            if (sourceColumn == null) {
                continue;
            }
            String propertyCode = support.text(bundled.get(i), "code", field);
            ApplyWriter.WrittenMapping written =
                    writer.propertyMapping(
                            type,
                            support.property(type, propertyCode, field + ".code"),
                            sourceColumn,
                            support.transformSteps(bundled.get(i), field),
                            field);
            reload.add(written.sourceResourceId());
            changes.add(written.change());
            propertyMappings++;
        }

        // 대표 속성: 등록 API(022)는 속성이 없어 지정할 수 없으므로, 묶음 속성을 만든 뒤 같은 트랜잭션에서 지정한다
        String displayCode = support.optionalText(payload, "displayPropertyCode", FIELD);
        if (displayCode != null) {
            String field = FIELD + ".displayPropertyCode";
            PropertyDefinition display = support.property(type, displayCode, field);
            if (display.getSensitivity() == PropertySensitivity.RESTRICTED) {
                throw ApplySupport.invalid(field, "민감 속성은 대표 속성으로 지정할 수 없습니다.");
            }
            type.update(
                    type.getName(),
                    type.getDescription(),
                    type.getSynonyms(),
                    type.getAccessGroup(),
                    display.getId());
            after.put("displayPropertyId", display.getId());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("objectTypeCode", code);
        summary.put("propertyCount", bundled.size());
        summary.put("objectMappingId", objectMappingId);
        summary.put("propertyMappingCount", propertyMappings);
        summary.put("displayPropertyCode", displayCode);
        return new ApplyResult("object_type", type.getId(), List.copyOf(reload), summary, changes);
    }
}

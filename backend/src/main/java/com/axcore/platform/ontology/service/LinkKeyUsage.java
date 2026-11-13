package com.axcore.platform.ontology.service;

import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 속성이 현재 쓰는(ACTIVE) 연결 규칙에 쓰이는지 본다(384·391). 조건 키와 출발·도착 조건({@code {property, equals}}) 둘 다 본다. 이런
 * 속성은 민감 속성이 될 수 없다. 객체를 잇는 값이라 가리면 연결이 끊기고, 가리지 않으면 민감 값이 연결 근거나 규칙 정의로 드러난다.
 */
@Component
@RequiredArgsConstructor
public class LinkKeyUsage {

    private final LinkMappingConditionRepository conditionRepository;
    private final LinkMappingRepository linkMappingRepository;
    private final ObjectTypeRepository objectTypeRepository;

    public boolean isUsed(PropertyDefinition property) {
        if (property.getId() == null) {
            return false;
        }
        if (conditionRepository.existsActiveForProperty(property.getId())) {
            return true;
        }
        // 규칙의 조건은 속성 코드나 "유형코드.속성코드"로 저장된다(LinkRuleApplier).
        return objectTypeRepository
                .findById(property.getObjectTypeId())
                .map(ObjectType::getCode)
                .map(
                        typeCode ->
                                linkMappingRepository.existsActiveFilterOn(
                                        property.getObjectTypeId(),
                                        property.getCode(),
                                        typeCode + "." + property.getCode()))
                .orElse(false);
    }
}

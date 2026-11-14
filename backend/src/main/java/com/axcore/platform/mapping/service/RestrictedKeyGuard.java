package com.axcore.platform.mapping.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.repository.SourceColumnRepository;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 민감 속성은 객체 매핑의 키 컬럼에 매핑될 수 없다(384·401). 키 값은 객체의 원천 키로 저장되고 대표 속성이 없거나 가려지면 표시 이름으로도 쓰인다. 원천 키 가림은
 * 그 키 컬럼에 민감 속성이 매핑돼 있을 때만 동작하므로, 속성 매핑을 지우거나 속성을 일반으로 내리는 순간 저장된 키 값이 드러난다. 391은 민감 등급 원천 컬럼을 키로
 * 쓰지 못하게 했고, 여기서는 사람이 민감으로 지정한 속성까지 같은 원칙을 적용한다.
 */
@Component
@RequiredArgsConstructor
public class RestrictedKeyGuard {

    private final PropertySourceMappingRepository propertyMappingRepository;
    private final ObjectSourceMappingRepository objectMappingRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final SourceColumnRepository columnRepository;

    /** 속성 매핑을 만들거나 바꿀 때. 속성이 민감인데 컬럼이 부모 객체 매핑의 키이면 거절한다. */
    public void requireNotKey(
            PropertyDefinition property, ObjectSourceMapping parent, String column, String field) {
        if (property.getSensitivity() == PropertySensitivity.RESTRICTED
                && parent.getKeyColumns().contains(column)) {
            throw restrictedKey(field, column);
        }
    }

    /** 속성을 민감으로 올릴 때. 그 속성의 매핑 중 하나라도 부모 객체 매핑의 키 컬럼을 담으면 거절한다. */
    public void requireNoKeyMapping(long propertyId, String field) {
        for (PropertySourceMapping mapping :
                propertyMappingRepository.findByPropertyDefinitionId(propertyId)) {
            String column = columnName(mapping);
            if (column != null
                    && objectMappingRepository
                            .findById(mapping.getSourceMappingId())
                            .map(parent -> parent.getKeyColumns().contains(column))
                            .orElse(false)) {
                throw restrictedKey(field, column);
            }
        }
    }

    /** 객체 매핑의 키를 바꿀 때. 그 매핑 아래에서 민감 속성이 담는 컬럼을 키로 쓰면 거절한다. */
    public void requireKeysNotRestricted(
            long objectMappingId, Collection<String> keyColumns, String field) {
        for (PropertySourceMapping mapping :
                propertyMappingRepository.findBySourceMappingIdInOrderById(
                        java.util.List.of(objectMappingId))) {
            String column = columnName(mapping);
            if (column != null
                    && keyColumns.contains(column)
                    && propertyRepository
                            .findById(mapping.getPropertyDefinitionId())
                            .map(p -> p.getSensitivity() == PropertySensitivity.RESTRICTED)
                            .orElse(false)) {
                throw restrictedKey(field, column);
            }
        }
    }

    private String columnName(PropertySourceMapping mapping) {
        if (mapping.getSourceColumnId() == null) {
            return null;
        }
        return columnRepository
                .findById(mapping.getSourceColumnId())
                .map(SourceColumn::getColumnName)
                .orElse(null);
    }

    private static BusinessException restrictedKey(String field, String column) {
        return new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                "민감 속성은 객체 매핑의 키 컬럼에 매핑할 수 없습니다. 키 값은 원천 키·표시 이름으로 남아 매핑을 바꾸면 가려지지 않습니다.",
                Map.of("field", field, "column", column, "reason", "RESTRICTED_KEY"));
    }
}

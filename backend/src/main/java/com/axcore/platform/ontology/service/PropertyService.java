package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.PropertyConstraints;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.domain.TemporalRole;
import com.axcore.platform.ontology.dto.DefinitionReference;
import com.axcore.platform.ontology.dto.PropertyCreateRequest;
import com.axcore.platform.ontology.dto.PropertyResponse;
import com.axcore.platform.ontology.dto.PropertyUpdateRequest;
import com.axcore.platform.ontology.repository.DefinitionReferenceRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.repository.PropertyValueRepository;
import com.axcore.platform.ontology.repository.PropertyValueRepository.ValueConflict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyService {

    private final ObjectTypeRepository objectTypeRepository;
    private final PropertyDefinitionRepository propertyDefinitionRepository;
    private final PropertyValueRepository propertyValueRepository;
    private final DefinitionReferenceRepository referenceRepository;

    /** API 026. 등록 순서로 돌려준다. */
    public List<PropertyResponse> findProperties(Long objectTypeId) {
        requireObjectType(objectTypeId);
        return propertyDefinitionRepository.findByObjectTypeIdOrderById(objectTypeId).stream()
                .map(PropertyResponse::from)
                .toList();
    }

    /** API 027. 코드는 같은 유형 안에서 유일하다. 동시에 같은 코드로 등록하면 DB 제약이 하나만 남긴다. */
    @Transactional
    public PropertyResponse create(Long objectTypeId, PropertyCreateRequest request) {
        requireObjectType(objectTypeId);
        PropertyDataType dataType = PropertyDataType.valueOf(request.dataType());
        TemporalRole temporalRole = enumOrNull(TemporalRole.class, request.temporalRole());
        Map<String, Object> constraints = constraintsOrEmpty(request.constraints());
        validateDefinition(dataType, temporalRole, constraints);
        if (propertyDefinitionRepository.existsByObjectTypeIdAndCode(
                objectTypeId, request.code())) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        PropertyDefinition property =
                PropertyDefinition.manual()
                        .objectTypeId(objectTypeId)
                        .code(request.code())
                        .name(request.name())
                        .dataType(dataType)
                        .description(request.description())
                        .identifier(Boolean.TRUE.equals(request.isIdentifier()))
                        .required(request.isRequired())
                        .unit(request.unit())
                        .constraints(constraints)
                        .sensitivity(enumOrNull(PropertySensitivity.class, request.sensitivity()))
                        .temporalRole(temporalRole)
                        .build();
        return PropertyResponse.from(propertyDefinitionRepository.saveAndFlush(property));
    }

    /**
     * API 028. 코드는 바꿀 수 없다. 자료형·필수·제약을 바꾸면 이미 저장된 객체 값이 새 정의에 맞는지 먼저 확인하고, 맞지 않으면 422와 맞지 않는 객체를
     * 돌려준다.
     */
    @Transactional
    public PropertyResponse update(Long id, PropertyUpdateRequest request) {
        if (request.hasCode()) {
            throw BusinessException.invalidField("code", "속성 코드는 바꿀 수 없습니다. 새 속성을 등록하세요.");
        }
        PropertyDefinition property = findProperty(id);
        if (request.getRowVersion() != property.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.getRowVersion(),
                            "actual", property.getRowVersion()));
        }
        PropertyDataType dataType = PropertyDataType.valueOf(request.getDataType());
        TemporalRole temporalRole =
                request.hasTemporalRole()
                        ? enumOrNull(TemporalRole.class, request.getTemporalRole())
                        : property.getTemporalRole();
        Map<String, Object> constraints =
                request.getConstraints() == null
                        ? property.getConstraints()
                        : new LinkedHashMap<>(request.getConstraints());
        validateDefinition(dataType, temporalRole, constraints);
        checkExistingValues(property, dataType, request.getIsRequired(), constraints);

        property.update(
                request.getName(),
                dataType,
                request.hasDescription() ? request.getDescription() : property.getDescription(),
                request.getIsIdentifier() == null
                        ? property.isIdentifier()
                        : request.getIsIdentifier(),
                request.getIsRequired(),
                request.hasUnit() ? request.getUnit() : property.getUnit(),
                constraints,
                request.getSensitivity() == null
                        ? property.getSensitivity()
                        : PropertySensitivity.valueOf(request.getSensitivity()),
                temporalRole);
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        propertyDefinitionRepository.flush();
        return PropertyResponse.from(property);
    }

    /** API 029. 참조가 하나라도 있으면 지우지 않고 참조 목록을 돌려준다. */
    @Transactional
    public void delete(Long id) {
        PropertyDefinition property =
                propertyDefinitionRepository.findForUpdate(id).orElseThrow(() -> notFound(id));
        List<DefinitionReference> references = referenceRepository.findPropertyReferences(id);
        if (!references.isEmpty()) {
            throw new BusinessException(CommonErrorCode.IN_USE, Map.of("references", references));
        }
        propertyDefinitionRepository.delete(property);
    }

    /** 기준 시점 역할은 날짜·시각 속성에만, 제약은 자료형에 맞는 것만 받는다(DDL CHECK와 같은 조건을 먼저 400으로 알린다). */
    private static void validateDefinition(
            PropertyDataType dataType, TemporalRole temporalRole, Map<String, Object> constraints) {
        if (temporalRole != null && !dataType.isTemporal()) {
            throw BusinessException.invalidField(
                    "temporalRole", "temporalRole은 dataType이 DATE·TIME·DATETIME일 때만 지정할 수 있습니다.");
        }
        PropertyConstraints.violation(constraints, dataType)
                .ifPresent(
                        reason -> {
                            throw BusinessException.invalidField("constraints", reason);
                        });
    }

    /** 바뀌는 항목만 확인한다. 자료형이 다른 값은 제약 검사에서 빼고 자료형 검사에서 알린다. */
    private void checkExistingValues(
            PropertyDefinition property,
            PropertyDataType dataType,
            boolean required,
            Map<String, Object> constraints) {
        Long objectTypeId = property.getObjectTypeId();
        String code = property.getCode();
        if (dataType != property.getDataType()) {
            rejectIfConflict(
                    "dataType",
                    "기존 객체 값이 새 자료형에 맞지 않습니다.",
                    propertyValueRepository.findTypeMismatches(objectTypeId, code, dataType));
        }
        if (required && !property.isRequired()) {
            rejectIfConflict(
                    "isRequired",
                    "값이 없는 기존 객체가 있어 필수로 바꿀 수 없습니다.",
                    propertyValueRepository.findMissingValues(objectTypeId, code));
        }
        PropertyConstraints parsed = PropertyConstraints.from(constraints);
        if (!parsed.isEmpty()
                && (dataType != property.getDataType()
                        || !Objects.equals(constraints, property.getConstraints()))) {
            rejectIfConflict(
                    "constraints",
                    "기존 객체 값이 새 제약에 맞지 않습니다.",
                    propertyValueRepository.findConstraintViolations(
                            objectTypeId, code, dataType, parsed));
        }
    }

    private static void rejectIfConflict(String field, String reason, ValueConflict conflict) {
        if (conflict.exists()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    reason,
                    Map.of(
                            "field",
                            field,
                            "reason",
                            reason,
                            "count",
                            conflict.count(),
                            "objectIds",
                            conflict.objectIds()));
        }
    }

    private void requireObjectType(Long objectTypeId) {
        if (!objectTypeRepository.existsById(objectTypeId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "object_type", "id", objectTypeId));
        }
    }

    private PropertyDefinition findProperty(Long id) {
        return propertyDefinitionRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "property_definition", "id", id));
    }

    private static Map<String, Object> constraintsOrEmpty(Map<String, Object> constraints) {
        return constraints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(constraints);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}

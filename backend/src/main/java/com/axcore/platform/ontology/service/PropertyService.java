package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.mapping.service.MappedResourceReload;
import com.axcore.platform.ontology.domain.DefinitionSource;
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
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final PropertySourceMappingRepository propertyMappingRepository;
    private final SourceColumnRepository sourceColumnRepository;
    private final SourceResourceRepository sourceResourceRepository;
    private final SourceColumnPolicy sourceColumnPolicy;
    private final LinkKeyUsage linkKeyUsage;
    private final MappedResourceReload mappedResourceReload;

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
        return create(objectTypeId, request, DefinitionSource.MANUAL);
    }

    /** 승인한 후보 반영(189)도 이 경로로 만든다. 출처만 다르다. */
    @Transactional
    public PropertyResponse create(
            Long objectTypeId, PropertyCreateRequest request, DefinitionSource source) {
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
        rejectRestrictedKey(property, property.isIdentifier(), property.getSensitivity());
        property.recordSource(source);
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
        // 민감으로 올리면 이 속성이 매핑된 표를 다시 읽는다(391, MappedResourceReload). 매핑 변경·수집과 같은 순서(원천 자원 → 정의)로
        // 잠그려고
        // 속성 행보다 먼저 원천 자원을 잠근다.
        List<SourceResource> mappedResources =
                PropertySensitivity.RESTRICTED.name().equals(request.getSensitivity())
                        ? mappedResourceReload.lockMappedResources(id, Set.of())
                        : List.of();
        // 024(대표 속성 지정)와 같은 행 잠금을 잡는다. 대표 지정과 민감 전환이 동시에 와도 뒤의 요청이 최신 값을 보고 거절된다.
        PropertyDefinition property =
                propertyDefinitionRepository.findForUpdate(id).orElseThrow(() -> notFound(id));
        PropertySensitivity before = property.getSensitivity();
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
        PropertySensitivity sensitivity =
                request.getSensitivity() == null
                        ? property.getSensitivity()
                        : PropertySensitivity.valueOf(request.getSensitivity());
        rejectRestrictedDisplayProperty(property, sensitivity);
        rejectBelowSourcePolicy(property, sensitivity);
        rejectRestrictedKey(
                property,
                request.getIsIdentifier() == null
                        ? property.isIdentifier()
                        : request.getIsIdentifier(),
                sensitivity);

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
                sensitivity,
                temporalRole);
        // 391 이전 매핑 때문에 적재 계획이 실패해 CDC가 건너뛴 변경을 되찾는다. 이미 민감이었으면 다시 읽지 않는다.
        if (before != PropertySensitivity.RESTRICTED
                && sensitivity == PropertySensitivity.RESTRICTED) {
            mappedResourceReload.requireFullReload(mappedResources);
        }
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

    /**
     * 속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다(384·391). 민감 컬럼(RESTRICTED)을 담는 속성을 일반으로 내리면 거절한다. 등급을 낮추려면 원천
     * 데이터 정책(application.yml)을 바꾼다. 사유 없이 화면에서 낮추지 못하게 한다.
     */
    private void rejectBelowSourcePolicy(
            PropertyDefinition property, PropertySensitivity sensitivity) {
        if (sensitivity == PropertySensitivity.RESTRICTED) {
            return;
        }
        List<PropertySourceMapping> mappings =
                propertyMappingRepository.findByPropertyDefinitionId(property.getId());
        for (PropertySourceMapping mapping : mappings) {
            if (mapping.getSourceColumnId() == null) {
                continue;
            }
            SourceColumn column =
                    sourceColumnRepository.findById(mapping.getSourceColumnId()).orElse(null);
            SourceResource resource =
                    sourceResourceRepository.findById(mapping.getSourceResourceId()).orElse(null);
            if (column != null
                    && resource != null
                    && sourceColumnPolicy.gradeOf(resource.getResourceKey(), column.getColumnName())
                            == SourceColumnGrade.RESTRICTED) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "원천 데이터 정책상 민감 컬럼을 담는 속성이라 일반으로 내릴 수 없습니다.",
                        Map.of(
                                "field",
                                "sensitivity",
                                "resourceKey",
                                resource.getResourceKey(),
                                "column",
                                column.getColumnName()));
            }
        }
    }

    /**
     * 식별 속성과 연결 규칙의 조건(키·출발·도착 조건)에 쓰는 속성은 민감 속성이 될 수 없다(384·391). 객체를 찾고 잇는 값이라 가리면 조회·연결이 끊기고,
     * 가리지 않으면 민감 값이 연결 근거로 드러난다. 민감한 원천 값은 키가 아닌 속성에 담는다.
     */
    private void rejectRestrictedKey(
            PropertyDefinition property, boolean identifier, PropertySensitivity sensitivity) {
        if (sensitivity != PropertySensitivity.RESTRICTED) {
            return;
        }
        if (identifier) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "식별 속성은 민감 속성이 될 수 없습니다.",
                    Map.of("field", "sensitivity", "reason", "IDENTIFIER"));
        }
        if (linkKeyUsage.isUsed(property)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "연결 규칙의 조건에 쓰는 속성은 민감 속성이 될 수 없습니다.",
                    Map.of("field", "sensitivity", "reason", "LINK_KEY"));
        }
    }

    /** 대표 속성 값은 객체 표시 이름이 되므로 민감 속성이 될 수 없다(024와 같은 규칙, S15P31C106-205 결정 D2). */
    private void rejectRestrictedDisplayProperty(
            PropertyDefinition property, PropertySensitivity sensitivity) {
        if (sensitivity != PropertySensitivity.RESTRICTED
                || property.getSensitivity() == PropertySensitivity.RESTRICTED) {
            return;
        }
        boolean display =
                objectTypeRepository
                        .findById(property.getObjectTypeId())
                        .map(type -> property.getId().equals(type.getDisplayPropertyId()))
                        .orElse(false);
        if (display) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "대표 속성은 민감 속성으로 바꿀 수 없습니다. 대표 속성을 먼저 바꾸세요.",
                    Map.of("field", "sensitivity"));
        }
    }

    private void requireObjectType(Long objectTypeId) {
        if (!objectTypeRepository.existsById(objectTypeId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "object_type", "id", objectTypeId));
        }
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

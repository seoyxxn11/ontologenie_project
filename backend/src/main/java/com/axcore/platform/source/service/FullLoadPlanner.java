package com.axcore.platform.source.service;

import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.FullLoadPlan;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.ClosedTransform;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.policy.SourcePolicyViolation;
import com.axcore.platform.source.policy.SourcePolicyViolation.Kind;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원천 테이블의 켜진 매핑으로 전체 읽기 계획({@link FullLoadPlan})을 만든다(Jira 197). 지금 적재가 지원하지 않는 매핑(행 조건, 문서 경로)이나
 * 원천에서 없어진 컬럼을 가리키는 매핑이 있으면 작업을 실패시킨다. 일부만 적재하면 빠진 속성이 조용히 비기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class FullLoadPlanner {

    /** 원천 데이터 정책 때문에 적재하지 않는 실패의 문구 머리(402). */
    public static final String POLICY_BLOCKED_PREFIX = "원천 데이터 정책상 ";

    private final SourceResourceRepository resourceRepository;
    private final ObjectSourceMappingRepository mappingRepository;
    private final PropertySourceMappingRepository propertyMappingRepository;
    private final SourceColumnRepository columnRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final SourceColumnPolicy sourceColumnPolicy;

    /**
     * {@link #plan}과 같되 계획을 만들 수 없으면 예외 대신 사유를 돌려준다(402). 다른 트랜잭션 안에서 부를 때 쓴다. 예외가 이 메서드의 트랜잭션 경계를
     * 넘으면 바깥 트랜잭션이 rollback-only가 되어, 잡아도 커밋하지 못한다(CDC 반영이 그 원천 트랜잭션 전체를 반영하지 못하고 멈춘다).
     */
    @Transactional(readOnly = true)
    public PlanAttempt tryPlan(long resourceId) {
        try {
            return new PlanAttempt(plan(resourceId), null);
        } catch (QueuedJobFailure e) {
            return new PlanAttempt(null, e.getMessage());
        }
    }

    /**
     * @param plan 만든 계획. 만들지 못했으면 null
     * @param failure 만들지 못한 사유. 만들었으면 null
     */
    public record PlanAttempt(FullLoadPlan plan, String failure) {

        public boolean blocked() {
            return failure != null;
        }
    }

    @Transactional(readOnly = true)
    public FullLoadPlan plan(long resourceId) {
        SourceResource resource = findResource(resourceId);
        if (!resource.isReadable()) {
            throw new QueuedJobFailure(
                    "SYNC 계정이 읽을 수 없는 원천 테이블입니다: %s".formatted(resource.getResourceKey()));
        }
        Loaded loaded = load(resourceId);
        if (loaded.mappings().isEmpty()) {
            return new FullLoadPlan(
                    resourceId, resource.getResourceKey(), resource.getSyncStrategy(), List.of());
        }
        rejectPolicyViolations(resource, violations(resource, loaded));
        List<ObjectSourceMapping> mappings = loaded.mappings();
        List<PropertySourceMapping> propertyMappings = loaded.propertyMappings();
        Map<Long, SourceColumn> columns = loaded.columns();
        Map<Long, ObjectType> objectTypes = loaded.objectTypes();
        Map<Long, PropertyDefinition> properties = loaded.properties();

        List<MappingPlan> plans = new ArrayList<>();
        for (ObjectSourceMapping mapping : mappings) {
            if (mapping.getRowFilter() != null && !mapping.getRowFilter().isEmpty()) {
                throw new QueuedJobFailure(
                        "행 조건이 있는 매핑(%d)은 아직 적재할 수 없습니다.".formatted(mapping.getId()));
            }
            ObjectType type = objectTypes.get(mapping.getObjectTypeId());
            List<PropertyPlan> propertyPlans = new ArrayList<>();
            for (PropertySourceMapping propertyMapping : propertyMappings) {
                if (!propertyMapping.getSourceMappingId().equals(mapping.getId())) {
                    continue;
                }
                propertyPlans.add(
                        propertyPlan(
                                resource,
                                propertyMapping,
                                columns.get(propertyMapping.getSourceColumnId()),
                                properties.get(propertyMapping.getPropertyDefinitionId())));
            }
            PropertyDefinition display =
                    type.getDisplayPropertyId() == null
                            ? null
                            : properties.get(type.getDisplayPropertyId());
            plans.add(
                    new MappingPlan(
                            mapping.getId(),
                            mapping.getRowVersion(),
                            type.getId(),
                            type.getRowVersion(),
                            mapping.getKeyColumns(),
                            display == null ? null : display.getCode(),
                            propertyPlans));
        }
        return new FullLoadPlan(
                resourceId, resource.getResourceKey(), resource.getSyncStrategy(), plans);
    }

    private static PropertyPlan propertyPlan(
            SourceResource resource,
            PropertySourceMapping mapping,
            SourceColumn column,
            PropertyDefinition property) {
        if (column == null) {
            throw new QueuedJobFailure(
                    "표 컬럼이 아닌 속성 매핑(%d)은 아직 적재할 수 없습니다.".formatted(mapping.getId()));
        }
        if (!column.isPresent()) {
            throw new QueuedJobFailure(
                    "속성 매핑(%d)이 가리키는 컬럼 %s가 원천 테이블 %s에 없습니다. 매핑을 고친 뒤 다시 수집해 주세요."
                            .formatted(
                                    mapping.getId(),
                                    column.getColumnName(),
                                    resource.getResourceKey()));
        }
        return new PropertyPlan(
                mapping.getId(),
                mapping.getRowVersion(),
                property.getId(),
                property.getCode(),
                property.getDataType(),
                property.getRowVersion(),
                column.getColumnName(),
                steps(mapping.getTransformSteps()));
    }

    // 저장할 때 TransformStepsValidator로 확인한 값이다. 그래도 형식이 깨졌으면 IllegalArgumentException이 그대로 난다.
    private static List<TransformStep> steps(List<Map<String, Object>> stored) {
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .map(
                        step ->
                                new TransformStep(
                                        TransformOperation.valueOf((String) step.get("op")),
                                        step.get("arg")))
                .toList();
    }

    /**
     * 이 원천 테이블의 켜진 매핑이 원천 데이터 정책을 어긴 곳(402). 적재 계획과 같은 판단이다. 읽기 권한은 보지 않는다. 유형 공개 검사와 위반 보고 API가
     * 쓴다.
     */
    @Transactional(readOnly = true)
    public List<SourcePolicyViolation> violations(long resourceId) {
        SourceResource resource = findResource(resourceId);
        return violations(resource, load(resourceId));
    }

    private SourceResource findResource(long resourceId) {
        return resourceRepository
                .findById(resourceId)
                .orElseThrow(() -> new IllegalStateException("원천 테이블이 없습니다: " + resourceId));
    }

    private Loaded load(long resourceId) {
        List<ObjectSourceMapping> mappings =
                mappingRepository.findBySourceResourceIdAndActiveTrueOrderById(resourceId);
        if (mappings.isEmpty()) {
            return new Loaded(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }
        List<PropertySourceMapping> propertyMappings =
                propertyMappingRepository.findBySourceMappingIdInOrderById(
                        mappings.stream().map(ObjectSourceMapping::getId).toList());
        Map<Long, SourceColumn> columns =
                byId(
                        columnRepository.findAllById(
                                propertyMappings.stream()
                                        .map(PropertySourceMapping::getSourceColumnId)
                                        .filter(id -> id != null)
                                        .collect(Collectors.toSet())),
                        SourceColumn::getId);
        Map<Long, ObjectType> objectTypes =
                byId(
                        objectTypeRepository.findAllById(
                                mappings.stream()
                                        .map(ObjectSourceMapping::getObjectTypeId)
                                        .collect(Collectors.toSet())),
                        ObjectType::getId);
        Set<Long> propertyIds = new HashSet<>();
        propertyMappings.forEach(p -> propertyIds.add(p.getPropertyDefinitionId()));
        objectTypes.values().stream()
                .map(ObjectType::getDisplayPropertyId)
                .filter(id -> id != null)
                .forEach(propertyIds::add);
        Map<Long, PropertyDefinition> properties =
                byId(propertyRepository.findAllById(propertyIds), PropertyDefinition::getId);
        return new Loaded(mappings, propertyMappings, columns, objectTypes, properties);
    }

    /**
     * 정책 위반을 매핑 순서대로 모은다. 매핑마다 키 컬럼(금지·민감)을 먼저, 그다음 그 매핑의 속성 매핑(금지 컬럼, 민감 컬럼을 담는 일반 속성)을 본다. 표 전체가
     * 금지이면 모든 키·속성 컬럼이 금지로 잡힌다.
     */
    private List<SourcePolicyViolation> violations(SourceResource resource, Loaded loaded) {
        String resourceKey = resource.getResourceKey();
        List<SourcePolicyViolation> found = new ArrayList<>();
        for (ObjectSourceMapping mapping : loaded.mappings()) {
            for (String key : mapping.getKeyColumns()) {
                SourceColumnGrade grade = sourceColumnPolicy.gradeOf(resourceKey, key);
                if (grade == SourceColumnGrade.FORBIDDEN) {
                    found.add(violation(Kind.FORBIDDEN_COLUMN, resource, key, mapping, null, null));
                } else if (grade == SourceColumnGrade.RESTRICTED) {
                    found.add(violation(Kind.RESTRICTED_KEY, resource, key, mapping, null, null));
                }
            }
            for (PropertySourceMapping propertyMapping : loaded.propertyMappings()) {
                if (!propertyMapping.getSourceMappingId().equals(mapping.getId())) {
                    continue;
                }
                SourceColumn column = loaded.columns().get(propertyMapping.getSourceColumnId());
                if (column == null) {
                    continue;
                }
                PropertyDefinition property =
                        loaded.properties().get(propertyMapping.getPropertyDefinitionId());
                // 마지막 단계가 대응표에 있는 값만 바꾸기(MAP_KNOWN)이면 결과가 닫혀 있어 민감 컬럼을 일반 속성에 담아도 위반이 아니다(448).
                SourceColumnGrade grade =
                        ClosedTransform.gradeOfStored(
                                sourceColumnPolicy.gradeOf(resourceKey, column.getColumnName()),
                                propertyMapping.getTransformSteps());
                if (grade == SourceColumnGrade.FORBIDDEN) {
                    found.add(
                            violation(
                                    Kind.FORBIDDEN_COLUMN,
                                    resource,
                                    column.getColumnName(),
                                    mapping,
                                    propertyMapping,
                                    property));
                } else if (grade == SourceColumnGrade.RESTRICTED
                        && property != null
                        && property.getSensitivity() != PropertySensitivity.RESTRICTED) {
                    found.add(
                            violation(
                                    Kind.RESTRICTED_UNDER_CLASSIFIED,
                                    resource,
                                    column.getColumnName(),
                                    mapping,
                                    propertyMapping,
                                    property));
                }
            }
        }
        return found;
    }

    private static SourcePolicyViolation violation(
            Kind kind,
            SourceResource resource,
            String column,
            ObjectSourceMapping mapping,
            PropertySourceMapping propertyMapping,
            PropertyDefinition property) {
        return new SourcePolicyViolation(
                kind,
                resource.getId(),
                resource.getResourceKey(),
                column,
                mapping.getId(),
                mapping.getObjectTypeId(),
                propertyMapping == null ? null : propertyMapping.getId(),
                property == null ? null : property.getId(),
                property == null ? null : property.getCode());
    }

    /**
     * 정책을 어긴 매핑이 있으면 적재하지 않는다(384·391). CDC 반영도 이 계획을 쓰므로 Kafka에 남은 옛 이벤트도 들어오지 않는다. 금지 → 민감 키 → 민감
     * 컬럼을 담는 일반 속성 순으로 하나만 알린다. 문구는 모두 {@link #POLICY_BLOCKED_PREFIX}로 시작한다.
     *
     * <ul>
     *   <li>민감 컬럼을 키로 쓰는 매핑(391 이전 매핑)은 원천 키로 민감 값이 드러난다.
     *   <li>속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다. 기본값 NORMAL을 분류가 끝난 것으로 보지 않고 적재 때 다시 본다. 391 이전에 만든
     *       매핑이나 서비스를 거치지 않은 변경이 민감 값을 일반 속성으로 들이지 않게 한다.
     * </ul>
     */
    private void rejectPolicyViolations(
            SourceResource resource, List<SourcePolicyViolation> violations) {
        String resourceKey = resource.getResourceKey();
        List<String> denied =
                sourceColumnPolicy.forbidsTable(resourceKey)
                        ? List.of("*")
                        : namesOf(
                                violations,
                                Kind.FORBIDDEN_COLUMN,
                                SourcePolicyViolation::columnName);
        if (!denied.isEmpty()) {
            throw new QueuedJobFailure(
                    POLICY_BLOCKED_PREFIX
                            + "들이지 않는 표·컬럼을 매핑이 가리킵니다: %s %s".formatted(resourceKey, denied));
        }
        List<String> restrictedKeys =
                namesOf(violations, Kind.RESTRICTED_KEY, SourcePolicyViolation::columnName);
        if (!restrictedKeys.isEmpty()) {
            throw new QueuedJobFailure(
                    POLICY_BLOCKED_PREFIX
                            + "민감 컬럼을 매핑 키로 씁니다. 키를 바꾸고 다시 적재하세요: %s %s"
                                    .formatted(resourceKey, restrictedKeys));
        }
        List<String> underClassified =
                namesOf(
                        violations,
                        Kind.RESTRICTED_UNDER_CLASSIFIED,
                        SourcePolicyViolation::propertyCode);
        if (!underClassified.isEmpty()) {
            throw new QueuedJobFailure(
                    POLICY_BLOCKED_PREFIX
                            + "민감 컬럼을 일반 속성이 담습니다. 속성을 민감으로 바꾸고 다시 적재하세요: %s %s"
                                    .formatted(resourceKey, underClassified));
        }
    }

    private static List<String> namesOf(
            List<SourcePolicyViolation> violations,
            Kind kind,
            Function<SourcePolicyViolation, String> name) {
        return violations.stream().filter(v -> v.kind() == kind).map(name).distinct().toList();
    }

    private record Loaded(
            List<ObjectSourceMapping> mappings,
            List<PropertySourceMapping> propertyMappings,
            Map<Long, SourceColumn> columns,
            Map<Long, ObjectType> objectTypes,
            Map<Long, PropertyDefinition> properties) {}

    private static <T> Map<Long, T> byId(Iterable<T> values, Function<T, Long> id) {
        Map<Long, T> map = new HashMap<>();
        values.forEach(value -> map.put(id.apply(value), value));
        return map;
    }
}

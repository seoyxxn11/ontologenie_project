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
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.Collection;
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

    private final SourceResourceRepository resourceRepository;
    private final ObjectSourceMappingRepository mappingRepository;
    private final PropertySourceMappingRepository propertyMappingRepository;
    private final SourceColumnRepository columnRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final SourceColumnPolicy sourceColumnPolicy;

    @Transactional(readOnly = true)
    public FullLoadPlan plan(long resourceId) {
        SourceResource resource =
                resourceRepository
                        .findById(resourceId)
                        .orElseThrow(
                                () -> new IllegalStateException("원천 테이블이 없습니다: " + resourceId));
        if (!resource.isReadable()) {
            throw new QueuedJobFailure(
                    "SYNC 계정이 읽을 수 없는 원천 테이블입니다: %s".formatted(resource.getResourceKey()));
        }
        List<ObjectSourceMapping> mappings =
                mappingRepository.findBySourceResourceIdAndActiveTrueOrderById(resourceId);
        if (mappings.isEmpty()) {
            return new FullLoadPlan(
                    resourceId, resource.getResourceKey(), resource.getSyncStrategy(), List.of());
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

        // 원천 데이터 정책상 들이지 않는 표·컬럼을 가리키는 매핑은 적재하지 않는다(384·391). CDC 반영도 이 계획을 쓰므로
        // Kafka에 남은 옛 이벤트도 들어오지 않는다.
        List<String> denied =
                sourceColumnPolicy.forbidsTable(resource.getResourceKey())
                        ? List.of("*")
                        : forbiddenMappedColumns(
                                resource.getResourceKey(), mappings, columns.values());
        if (!denied.isEmpty()) {
            throw new QueuedJobFailure(
                    "원천 데이터 정책상 들이지 않는 표·컬럼을 매핑이 가리킵니다: %s %s"
                            .formatted(resource.getResourceKey(), denied));
        }
        // 민감 컬럼을 키로 쓰는 매핑(391 이전 매핑)은 원천 키로 민감 값이 드러나므로 적재하지 않는다.
        List<String> restrictedKeys =
                mappings.stream()
                        .flatMap(
                                mapping ->
                                        sourceColumnPolicy
                                                .restrictedColumns(
                                                        resource.getResourceKey(),
                                                        mapping.getKeyColumns())
                                                .stream())
                        .distinct()
                        .toList();
        if (!restrictedKeys.isEmpty()) {
            throw new QueuedJobFailure(
                    "원천 데이터 정책상 민감 컬럼을 매핑 키로 씁니다. 키를 바꾸고 다시 적재하세요: %s %s"
                            .formatted(resource.getResourceKey(), restrictedKeys));
        }
        // 속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다. 기본값 NORMAL을 분류가 끝난 것으로 보지 않고 적재 때 다시 본다.
        // 391 이전에 만든 매핑이나 서비스를 거치지 않은 변경이 민감 값을 일반 속성으로 들이지 않게 한다.
        List<String> underClassified =
                propertyMappings.stream()
                        .filter(p -> p.getSourceColumnId() != null)
                        .filter(p -> columns.containsKey(p.getSourceColumnId()))
                        .filter(
                                p ->
                                        sourceColumnPolicy.gradeOf(
                                                        resource.getResourceKey(),
                                                        columns.get(p.getSourceColumnId())
                                                                .getColumnName())
                                                == SourceColumnGrade.RESTRICTED)
                        .map(p -> properties.get(p.getPropertyDefinitionId()))
                        .filter(
                                p ->
                                        p != null
                                                && p.getSensitivity()
                                                        != PropertySensitivity.RESTRICTED)
                        .map(PropertyDefinition::getCode)
                        .distinct()
                        .toList();
        if (!underClassified.isEmpty()) {
            throw new QueuedJobFailure(
                    "원천 데이터 정책상 민감 컬럼을 일반 속성이 담습니다. 속성을 민감으로 바꾸고 다시 적재하세요: %s %s"
                            .formatted(resource.getResourceKey(), underClassified));
        }

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

    /** 켜진 매핑의 키 컬럼과 속성 매핑 컬럼 가운데 원천 데이터 정책상 금지인 것. */
    private List<String> forbiddenMappedColumns(
            String resourceKey,
            List<ObjectSourceMapping> mappings,
            Collection<SourceColumn> mappedColumns) {
        List<String> names = new ArrayList<>();
        mappings.forEach(mapping -> names.addAll(mapping.getKeyColumns()));
        mappedColumns.forEach(column -> names.add(column.getColumnName()));
        return sourceColumnPolicy.forbiddenColumns(resourceKey, names.stream().distinct().toList());
    }

    private static <T> Map<Long, T> byId(Iterable<T> values, Function<T, Long> id) {
        Map<Long, T> map = new HashMap<>();
        values.forEach(value -> map.put(id.apply(value), value));
        return map;
    }
}

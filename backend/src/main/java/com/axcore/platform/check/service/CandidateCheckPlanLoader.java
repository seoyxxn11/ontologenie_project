package com.axcore.platform.check.service;

import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckRuleInput;
import com.axcore.platform.check.domain.CheckTarget;
import com.axcore.platform.check.domain.ObjectRowFilter;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.repository.CandidateCheckRepository;
import com.axcore.platform.check.repository.CandidateCheckRepository.CandidateRevision;
import com.axcore.platform.check.repository.CandidateCheckRepository.MappingProposal;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.mapping.service.TransformStepsValidator;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("batch")
@RequiredArgsConstructor
class CandidateCheckPlanLoader {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final CandidateCheckRepository candidates;
    private final CheckRunRepository runs;
    private final LinkTypeRepository linkTypes;
    private final ObjectTypeRepository objectTypes;
    private final PropertyDefinitionRepository definitions;
    private final SourceResourceRepository resources;
    private final SourceColumnRepository columns;
    private final DataSourceRepository sources;
    private final JsonMapper json;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan load(long runId) {
        CandidateRevision candidate =
                candidates
                        .findRevision(runId)
                        .orElseThrow(() -> new QueuedJobFailure("시험할 후보 개정본이 없습니다."));
        if (!"LINK_RULE".equals(candidate.kind()) || candidate.hasOptions()) {
            throw new QueuedJobFailure(CandidateCheckService.UNSUPPORTED_MESSAGE);
        }
        try {
            return plan(candidate, runId);
        } catch (IllegalArgumentException | BusinessException e) {
            throw new QueuedJobFailure("후보의 관계 유형, 비교 조건 또는 원천 매핑을 읽을 수 없습니다.");
        }
    }

    private Plan plan(CandidateRevision candidate, long runId) {
        JsonNode payload = candidate.payload();
        List<MappingProposal> pending =
                candidate.analysisRunId() == null
                        ? List.of()
                        : candidates.findMappingProposals(candidate.analysisRunId());
        Relation relation = relation(payload, pending);
        List<CheckCondition> conditions = new ArrayList<>();
        Set<String> sourceProperties = new LinkedHashSet<>();
        Set<String> targetProperties = new LinkedHashSet<>();
        JsonNode storedConditions = payload.path("conditions");
        if (!storedConditions.isArray()) {
            throw new IllegalArgumentException();
        }
        for (JsonNode condition : storedConditions) {
            String source = property(condition, "sourceProperty", relation.source());
            String target = property(condition, "targetProperty", relation.target());
            sourceProperties.add(source);
            targetProperties.add(target);
            conditions.add(
                    new CheckCondition(
                            conditions.size() + 1,
                            relation.source() + "." + source,
                            relation.target() + "." + target,
                            condition.path("operator").asString("EQ"),
                            steps(condition.path("sourceTransforms")),
                            steps(condition.path("targetTransforms"))));
        }
        ObjectRowFilter sourceFilter =
                filter(payload.path("sourceFilter"), relation.source(), sourceProperties);
        ObjectRowFilter targetFilter =
                filter(payload.path("targetFilter"), relation.target(), targetProperties);

        var basis =
                runs.findToExecute(runId)
                        .orElseThrow(() -> new QueuedJobFailure("후보 시험 실행 기록이 없습니다."))
                        .dataBasis();
        var input =
                new CheckRuleInput(
                        CheckTarget.forProposal(candidate.proposalId(), candidate.revision()),
                        basis,
                        relation.cardinality(),
                        conditions);
        Set<Long> resourceIds =
                basis.resources().stream()
                        .map(ResourceCheckpoint::sourceResourceId)
                        .collect(Collectors.toSet());
        if (resourceIds.isEmpty()) {
            throw new QueuedJobFailure("후보 시험에 사용할 원천 자원이 없습니다.");
        }
        var catalog =
                resources.findAllById(resourceIds).stream()
                        .collect(Collectors.toMap(SourceResource::getId, Function.identity()));
        var presentColumns =
                columns.findBySourceResourceIdInAndIsPresentTrue(resourceIds).stream()
                        .collect(
                                Collectors.groupingBy(
                                        SourceColumn::getSourceResourceId,
                                        Collectors.mapping(
                                                SourceColumn::getColumnName, Collectors.toSet())));

        return new Plan(
                input,
                new Side(
                        relation.source(),
                        sourceFilter,
                        restrictedProperties(relation.source(), sourceProperties, pending),
                        mappings(
                                relation.source(),
                                sourceProperties,
                                resourceIds,
                                catalog,
                                presentColumns,
                                pending)),
                new Side(
                        relation.target(),
                        targetFilter,
                        restrictedProperties(relation.target(), targetProperties, pending),
                        mappings(
                                relation.target(),
                                targetProperties,
                                resourceIds,
                                catalog,
                                presentColumns,
                                pending)));
    }

    private Relation relation(JsonNode payload, List<MappingProposal> pending) {
        JsonNode id = payload.path("linkTypeId");
        var existing =
                id.isMissingNode() || id.isNull()
                        ? linkTypes.findByCode(text(payload, "linkTypeCode"))
                        : linkTypes.findById(id.asLong());
        if (existing.isPresent()) {
            var type = existing.get();
            return new Relation(
                    objectTypes
                            .findById(type.getSourceTypeId())
                            .orElseThrow(() -> new QueuedJobFailure("후보 시험의 출발 객체 유형이 없습니다."))
                            .getCode(),
                    objectTypes
                            .findById(type.getTargetTypeId())
                            .orElseThrow(() -> new QueuedJobFailure("후보 시험의 도착 객체 유형이 없습니다."))
                            .getCode(),
                    type.getCardinality());
        }
        if (!id.isMissingNode() && !id.isNull()) {
            throw new IllegalArgumentException();
        }
        String code = text(payload, "linkTypeCode");
        JsonNode definition = payload.path("linkType");
        if (definition.isMissingNode() || definition.isNull()) {
            var pendingTypes =
                    pending.stream()
                            .filter(
                                    p ->
                                            "LINK_TYPE".equals(p.kind())
                                                    && code.equals(
                                                            p.payload().path("code").asString("")))
                            .toList();
            if (pendingTypes.size() != 1) {
                throw new IllegalArgumentException();
            }
            definition = pendingTypes.getFirst().payload();
        }
        if (!code.equals(text(definition, "code"))) {
            throw new IllegalArgumentException();
        }
        return new Relation(
                text(definition, "sourceTypeCode"),
                text(definition, "targetTypeCode"),
                LinkCardinality.valueOf(text(definition, "cardinality")));
    }

    private List<RowMapping> mappings(
            String type,
            Set<String> required,
            Set<Long> resourceIds,
            Map<Long, SourceResource> catalog,
            Map<Long, Set<String>> presentColumns,
            List<MappingProposal> pending) {
        Map<Long, RowMapping> selected = new LinkedHashMap<>();
        for (var mapping : candidates.findActiveMappings(type, resourceIds)) {
            SourceResource resource = catalog.get(mapping.resourceId());
            if (mapping.hasFilter()) {
                throw new QueuedJobFailure("원천 객체 매핑의 행 조건은 아직 시험할 수 없습니다.");
            }
            Map<String, ColumnMapping> properties = new LinkedHashMap<>();
            Set<String> restrictedColumns = new LinkedHashSet<>();
            for (JsonNode property : mapping.properties()) {
                String code = text(property, "code");
                if (required.contains(code)) {
                    properties.put(
                            code,
                            column(property, text(property, "dataType"), text(property, "column")));
                }
                JsonNode column = property.path("column");
                if (column.isString() && isRestricted(type, code, pending)) {
                    restrictedColumns.add(column.asString());
                }
            }
            addPendingProperties(
                    type,
                    required,
                    mapping.sourceCode(),
                    resource.getResourceKey(),
                    pending,
                    properties);
            addPendingRestrictedColumns(
                    type,
                    mapping.sourceCode(),
                    resource.getResourceKey(),
                    pending,
                    restrictedColumns);
            selected.put(
                    resource.getId(),
                    rowMapping(
                            resource,
                            mapping.keyColumns(),
                            properties,
                            restrictedColumns,
                            presentColumns));
        }

        Set<Long> activeIds = Set.copyOf(selected.keySet());
        for (MappingProposal proposal : pending) {
            JsonNode payload = proposal.payload();
            boolean bundled = "OBJECT_TYPE".equals(proposal.kind());
            if (!(bundled || "OBJECT_MAPPING".equals(proposal.kind()))
                    || !type.equals(payload.path(bundled ? "code" : "objectTypeCode").asString())) {
                continue;
            }
            JsonNode mapping = bundled ? payload.path("objectMapping") : payload;
            if (!mapping.isObject()) {
                continue;
            }
            String source = text(mapping, "sourceId");
            String key = text(mapping, "resourceKey");
            Long sourceId =
                    sources.findByCode(source).orElseThrow(IllegalArgumentException::new).getId();
            SourceResource resource =
                    catalog.values().stream()
                            .filter(
                                    r ->
                                            r.getDataSourceId().equals(sourceId)
                                                    && r.getResourceKey().equals(key))
                            .findFirst()
                            .orElseThrow(IllegalArgumentException::new);
            if (activeIds.contains(resource.getId())) {
                continue;
            }
            if (selected.containsKey(resource.getId())) {
                throw new QueuedJobFailure("같은 유형과 원천 자원에 여러 매핑 후보가 있어 시험할 수 없습니다.");
            }
            Map<String, ColumnMapping> properties = new LinkedHashMap<>();
            Set<String> restrictedColumns = new LinkedHashSet<>();
            for (JsonNode property : payload.path("properties")) {
                String code = text(property, "code");
                JsonNode reference = property.path("sourceColumn");
                if (!sameResource(reference, source, key)) {
                    continue;
                }
                if (required.contains(code)) {
                    properties.put(
                            code,
                            column(
                                    property,
                                    text(property, "dataType"),
                                    text(reference, "column")));
                }
                if (reference.path("column").isString()
                        && (isRestricted(property) || isRestricted(type, code, pending))) {
                    restrictedColumns.add(reference.path("column").asString());
                }
            }
            addPendingProperties(type, required, source, key, pending, properties);
            addPendingRestrictedColumns(type, source, key, pending, restrictedColumns);
            selected.put(
                    resource.getId(),
                    rowMapping(
                            resource,
                            mapping.path("keyColumns"),
                            properties,
                            restrictedColumns,
                            presentColumns));
        }

        Set<String> mapped = new LinkedHashSet<>();
        selected.values().forEach(mapping -> mapped.addAll(mapping.properties().keySet()));
        if (selected.isEmpty() || !mapped.containsAll(required)) {
            throw new QueuedJobFailure("비교 조건에 필요한 속성의 원천 컬럼 매핑을 찾지 못했습니다.");
        }
        return List.copyOf(selected.values());
    }

    private void addPendingProperties(
            String type,
            Set<String> required,
            String source,
            String key,
            List<MappingProposal> pending,
            Map<String, ColumnMapping> properties) {
        Set<String> existing = Set.copyOf(properties.keySet());
        if (existing.containsAll(required)) {
            return;
        }
        for (MappingProposal proposal : pending) {
            JsonNode property = proposal.payload();
            String code = property.path("propertyCode").asString("");
            JsonNode reference = property.path("sourceColumn");
            if (!"PROPERTY_MAPPING".equals(proposal.kind())
                    || !type.equals(property.path("objectTypeCode").asString(""))
                    || !sameResource(reference, source, key)
                    || !required.contains(code)
                    || existing.contains(code)) {
                continue;
            }
            if (properties.containsKey(code)) {
                throw new QueuedJobFailure("같은 속성과 원천 자원에 여러 속성 매핑 후보가 있습니다.");
            }
            properties.put(
                    code,
                    column(property, propertyType(type, code, pending), text(reference, "column")));
        }
    }

    private String propertyType(String type, String code, List<MappingProposal> pending) {
        var existing =
                objectTypes
                        .findByCode(type)
                        .flatMap(t -> definitions.findByObjectTypeIdAndCode(t.getId(), code));
        if (existing.isPresent()) {
            return existing.get().getDataType().name();
        }
        return pending.stream()
                .filter(
                        p ->
                                "PROPERTY".equals(p.kind())
                                        && type.equals(
                                                p.payload().path("objectTypeCode").asString())
                                        && code.equals(p.payload().path("code").asString()))
                .map(p -> text(p.payload(), "dataType"))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }

    /** 시험에 쓰는 속성(조건 키·행 조건) 중 민감(RESTRICTED) 속성 코드(400). */
    private Set<String> restrictedProperties(
            String type, Set<String> required, List<MappingProposal> pending) {
        return required.stream()
                .filter(code -> isRestricted(type, code, pending))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 같은 분석의 속성 매핑 후보가 이 원천 자원에 담는 민감 속성의 컬럼을 더한다(400). 조건에 쓰지 않는 속성이어도 키 컬럼에 매핑돼 있으면 원천 키로 값이
     * 드러나므로 모두 본다.
     */
    private void addPendingRestrictedColumns(
            String type,
            String source,
            String key,
            List<MappingProposal> pending,
            Set<String> restrictedColumns) {
        for (MappingProposal proposal : pending) {
            JsonNode property = proposal.payload();
            JsonNode reference = property.path("sourceColumn");
            if ("PROPERTY_MAPPING".equals(proposal.kind())
                    && type.equals(property.path("objectTypeCode").asString(""))
                    && sameResource(reference, source, key)
                    && reference.path("column").isString()
                    && isRestricted(type, property.path("propertyCode").asString(""), pending)) {
                restrictedColumns.add(reference.path("column").asString());
            }
        }
    }

    /**
     * 민감(RESTRICTED) 속성인가. 확정 속성은 정의의 민감도를, 같은 분석의 속성 후보와 객체 유형 후보에 묶인 속성은 payload의 sensitivity를
     * 본다. 원천 컬럼 등급 때문에 민감해질 속성은 원천 데이터 정책을 쓰는 {@link CandidateCheckJob}이 더 본다.
     */
    private boolean isRestricted(String type, String code, List<MappingProposal> pending) {
        boolean defined =
                objectTypes
                        .findByCode(type)
                        .flatMap(t -> definitions.findByObjectTypeIdAndCode(t.getId(), code))
                        .filter(
                                definition ->
                                        definition.getSensitivity()
                                                == PropertySensitivity.RESTRICTED)
                        .isPresent();
        if (defined) {
            return true;
        }
        for (MappingProposal proposal : pending) {
            JsonNode payload = proposal.payload();
            if ("PROPERTY".equals(proposal.kind())
                    && type.equals(payload.path("objectTypeCode").asString(""))
                    && code.equals(payload.path("code").asString(""))
                    && isRestricted(payload)) {
                return true;
            }
            if ("OBJECT_TYPE".equals(proposal.kind())
                    && type.equals(payload.path("code").asString(""))) {
                for (JsonNode property : payload.path("properties")) {
                    if (code.equals(property.path("code").asString("")) && isRestricted(property)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isRestricted(JsonNode property) {
        return PropertySensitivity.RESTRICTED
                .name()
                .equals(property.path("sensitivity").asString(""));
    }

    private static boolean sameResource(JsonNode reference, String source, String key) {
        return source.equals(reference.path("sourceId").asString())
                && key.equals(reference.path("resourceKey").asString());
    }

    private RowMapping rowMapping(
            SourceResource resource,
            JsonNode keys,
            Map<String, ColumnMapping> properties,
            Set<String> restrictedColumns,
            Map<Long, Set<String>> presentColumns) {
        List<String> keyColumns = new ArrayList<>();
        keys.forEach(key -> keyColumns.add(key.asString()));
        Set<String> available = presentColumns.getOrDefault(resource.getId(), Set.of());
        if (!resource.isReadable()
                || keyColumns.isEmpty()
                || !available.containsAll(keyColumns)
                || properties.values().stream().anyMatch(p -> !available.contains(p.column()))) {
            throw new QueuedJobFailure("후보 시험의 원천 테이블 또는 컬럼을 읽을 수 없습니다.");
        }
        return new RowMapping(
                resource.getDataSourceId(),
                resource.getId(),
                resource.getResourceKey(),
                List.copyOf(keyColumns),
                Map.copyOf(properties),
                Set.copyOf(restrictedColumns));
    }

    private ColumnMapping column(JsonNode property, String dataType, String column) {
        return new ColumnMapping(
                column, PropertyDataType.valueOf(dataType), steps(property.path("transformSteps")));
    }

    private List<TransformStep> steps(JsonNode stored) {
        if (stored.isMissingNode() || stored.isNull()) {
            return List.of();
        }
        if (!stored.isArray()) {
            throw new IllegalArgumentException();
        }
        List<TransformStepPayload> steps = new ArrayList<>();
        stored.forEach(
                step ->
                        steps.add(
                                new TransformStepPayload(
                                        text(step, "op"),
                                        json.treeToValue(step.get("arg"), Object.class))));
        return TransformStepsValidator.parse(steps);
    }

    private ObjectRowFilter filter(JsonNode node, String type, Set<String> properties) {
        Map<String, Object> stored =
                node.isMissingNode() || node.isNull() ? Map.of() : json.treeToValue(node, OBJECT);
        ObjectRowFilter filter =
                ObjectRowFilter.parse(stored, type).orElseThrow(IllegalArgumentException::new);
        if (filter.propertyCode() != null) {
            properties.add(filter.propertyCode());
        }
        return filter;
    }

    private static String property(JsonNode node, String field, String type) {
        String value = text(node, field);
        int dot = value.lastIndexOf('.');
        if (dot == -1) {
            return value;
        }
        if (!type.equals(value.substring(0, dot)) || dot == value.length() - 1) {
            throw new IllegalArgumentException();
        }
        return value.substring(dot + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException();
        }
        return value.asString();
    }

    private record Relation(String source, String target, LinkCardinality cardinality) {}

    record Plan(CheckRuleInput input, Side source, Side target) {}

    /** restrictedProperties: 이 쪽 속성 중 정의나 후보가 민감(RESTRICTED)인 속성 코드. */
    record Side(
            String typeCode,
            ObjectRowFilter filter,
            Set<String> restrictedProperties,
            List<RowMapping> mappings) {}

    /** restrictedColumns: 이 매핑에서 민감 속성(정의·후보 기준)을 담는 원천 컬럼. 정책상 민감 컬럼은 따로 본다. */
    record RowMapping(
            long dataSourceId,
            long resourceId,
            String resourceKey,
            List<String> keyColumns,
            Map<String, ColumnMapping> properties,
            Set<String> restrictedColumns) {}

    record ColumnMapping(String column, PropertyDataType dataType, List<TransformStep> steps) {}
}

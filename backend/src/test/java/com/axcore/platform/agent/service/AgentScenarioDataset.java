package com.axcore.platform.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 341에서 만든 가상 프레스 시험 데이터(agent-scenario/press-v1.0.json.gz)를 시험 DB에 넣는다(342). 원천·매핑·유형·속성·관계
 * 규칙·객체·연결을 실제 적재와 같은 표에 넣어, 객체 검색·상세·관계·근거 조회가 운영과 같은 SQL로 돈다.
 *
 * <p>규칙 비교 항목은 규칙을 만든 트랜잭션 안에서만 넣을 수 있어(V1 트리거) 한 트랜잭션으로 넣는다. 수동 연결은 확인자 계정이 있어야 해서 적재용 관리자 계정을 하나
 * 만든다.
 */
public final class AgentScenarioDataset {
    private static final String RESOURCE = "agent-scenario/press-v1.0.json.gz";

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    public AgentScenarioDataset(
            JdbcClient jdbc, JdbcTemplate batch, TransactionTemplate transaction, JsonMapper json) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.transaction = transaction;
        this.json = json;
    }

    /** 넣은 결과. 시나리오 판정에서 원천 키로 객체 ID를 찾는다. */
    public record Loaded(long adminAccountId, Map<String, Long> objectIds, int objects, int links) {

        long object(String objectType, Map<String, String> sourceKey) {
            Long id = objectIds.get(key(objectType, sourceKey));
            if (id == null) {
                throw new IllegalArgumentException(
                        "시험 데이터에 없는 객체: " + objectType + " " + sourceKey);
            }
            return id;
        }
    }

    public Loaded load() {
        Dataset data = read();
        return transaction.execute(status -> insert(data));
    }

    private Dataset read() {
        try (InputStream in =
                new GZIPInputStream(new ClassPathResource(RESOURCE).getInputStream())) {
            return json.readValue(in, Dataset.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Loaded insert(Dataset data) {
        long admin =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     status, must_change_password)
                                values ('scenario-loader', '시나리오 적재', 'not-for-login',
                                        (select id from role where code = 'ADMIN'), 'ACTIVE', false)
                                returning id
                                """)
                        .query(Long.class)
                        .single();

        Map<String, Long> resources = new HashMap<>();
        for (DataSourceRow source : data.dataSources()) {
            long sourceId =
                    jdbc.sql(
                                    """
                                    insert into data_source (code, name, source_kind, connection_status)
                                    values (:code, :name, 'POSTGRES', 'CONNECTED') returning id
                                    """)
                            .param("code", source.code())
                            .param("name", source.name())
                            .query(Long.class)
                            .single();
            for (String resource : source.resources()) {
                resources.put(
                        resource,
                        jdbc.sql(
                                        """
                                        insert into source_resource (data_source_id, resource_key,
                                                                     resource_kind, is_readable)
                                        values (:source, :key, 'TABLE', true) returning id
                                        """)
                                .param("source", sourceId)
                                .param("key", resource)
                                .query(Long.class)
                                .single());
            }
        }

        Map<String, TypeRow> typeRows = new HashMap<>();
        Map<String, Long> types = new HashMap<>();
        for (TypeRow type : data.objectTypes()) {
            typeRows.put(type.code(), type);
            types.put(
                    type.code(),
                    jdbc.sql(
                                    """
                                    insert into object_type (code, name, access_group, origin)
                                    values (:code, :name, :group, 'SEED') returning id
                                    """)
                            .param("code", type.code())
                            .param("name", type.name())
                            .param("group", type.accessGroup())
                            .query(Long.class)
                            .single());
        }

        Map<String, Long> properties = new HashMap<>();
        Map<String, Long> columns = new HashMap<>();
        Map<String, Integer> columnCount = new HashMap<>();
        for (PropertyRow property : data.properties()) {
            TypeRow type = typeRows.get(property.objectType());
            long propertyId =
                    jdbc.sql(
                                    """
                                    insert into property_definition (object_type_id, code, name, data_type,
                                                                     is_identifier, is_required,
                                                                     sensitivity, origin)
                                    values (:type, :code, :name, :dataType, :identifier, :required,
                                            :sensitivity, 'SEED')
                                    returning id
                                    """)
                            .param("type", types.get(type.code()))
                            .param("code", property.code())
                            .param("name", property.name())
                            .param("dataType", property.dataType())
                            .param("identifier", property.isIdentifier())
                            .param("required", property.required())
                            .param("sensitivity", property.sensitivity())
                            .query(Long.class)
                            .single();
            properties.put(property.objectType() + "." + property.code(), propertyId);
            if (property.sourceColumn() != null) {
                long resource = resources.get(type.sourceResource());
                int position = columnCount.merge(type.sourceResource(), 1, Integer::sum);
                columns.put(
                        type.sourceResource() + "." + property.sourceColumn(),
                        jdbc.sql(
                                        """
                                        insert into source_column (source_resource_id, column_name,
                                                                   source_data_type, is_nullable,
                                                                   is_key_part, ordinal_position)
                                        values (:resource, :column, :dataType, :nullable, :key, :position)
                                        returning id
                                        """)
                                .param("resource", resource)
                                .param("column", property.sourceColumn())
                                .param("dataType", property.dataType().toLowerCase())
                                .param("nullable", !property.required())
                                .param("key", type.keyColumns().contains(property.sourceColumn()))
                                .param("position", position)
                                .query(Long.class)
                                .single());
            }
        }

        Map<String, Long> mappings = new HashMap<>();
        for (TypeRow type : data.objectTypes()) {
            long typeId = types.get(type.code());
            if (type.displayProperty() != null) {
                jdbc.sql("update object_type set display_property_id = :property where id = :id")
                        .param(
                                "property",
                                properties.get(type.code() + "." + type.displayProperty()))
                        .param("id", typeId)
                        .update();
            }
            long resource = resources.get(type.sourceResource());
            long mapping =
                    jdbc.sql(
                                    """
                                    insert into object_source_mapping (source_resource_id, object_type_id,
                                                                       key_columns, origin)
                                    values (:resource, :type, cast(:keys as jsonb), 'SEED') returning id
                                    """)
                            .param("resource", resource)
                            .param("type", typeId)
                            .param("keys", json.writeValueAsString(type.keyColumns()))
                            .query(Long.class)
                            .single();
            mappings.put(type.code(), mapping);
            for (PropertyRow property : data.properties()) {
                if (!property.objectType().equals(type.code()) || property.sourceColumn() == null) {
                    continue;
                }
                jdbc.sql(
                                """
                                insert into property_source_mapping (source_mapping_id, source_resource_id,
                                                                     property_definition_id,
                                                                     source_column_id)
                                values (:mapping, :resource, :property, :column)
                                """)
                        .param("mapping", mapping)
                        .param("resource", resource)
                        .param("property", properties.get(type.code() + "." + property.code()))
                        .param(
                                "column",
                                columns.get(type.sourceResource() + "." + property.sourceColumn()))
                        .update();
            }
        }

        List<Object[]> objectRows = new ArrayList<>();
        for (ObjectRow object : data.objects()) {
            objectRows.add(
                    new Object[] {
                        types.get(object.objectType()),
                        mappings.get(object.objectType()),
                        json.writeValueAsString(object.sourceKey()),
                        object.displayName(),
                        json.writeValueAsString(object.properties())
                    });
        }
        batch.batchUpdate(
                """
                insert into object_instance (object_type_id, source_mapping_id, source_key, display_name,
                                             properties, last_synced_at)
                values (?, ?, cast(? as jsonb), ?, cast(? as jsonb), now())
                """,
                objectRows);
        Map<String, Long> objectIds = new HashMap<>();
        jdbc.sql(
                        """
                        select oi.id, ot.code, oi.source_key::text as source_key
                        from object_instance oi join object_type ot on ot.id = oi.object_type_id
                        """)
                .query(
                        (rs, rowNum) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, String> sourceKey =
                                    json.readValue(rs.getString("source_key"), Map.class);
                            objectIds.put(key(rs.getString("code"), sourceKey), rs.getLong("id"));
                            return null;
                        })
                .list();

        Map<String, LinkTypeRow> linkTypeRows = new HashMap<>();
        Map<String, Long> linkTypes = new HashMap<>();
        for (LinkTypeRow linkType : data.linkTypes()) {
            linkTypeRows.put(linkType.code(), linkType);
            linkTypes.put(
                    linkType.code(),
                    jdbc.sql(
                                    """
                                    insert into link_type (code, name, source_type_id, target_type_id,
                                                           cardinality, description, origin)
                                    values (:code, :name, :source, :target, :cardinality, :description,
                                            'SEED')
                                    returning id
                                    """)
                            .param("code", linkType.code())
                            .param("name", linkType.name())
                            .param("source", types.get(linkType.sourceType()))
                            .param("target", types.get(linkType.targetType()))
                            .param("cardinality", linkType.cardinality())
                            .param("description", linkType.description())
                            .query(Long.class)
                            .single());
        }

        Map<String, Long> rules = new HashMap<>();
        for (RuleRow rule : data.rules()) {
            LinkTypeRow linkType = linkTypeRows.get(rule.linkType());
            long mapping =
                    jdbc.sql(
                                    """
                                    insert into link_mapping (link_type_id, code, revision, source_filter,
                                                              target_filter, origin)
                                    values (:linkType, :code, :revision, cast(:sourceFilter as jsonb),
                                            cast(:targetFilter as jsonb), 'SEED')
                                    returning id
                                    """)
                            .param("linkType", linkTypes.get(rule.linkType()))
                            .param("code", rule.code())
                            .param("revision", rule.revision())
                            .param("sourceFilter", json.writeValueAsString(rule.sourceFilter()))
                            .param("targetFilter", json.writeValueAsString(rule.targetFilter()))
                            .query(Long.class)
                            .single();
            rules.put(rule.linkType(), mapping);
            for (ConditionRow condition : rule.conditions()) {
                jdbc.sql(
                                """
                                insert into link_mapping_condition (link_mapping_id, source_property_id,
                                                                    target_property_id, sequence_no,
                                                                    operator, source_transforms,
                                                                    target_transforms)
                                values (:mapping, :source, :target, :sequence, :operator,
                                        cast(:sourceTransforms as jsonb),
                                        cast(:targetTransforms as jsonb))
                                """)
                        .param("mapping", mapping)
                        .param(
                                "source",
                                properties.get(
                                        linkType.sourceType() + "." + condition.sourceProperty()))
                        .param(
                                "target",
                                properties.get(
                                        linkType.targetType() + "." + condition.targetProperty()))
                        .param("sequence", condition.sequenceNo())
                        .param("operator", condition.operator())
                        .param(
                                "sourceTransforms",
                                json.writeValueAsString(condition.sourceTransforms()))
                        .param(
                                "targetTransforms",
                                json.writeValueAsString(condition.targetTransforms()))
                        .update();
            }
        }

        List<Object[]> linkRows = new ArrayList<>();
        for (LinkRow link : data.links()) {
            boolean rule = "RULE".equals(link.origin());
            linkRows.add(
                    new Object[] {
                        linkTypes.get(link.linkType()),
                        objectIds.get(key(link.source().objectType(), link.source().sourceKey())),
                        objectIds.get(key(link.target().objectType(), link.target().sourceKey())),
                        link.origin(),
                        rule ? rules.get(link.linkType()) : null,
                        json.writeValueAsString(link.evidence()),
                        rule ? null : admin
                    });
        }
        batch.batchUpdate(
                """
                insert into object_link (link_type_id, source_object_id, target_object_id, origin,
                                         link_mapping_id, evidence, confirmed_by)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                linkRows);

        return new Loaded(admin, Map.copyOf(objectIds), objectRows.size(), linkRows.size());
    }

    private static String key(String objectType, Map<String, String> sourceKey) {
        return objectType + new TreeMap<>(sourceKey);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dataset(
            List<DataSourceRow> dataSources,
            List<TypeRow> objectTypes,
            List<PropertyRow> properties,
            List<LinkTypeRow> linkTypes,
            List<RuleRow> rules,
            List<ObjectRow> objects,
            List<LinkRow> links) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataSourceRow(String code, String name, List<String> resources) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TypeRow(
            String code,
            String name,
            String accessGroup,
            String sourceResource,
            List<String> keyColumns,
            String displayProperty) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PropertyRow(
            String objectType,
            String code,
            String name,
            String dataType,
            @JsonProperty("isIdentifier") boolean isIdentifier,
            @JsonProperty("isRequired") Boolean isRequired,
            String sensitivity,
            String sourceColumn) {

        /** 정답 온톨로지에 필수 여부가 없으면 null이다. 필수로 보지 않는다. */
        boolean required() {
            return Boolean.TRUE.equals(isRequired);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkTypeRow(
            String code,
            String name,
            String description,
            String sourceType,
            String targetType,
            String cardinality,
            String linkOrigin) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleRow(
            String code,
            int revision,
            String linkType,
            Map<String, Object> sourceFilter,
            Map<String, Object> targetFilter,
            List<ConditionRow> conditions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConditionRow(
            int sequenceNo,
            String sourceProperty,
            String targetProperty,
            String operator,
            List<Object> sourceTransforms,
            List<Object> targetTransforms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ObjectRow(
            String objectType,
            Map<String, String> sourceKey,
            String displayName,
            Map<String, Object> properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Endpoint(String objectType, Map<String, String> sourceKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkRow(
            String linkType,
            String origin,
            Endpoint source,
            Endpoint target,
            Map<String, Object> evidence) {}
}

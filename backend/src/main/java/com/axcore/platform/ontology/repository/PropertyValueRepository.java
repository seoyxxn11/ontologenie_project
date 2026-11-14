package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.PropertyConstraints;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 속성 정의를 바꾸기 전에 이미 저장된 객체 값(object_instance.properties)과 맞는지 확인한다 (API 028). 값 형식은 {@link
 * PropertyDataType} 설명과 같다. 상태와 관계없이 그 유형의 모든 객체를 본다.
 */
@Repository
@RequiredArgsConstructor
public class PropertyValueRepository {

    private static final String VALUE = "o.properties -> :code";
    private static final String TEXT = "(o.properties ->> :code)";
    private static final String MISSING = "coalesce(jsonb_typeof(" + VALUE + "), 'null') = 'null'";

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** 값이 있는데 새 자료형의 값이 아닌 객체. */
    public ValueConflict findTypeMismatches(
            Long objectTypeId, String code, PropertyDataType dataType) {
        return find(
                objectTypeId,
                code,
                "not (" + MISSING + ") and not (" + conforms(dataType) + ")",
                Map.of());
    }

    /** 값(null 아님)이 남아 있는 객체. 민감 속성을 일반으로 내리기 전에 확인한다(384·401). 매핑이 없어도 객체에 남은 값은 민감 값이다. */
    public ValueConflict findPresentValues(Long objectTypeId, String code) {
        return find(objectTypeId, code, "not (" + MISSING + ")", Map.of());
    }

    /**
     * 그 유형의 모든 객체(상태와 관계없이)에서 이 속성의 값을 지운다. 값이 null인 키는 남겨 다시 쓰는 행과 잠금 시간을 줄인다. 민감 속성을 일반으로 내리며 남은
     * 값을 지울 때 쓴다(401). object_instance는 Entity가 없는 표라 JdbcClient로 바꾼다. 지운 객체 수를 돌려준다.
     */
    public int clearValues(Long objectTypeId, String code) {
        return jdbcClient
                .sql(
                        """
                        update object_instance o set properties = o.properties - :code
                        where o.object_type_id = :objectTypeId
                          and jsonb_typeof(o.properties -> :code) is distinct from 'null'
                          and jsonb_exists(o.properties, :code)
                        """)
                .param("objectTypeId", objectTypeId)
                .param("code", code)
                .update();
    }

    /** 값이 없거나 null인 객체. 필수로 바꿀 때 확인한다. */
    public ValueConflict findMissingValues(Long objectTypeId, String code) {
        return find(objectTypeId, code, MISSING, Map.of());
    }

    /** 자료형에 맞는 값 중 제약을 어기는 객체. 자료형이 다른 값은 {@link #findTypeMismatches}가 잡는다. */
    public ValueConflict findConstraintViolations(
            Long objectTypeId,
            String code,
            PropertyDataType dataType,
            PropertyConstraints constraints) {
        List<String> violations = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (constraints.min() != null) {
            violations.add(TEXT + "::numeric < :min");
            params.put("min", constraints.min());
        }
        if (constraints.max() != null) {
            violations.add(TEXT + "::numeric > :max");
            params.put("max", constraints.max());
        }
        if (constraints.maxLength() != null) {
            violations.add("char_length(" + TEXT + ") > :maxLength");
            params.put("maxLength", constraints.maxLength());
        }
        if (constraints.allowedValues() != null) {
            violations.add(
                    "not (cast(:allowedValues as jsonb) @> jsonb_build_array(" + VALUE + "))");
            params.put("allowedValues", jsonMapper.writeValueAsString(constraints.allowedValues()));
        }
        if (violations.isEmpty()) {
            return ValueConflict.NONE;
        }
        // AND는 계산 순서를 보장하지 않아 형변환 오류가 날 수 있으므로 CASE로 자료형부터 확인한다.
        String predicate =
                "case when not ("
                        + MISSING
                        + ") and "
                        + conforms(dataType)
                        + " then ("
                        + String.join(" or ", violations)
                        + ") else false end";
        return find(objectTypeId, code, predicate, params);
    }

    private ValueConflict find(
            Long objectTypeId, String code, String predicate, Map<String, Object> params) {
        String where =
                " from object_instance o where o.object_type_id = :objectTypeId and ("
                        + predicate
                        + ")";
        long count =
                jdbcClient
                        .sql("select count(*)" + where)
                        .param("objectTypeId", objectTypeId)
                        .param("code", code)
                        .params(params)
                        .query(Long.class)
                        .single();
        if (count == 0) {
            return ValueConflict.NONE;
        }
        List<Long> ids =
                jdbcClient
                        .sql(
                                "select o.id"
                                        + where
                                        + " order by o.id limit "
                                        + DefinitionReferenceRepository.MAX_IDS)
                        .param("objectTypeId", objectTypeId)
                        .param("code", code)
                        .params(params)
                        .query(Long.class)
                        .list();
        return new ValueConflict(count, ids);
    }

    /** 값이 그 자료형의 값인지 확인하는 SQL 조건. 값이 있는 행에서만 쓴다. */
    private static String conforms(PropertyDataType dataType) {
        String type = "jsonb_typeof(" + VALUE + ")";
        return switch (dataType) {
            case STRING -> type + " = 'string'";
            case INTEGER -> type + " = 'number' and pg_input_is_valid(" + TEXT + ", 'bigint')";
            case DECIMAL ->
                    type
                            + " in ('number', 'string') and pg_input_is_valid("
                            + TEXT
                            + ", 'numeric')";
            case DATE ->
                    type
                            + " = 'string' and "
                            + TEXT
                            + " ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' and pg_input_is_valid("
                            + TEXT
                            + ", 'date')";
            case TIME ->
                    type
                            + " = 'string' and "
                            + TEXT
                            + " ~ '^[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?$'"
                            + " and pg_input_is_valid("
                            + TEXT
                            + ", 'time')";
            case DATETIME ->
                    type
                            + " = 'string' and "
                            + TEXT
                            + " ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?(Z|[+-][0-9]{2}:[0-9]{2})$'"
                            + " and pg_input_is_valid("
                            + TEXT
                            + ", 'timestamptz')";
            case BOOLEAN -> type + " = 'boolean'";
            case JSON -> "true";
        };
    }

    /** 규칙에 맞지 않는 객체 수와 ID(최대 {@value DefinitionReferenceRepository#MAX_IDS}개). */
    public record ValueConflict(long count, List<Long> objectIds) {

        static final ValueConflict NONE = new ValueConflict(0, List.of());

        public boolean exists() {
            return count > 0;
        }
    }
}

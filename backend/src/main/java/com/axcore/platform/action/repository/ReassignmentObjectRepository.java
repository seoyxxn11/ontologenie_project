package com.axcore.platform.action.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 재배정 계획 검사가 읽는 온톨로지 객체(object_instance). 객체 유형은 코드로, 속성은 properties의 키로 찾는다. */
@Repository
@RequiredArgsConstructor
public class ReassignmentObjectRepository {

    private static final String SELECT =
            """
            select oi.id, ot.code as type_code, oi.status, oi.display_name,
                   oi.properties::text as properties
            from object_instance oi join object_type ot on ot.id = oi.object_type_id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** ID로 객체를 읽는다. 없는 ID는 결과에 없다. */
    public Map<Long, ObjectRow> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(SELECT + " where oi.id in (:ids)")
                .param("ids", ids)
                .query((rs, row) -> row(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(ObjectRow::id, Function.identity()));
    }

    /** 유형이 typeCode인 활성 객체 중 properties[key]가 values 중 하나인 것. */
    public List<ObjectRow> findActiveByProperty(
            String typeCode, String key, Collection<String> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        SELECT
                                + """
                                where ot.code = :typeCode and oi.status = 'ACTIVE'
                                  and oi.properties ->> :key in (:values)
                                order by oi.id
                                """)
                .param("typeCode", typeCode)
                .param("key", key)
                .param("values", values)
                .query((rs, row) -> row(rs))
                .list();
    }

    private ObjectRow row(ResultSet rs) throws SQLException {
        return new ObjectRow(
                rs.getLong("id"),
                rs.getString("type_code"),
                rs.getString("status"),
                rs.getString("display_name"),
                json.readValue(
                        rs.getString("properties"), new TypeReference<Map<String, Object>>() {}));
    }

    /** object_instance 한 행. 속성 값은 자료형에 따라 숫자·문자열로 오므로 text·number로 읽는다. */
    public record ObjectRow(
            long id,
            String typeCode,
            String status,
            String displayName,
            Map<String, Object> properties) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        /** 속성 값을 문자열로 읽는다. 숫자는 20과 20.0이 같도록 정규화한다. 없으면 null. */
        public String text(String key) {
            Object value = properties.get(key);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            }
            return value.toString();
        }

        /** 숫자 속성을 읽는다. DECIMAL은 문자열로 저장될 수 있다. 숫자가 아니거나 없으면 null. */
        public BigDecimal number(String key) {
            String text = text(key);
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}

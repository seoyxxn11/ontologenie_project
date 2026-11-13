package com.axcore.platform.ontology.repository;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.MaskedProperties;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사용자 요청 경로가 객체(object_instance)를 읽는 공통 진입점(S15P31C106-384). 모든 메서드가 {@link DataAccess}를 받아, 범위를
 * 빼먹은 채로는 객체를 읽을 수 없게 한다. 객체 조회 API는 {@link ObjectQueryRepository}가 같은 규칙으로 읽고, 그 밖의 경로(Action
 * Handler, Agent 계산 등)는 이 클래스로 읽는다. 다른 클래스가 object_instance를 직접 읽지 않는지는 {@code
 * ObjectInstanceReadBoundaryTests}가 막는다.
 *
 * <ul>
 *   <li>볼 수 없는 유형(그룹이 없거나 역할에 허용되지 않은 유형)의 객체는 없는 객체처럼 결과에 넣지 않는다(205 D4).
 *   <li>민감 값을 볼 수 없으면 민감 속성 값은 null로 바꾸고, 표시 이름은 객체 조회와 같은 규칙({@link ObjectDisplaySql})으로 다시 만든다.
 *   <li>민감 속성 값으로 객체를 찾는 요청은 그 값을 볼 수 없으면 빈 결과다. 찾기 결과로 민감 값을 알아낼 수 없게 한다.
 *   <li>정의 조회 용도의 범위로는 읽지 않는다({@link DataAccess#requireObjectValues}).
 * </ul>
 *
 * <p>object_instance는 Entity가 없는 표이고 표시 이름 가림이 SQL 조각이라 JdbcClient로 읽는다({@link
 * ObjectQueryRepository}와 같은 방식).
 */
@Repository
@RequiredArgsConstructor
public class ScopedObjectReader {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final String SELECT =
            """
            select oi.id, oi.object_type_id, ot.code as type_code, oi.status,
                   shown.display_name, oi.properties::text as properties
            from object_instance oi
            """
                    + ObjectDisplaySql.DISPLAY_JOINS
                    + """
            where oi.object_type_id in (:visibleTypeIds)
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** ID로 객체를 읽는다. 없는 ID와 볼 수 없는 유형의 객체는 결과에 없다. */
    public Map<Long, ScopedObject> findByIds(DataAccess access, Collection<Long> ids) {
        access.requireObjectValues();
        Set<Long> visible = access.visibleObjectTypeIds();
        if (ids.isEmpty() || visible.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(SELECT + "  and oi.id in (:ids)")
                .param("visibleTypeIds", visible)
                .param("maskedTypeIds", maskedTypeIds(access))
                .param("ids", ids)
                .query((rs, row) -> row(rs, access))
                .list()
                .stream()
                .collect(
                        Collectors.toMap(
                                ScopedObject::id,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new));
    }

    /**
     * 유형이 typeCode인 활성 객체 중 properties[key]가 values 중 하나인 것을 ID 순으로 읽는다. 유형을 볼 수 없거나, key가 볼 수 없는
     * 민감 속성이면 빈 목록이다.
     */
    public List<ScopedObject> findActiveByProperty(
            DataAccess access, String typeCode, String key, Collection<String> values) {
        access.requireObjectValues();
        if (values.isEmpty() || !access.canView(typeCode) || access.hidesValue(typeCode, key)) {
            return List.of();
        }
        return jdbc.sql(
                        SELECT
                                + """
                                  and ot.code = :typeCode and oi.status = 'ACTIVE'
                                  and oi.properties ->> :key in (:values)
                                order by oi.id
                                """)
                .param("visibleTypeIds", access.visibleObjectTypeIds())
                .param("maskedTypeIds", maskedTypeIds(access))
                .param("typeCode", typeCode)
                .param("key", key)
                .param("values", values)
                .query((rs, row) -> row(rs, access))
                .list();
    }

    private static SqlArrayValue maskedTypeIds(DataAccess access) {
        return new SqlArrayValue("bigint", access.maskedObjectTypeIds().toArray());
    }

    private ScopedObject row(ResultSet rs, DataAccess access) throws SQLException {
        long typeId = rs.getLong("object_type_id");
        MaskedProperties masked =
                access.mask(typeId, json.readValue(rs.getString("properties"), JSON_OBJECT));
        return new ScopedObject(
                rs.getLong("id"),
                typeId,
                rs.getString("type_code"),
                rs.getString("status"),
                rs.getString("display_name"),
                masked.properties(),
                masked.restrictedPropertyCodes());
    }

    /**
     * 범위를 적용한 객체 한 행. properties의 민감 값은 볼 수 없으면 null이고, 가린 속성 코드는 maskedPropertyCodes에 있다.
     * displayName도 가림 규칙을 적용한 값이다.
     */
    public record ScopedObject(
            long id,
            long objectTypeId,
            String typeCode,
            String status,
            String displayName,
            Map<String, Object> properties,
            List<String> maskedPropertyCodes) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        /** 속성 값을 문자열로 읽는다. 숫자는 20과 20.0이 같도록 정규화한다. 없거나 가린 값이면 null. */
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

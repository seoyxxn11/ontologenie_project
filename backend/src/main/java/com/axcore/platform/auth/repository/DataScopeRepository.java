package com.axcore.platform.auth.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 역할의 객체 유형별 조회 범위(S15P31C106-205). object_type·role_data_scope·property_definition을 한 번의 SQL로 묶어
 * 읽으므로 JdbcClient를 쓴다(role_data_scope는 Entity가 없다).
 */
@Repository
@RequiredArgsConstructor
public class DataScopeRepository {

    private final JdbcClient jdbc;

    /** 객체 유형 하나에 대한 역할의 범위. restrictedPropertyCodes는 그 유형의 RESTRICTED 속성 코드 전체다. */
    public record ObjectTypeScope(
            long objectTypeId,
            String objectTypeCode,
            boolean visible,
            boolean canViewRestricted,
            Set<String> restrictedPropertyCodes) {}

    /**
     * 모든 객체 유형에 대해 역할의 범위를 돌려준다. 유형의 access_group과 같은 그룹이 role_data_scope에 있어야 보인다. 그래서 그룹이 없는(미분류)
     * 유형과 역할에 허용 행이 없는 유형은 보이지 않는다. ADMIN도 다른 역할과 같이 허용 행으로만 정한다(384: 정의 관리와 값 열람을 나눈다).
     */
    public List<ObjectTypeScope> findAll(String roleCode) {
        return jdbc.sql(
                        """
                        select t.id as object_type_id, t.code as object_type_code,
                               s.access_group is not null as visible,
                               coalesce(s.can_view_restricted, false) as can_view_restricted,
                               coalesce(array_agg(p.code order by p.code)
                                            filter (where p.code is not null),
                                        array[]::varchar[]) as restricted_codes
                        from object_type t
                        left join role_data_scope s
                               on s.access_group = t.access_group
                              and s.role_id = (select r.id from role r where r.code = :roleCode)
                        left join property_definition p
                               on p.object_type_id = t.id and p.sensitivity = 'RESTRICTED'
                        group by t.id, s.access_group, s.can_view_restricted
                        """)
                .param("roleCode", roleCode)
                .query((rs, row) -> scope(rs))
                .list();
    }

    /**
     * 모든 객체 유형의 정의 조회 범위. 모든 유형이 보이고 민감 속성 정의도 본다. 역할과 관계없는 내부 정의 조회(DataPurpose.DEFINITIONS)만 쓴다.
     * 이 범위로는 객체 값을 읽지 않는다.
     */
    public List<ObjectTypeScope> findAllDefinitions() {
        return jdbc.sql(
                        """
                        select t.id as object_type_id, t.code as object_type_code,
                               true as visible, true as can_view_restricted,
                               coalesce(array_agg(p.code order by p.code)
                                            filter (where p.code is not null),
                                        array[]::varchar[]) as restricted_codes
                        from object_type t
                        left join property_definition p
                               on p.object_type_id = t.id and p.sensitivity = 'RESTRICTED'
                        group by t.id
                        """)
                .query((rs, row) -> scope(rs))
                .list();
    }

    private static ObjectTypeScope scope(ResultSet rs) throws SQLException {
        return new ObjectTypeScope(
                rs.getLong("object_type_id"),
                rs.getString("object_type_code"),
                rs.getBoolean("visible"),
                rs.getBoolean("can_view_restricted"),
                codes(rs.getArray("restricted_codes")));
    }

    private static Set<String> codes(Array array) throws SQLException {
        try {
            return Set.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}

package com.axcore.platform.action.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Action 정의가 가리키는 다른 표(permission, role_permission, object_type)를 확인한다. */
@Repository
@RequiredArgsConstructor
public class ActionReferenceRepository {

    private final JdbcClient jdbc;

    public boolean permissionExists(String code) {
        return jdbc.sql("select exists (select 1 from permission where code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    public boolean roleHasPermission(String roleCode, String permissionCode) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from role_permission rp
                            join role r on r.id = rp.role_id
                            join permission p on p.id = rp.permission_id
                            where r.code = :roleCode and p.code = :permissionCode)
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
    }

    public boolean objectTypeExists(long id) {
        return jdbc.sql("select exists (select 1 from object_type where id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}

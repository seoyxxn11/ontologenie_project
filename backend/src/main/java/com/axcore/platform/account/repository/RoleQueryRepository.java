package com.axcore.platform.account.repository;

import com.axcore.platform.account.dto.RoleResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 역할과 권한 코드 (API 008). 권한은 Entity 없이 role_permission·permission 표에서 바로 읽는다. */
@Repository
@RequiredArgsConstructor
public class RoleQueryRepository {

    private final JdbcClient jdbc;

    /** 모든 역할을 등록 순서로, 권한 코드는 이름 순으로 돌려준다. 권한이 없는 역할은 빈 목록이다. */
    public List<RoleResponse> findAllWithPermissions() {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select r.code, r.name, p.code as permission_code
                        from role r
                        left join role_permission rp on rp.role_id = r.id
                        left join permission p on p.id = rp.permission_id
                        order by r.id, p.code
                        """)
                .query(
                        (rs, row) -> {
                            String code = rs.getString("code");
                            names.putIfAbsent(code, rs.getString("name"));
                            List<String> codes =
                                    permissions.computeIfAbsent(code, key -> new ArrayList<>());
                            String permission = rs.getString("permission_code");
                            if (permission != null) {
                                codes.add(permission);
                            }
                            return code;
                        })
                .list();
        return names.entrySet().stream()
                .map(
                        entry ->
                                new RoleResponse(
                                        entry.getKey(),
                                        entry.getValue(),
                                        List.copyOf(permissions.get(entry.getKey()))))
                .toList();
    }
}

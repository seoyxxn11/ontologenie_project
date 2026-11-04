package com.axcore.platform.account.dto;

import java.util.List;

/** API 008의 Role. permissions는 그 역할에 연결된 권한 코드다(role_permission). */
public record RoleResponse(String code, String name, List<String> permissions) {}

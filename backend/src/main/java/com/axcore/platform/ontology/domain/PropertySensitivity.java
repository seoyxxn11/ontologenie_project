package com.axcore.platform.ontology.domain;

/** RESTRICTED 속성은 역할의 민감 열람 권한(role_data_scope.can_view_restricted)이 있어야 값을 본다. */
public enum PropertySensitivity {
    NORMAL,
    RESTRICTED
}

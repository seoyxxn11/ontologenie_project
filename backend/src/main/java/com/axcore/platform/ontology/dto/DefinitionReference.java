package com.axcore.platform.ontology.dto;

import java.util.List;

/** 정의를 지우지 못하게 막는 참조. IN_USE 오류의 details.references[] 항목이다 (API 025·029·033). */
public record DefinitionReference(String tableName, long count, List<Long> ids) {}

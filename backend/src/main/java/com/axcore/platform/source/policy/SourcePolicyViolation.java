package com.axcore.platform.source.policy;

/**
 * 켜진 매핑 하나가 원천 데이터 정책을 어긴 곳(S15P31C106-402). 적재 계획({@code FullLoadPlanner})이 같은 판단으로 적재를 거절하고, 유형
 * 공개(024 그룹 지정) 검사와 위반 보고 API가 이 목록을 쓴다.
 *
 * @param propertyMappingId 키 컬럼 위반이면 null
 * @param propertyDefinitionId 키 컬럼 위반이면 null
 * @param propertyCode 키 컬럼 위반이면 null
 */
public record SourcePolicyViolation(
        Kind kind,
        long sourceResourceId,
        String resourceKey,
        String columnName,
        long objectMappingId,
        long objectTypeId,
        Long propertyMappingId,
        Long propertyDefinitionId,
        String propertyCode) {

    public enum Kind {
        /** 금지 표·컬럼을 키나 속성 매핑으로 가리킨다. */
        FORBIDDEN_COLUMN,
        /** 민감 컬럼을 객체 매핑 키로 쓴다. 원천 키·표시 이름으로 값이 드러난다. */
        RESTRICTED_KEY,
        /** 민감 컬럼을 일반(NORMAL) 속성이 담는다. */
        RESTRICTED_UNDER_CLASSIFIED
    }
}

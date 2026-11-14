package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.policy.SourcePolicyViolation;
import java.util.List;

/**
 * 원천 데이터 정책 위반 보고(S15P31C106-402). 정책 파일을 바꾸거나 배포하기 전에, 또 유형을 공개하기 전에 본다.
 *
 * @param policyVersion 지금 정책 목록의 지문({@code SourceColumnPolicy.fingerprint})
 * @param violations 켜진 매핑이 정책을 어긴 곳. 비어 있어야 한다
 * @param blockedResources 켜진 매핑이 있지만 적재 계획을 만들 수 없는 원천 테이블. 이 테이블은 적재하지 않고 CDC 변경도 건너뛴다
 */
public record SourcePolicyReportResponse(
        String policyVersion, List<Violation> violations, List<BlockedResource> blockedResources) {

    /**
     * @param accessGroup 위반 매핑이 채우는 객체 유형의 조회 그룹. 값이 있으면 이미 공개된 유형이다
     */
    public record Violation(
            SourcePolicyViolation.Kind kind,
            long sourceResourceId,
            String resourceKey,
            String columnName,
            long objectMappingId,
            long objectTypeId,
            String objectTypeCode,
            String accessGroup,
            Long propertyMappingId,
            Long propertyDefinitionId,
            String propertyCode) {}

    /**
     * @param reloadPending 다시 읽기 표시가 붙어 있는가(CDC 테이블). 고치면 감시 작업이 다시 읽는다
     * @param reason 적재 계획을 만들지 못한 사유(수집 실패 문구와 같다)
     */
    public record BlockedResource(
            long sourceResourceId,
            long dataSourceId,
            String resourceKey,
            SyncStrategy syncStrategy,
            boolean reloadPending,
            String reason) {}
}

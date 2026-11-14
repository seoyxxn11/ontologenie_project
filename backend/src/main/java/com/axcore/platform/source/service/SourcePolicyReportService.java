package com.axcore.platform.source.service;

import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.dto.SourcePolicyReportResponse;
import com.axcore.platform.source.dto.SourcePolicyReportResponse.BlockedResource;
import com.axcore.platform.source.dto.SourcePolicyReportResponse.Violation;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.policy.SourcePolicyViolation;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 원천 데이터 정책 위반 보고(S15P31C106-402). 켜진 매핑이 있는 원천 테이블마다 적재 계획과 같은 판단으로 위반을 모으고, 계획을 만들 수 없는 테이블을 사유와
 * 함께 돌려준다. 테이블 수만큼 계획을 만들므로 관리자가 가끔 부르는 조회다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourcePolicyReportService {

    private final ObjectSourceMappingRepository mappingRepository;
    private final SourceResourceRepository resourceRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final FullLoadPlanner planner;
    private final SourceColumnPolicy sourceColumnPolicy;

    public SourcePolicyReportResponse report() {
        List<Long> resourceIds = mappingRepository.findAllActiveSourceResourceIds();
        List<SourcePolicyViolation> violations = new ArrayList<>();
        List<BlockedResource> blocked = new ArrayList<>();
        Map<Long, SourceResource> resources =
                resourceRepository.findAllById(resourceIds).stream()
                        .collect(Collectors.toMap(SourceResource::getId, Function.identity()));
        for (long resourceId : resourceIds) {
            SourceResource resource = resources.get(resourceId);
            if (resource == null) {
                continue;
            }
            String failure;
            try {
                violations.addAll(planner.violations(resourceId));
                failure = planner.tryPlan(resourceId).failure();
            } catch (RuntimeException e) {
                // 한 테이블의 예상하지 못한 오류(깨진 변환 단계 등)로 보고 전체가 실패하지 않게 그 테이블만 막힌 것으로 알린다
                log.warn("적재 계획을 확인하지 못했습니다 resourceId={}", resourceId, e);
                failure = "적재 계획을 확인하지 못했습니다(예상하지 못한 오류). 서버 로그를 확인하세요.";
            }
            if (failure != null) {
                blocked.add(
                        new BlockedResource(
                                resourceId,
                                resource.getDataSourceId(),
                                resource.getResourceKey(),
                                resource.getSyncStrategy(),
                                resource.getSyncStrategy() == SyncStrategy.CDC
                                        && resource.getLoadedLsn() == null,
                                failure));
            }
        }
        Map<Long, ObjectType> types =
                objectTypeRepository
                        .findAllById(
                                violations.stream()
                                        .map(SourcePolicyViolation::objectTypeId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(ObjectType::getId, Function.identity()));
        return new SourcePolicyReportResponse(
                sourceColumnPolicy.fingerprint(),
                violations.stream().map(v -> violation(v, types.get(v.objectTypeId()))).toList(),
                blocked);
    }

    private static Violation violation(SourcePolicyViolation violation, ObjectType type) {
        return new Violation(
                violation.kind(),
                violation.sourceResourceId(),
                violation.resourceKey(),
                violation.columnName(),
                violation.objectMappingId(),
                violation.objectTypeId(),
                type == null ? null : type.getCode(),
                type == null ? null : type.getAccessGroup(),
                violation.propertyMappingId(),
                violation.propertyDefinitionId(),
                violation.propertyCode());
    }
}

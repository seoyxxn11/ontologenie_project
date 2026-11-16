package com.axcore.platform.source.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.ResourceKind;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.dto.SourceSampleAcceptedResponse;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 016 원천 표본 요청 접수. 원천 조회는 배치에서 실행한다. */
@Service
@RequiredArgsConstructor
public class SourceSampleRequestService {

    private final SourceResourceRepository resources;
    private final SourcePreviewRepository previews;
    private final AuditRecorder audits;
    private final Clock clock;

    @Transactional
    public SourceSampleAcceptedResponse request(
            long sourceId, long resourceId, int rowLimit, LoginAccount account) {
        SourceResource resource =
                resources
                        .findByIdAndDataSourceId(resourceId, sourceId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "source_resource",
                                                        "id",
                                                        resourceId)));
        if (!isReadableTable(resource)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "SYNC 계정으로 읽을 수 있는 원천 테이블만 표본을 조회할 수 있습니다.",
                    Map.of("field", "resourceId"));
        }

        previews.lockRequester(account.id(), resourceId);
        Instant now = Instant.now(clock);
        var active = previews.findActiveSample(resourceId, account.id(), rowLimit, now);
        boolean joined = active.isPresent();
        var accepted =
                active.orElseGet(
                        () -> previews.insertSample(resourceId, account.id(), rowLimit, now));
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.SOURCE_PREVIEW)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType(QueuedJobType.SOURCE_PREVIEW.table())
                        .targetId(accepted.id())
                        .input(
                                Map.of(
                                        "previewKind",
                                        "SAMPLE",
                                        "sourceResourceId",
                                        resourceId,
                                        "rowLimit",
                                        rowLimit))
                        .result(Map.of("joined", joined, "status", accepted.status()))
                        .build());
        return new SourceSampleAcceptedResponse(
                accepted.id(),
                accepted.status(),
                joined,
                accepted.requestedAt(),
                accepted.expiresAt());
    }

    /** SYNC 계정으로 읽을 수 있는 원천 테이블인가. 원천 표본(016)과 제안 검토의 제한 값 확인(447)이 같이 쓴다. */
    public static boolean isReadableTable(SourceResource resource) {
        return resource.getResourceKind() == ResourceKind.TABLE && resource.isReadable();
    }
}

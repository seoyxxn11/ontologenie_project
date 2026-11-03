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
import com.axcore.platform.source.dto.ConnectionCheckResponse;
import com.axcore.platform.source.dto.ConnectionCheckResultResponse;
import com.axcore.platform.source.repository.SourceQueryRepository;
import com.axcore.platform.source.repository.SourceQueryRepository.ConnectionCheck;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원천 연결 검사 (API 013·100, FN-SRC-02). api는 source_connection_check에 QUEUED로 넣기만 하고, 배치가 SYNC·AI 계정으로
 * 접속해 결과와 자원·컬럼 목록을 채운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceConnectionCheckService {

    private final DataSourceService dataSourceService;
    private final SourceQueryRepository queryRepository;
    private final AuditRecorder audits;
    private final Clock clock;

    /** API 013. 같은 원천의 대기·실행 중 검사가 있으면 새로 만들지 않고 그 검사에 합류한다. 합류해도 감사 기록은 남긴다. */
    @Transactional
    public ConnectionCheckResponse request(Long dataSourceId, LoginAccount account) {
        dataSourceService.findDataSource(dataSourceId);
        long checkId;
        boolean joined;
        while (true) {
            var inserted =
                    queryRepository.insertCheckIfAbsent(
                            dataSourceId, account.id(), Instant.now(clock));
            if (inserted.isPresent()) {
                checkId = inserted.get();
                joined = false;
                break;
            }
            var active = queryRepository.findActiveCheckForUpdate(dataSourceId);
            if (active.isPresent()) {
                checkId = active.get();
                joined = true;
                break;
            }
            // 충돌한 검사가 기다리는 동안 끝났으면 새 검사를 다시 넣어 본다.
        }
        ConnectionCheck check = findCheck(dataSourceId, checkId);
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.SOURCE_CHECK)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType(QueuedJobType.SOURCE_CHECK.table())
                        .targetId(checkId)
                        .input(Map.of("dataSourceId", dataSourceId))
                        .result(Map.of("joined", joined, "status", check.status()))
                        .build());
        return ConnectionCheckResponse.of(check, joined);
    }

    /** API 100. 검사가 경로의 원천에 속하지 않으면 없는 것으로 본다. */
    public ConnectionCheckResultResponse result(Long dataSourceId, Long checkId) {
        return ConnectionCheckResultResponse.from(findCheck(dataSourceId, checkId));
    }

    private ConnectionCheck findCheck(long dataSourceId, long checkId) {
        return queryRepository
                .findCheck(dataSourceId, checkId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of(
                                                "resource",
                                                "source_connection_check",
                                                "id",
                                                checkId)));
    }
}

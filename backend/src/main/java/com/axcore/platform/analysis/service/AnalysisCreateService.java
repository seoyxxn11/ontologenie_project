package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.domain.AnalysisRunCreated;
import com.axcore.platform.analysis.dto.AnalysisCreateRequest;
import com.axcore.platform.analysis.dto.AnalysisRunResponse;
import com.axcore.platform.analysis.repository.AnalysisCreateRepository;
import com.axcore.platform.analysis.repository.AnalysisQueryRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class AnalysisCreateService {
    private final AnalysisCreateRepository repository;
    private final AnalysisQueryRepository queries;
    private final SourceResourceRepository sourceResources;
    private final SourceColumnRepository sourceColumns;
    private final AuditRecorder audits;
    private final Clock clock;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final AnalysisColumnScope columnScope;

    @Transactional
    public AnalysisRunResponse create(AnalysisCreateRequest request, LoginAccount account) {
        var targets = normalize(request);
        var ids = targets.stream().map(Target::sourceResourceId).toList();
        validateReferences(request, targets, ids);
        Map<String, Object> range = new LinkedHashMap<>(request.analysisRange());
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (var target : targets) {
            if (target.columns() != null)
                columns.put(Long.toString(target.sourceResourceId()), target.columns());
        }
        if (!columns.isEmpty()) range.put("columns", columns);
        String key = "ANALYSIS:" + digest(json.writeValueAsString(targets));
        AnalysisRunResponse response;
        while (true) {
            var inserted =
                    repository.insertIfAbsent(
                            request.runKind(),
                            key,
                            range,
                            request.basedOnSyncRunId(),
                            account.id(),
                            Instant.now(clock));
            if (inserted.isPresent()) {
                repository.insertTargets(inserted.get(), ids);
                response = read(inserted.get(), false);
                break;
            }
            var active = repository.findActiveForUpdate(key);
            if (active.isPresent()) {
                response = read(active.get(), true);
                break;
            }
            // 충돌한 실행이 기다리는 동안 끝났으면 활성 실행 생성을 다시 시도한다.
        }
        if (!response.joined()) {
            // 커밋 뒤 AnalysisStartNotifier가 AI에 시작을 알린다. 합류한 요청은 이미 알린 실행이라 보내지 않는다.
            events.publishEvent(new AnalysisRunCreated(response.id()));
        }
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.ANALYSIS_RUN)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType("analysis_run")
                        .targetId(response.id())
                        .input(Map.of("targetCount", ids.size()))
                        .result(Map.of("joined", response.joined(), "status", response.status()))
                        .build());
        return response;
    }

    private List<Target> normalize(AnalysisCreateRequest request) {
        if (request.analysisRange().containsKey("columns")) {
            throw BusinessException.invalidField(
                    "analysisRange.columns", "컬럼은 targets[].columns로 지정해 주세요.");
        }
        Map<Long, Target> sorted = new TreeMap<>();
        for (var target : request.targets()) {
            List<String> columns =
                    target.columns() == null ? null : List.copyOf(new TreeSet<>(target.columns()));
            if (sorted.putIfAbsent(
                            target.sourceResourceId(),
                            new Target(target.sourceResourceId(), columns))
                    != null) {
                throw BusinessException.invalidField("targets", "같은 분석 대상을 중복 지정할 수 없습니다.");
            }
        }
        return new ArrayList<>(sorted.values());
    }

    private void validateReferences(
            AnalysisCreateRequest request, List<Target> targets, List<Long> ids) {
        Map<Long, String> resourceKeys =
                sourceResources.findAllById(ids).stream()
                        .collect(
                                Collectors.toMap(
                                        SourceResource::getId, SourceResource::getResourceKey));
        if (!resourceKeys.keySet().containsAll(ids)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "source_resource"));
        }
        if (request.basedOnSyncRunId() != null
                && !repository.syncExists(request.basedOnSyncRunId())) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "sync_run"));
        }
        Map<Long, Set<String>> existing = new HashMap<>();
        for (SourceColumn column : sourceColumns.findBySourceResourceIdInAndIsPresentTrue(ids)) {
            existing.computeIfAbsent(column.getSourceResourceId(), ignored -> new HashSet<>())
                    .add(column.getColumnName());
        }
        for (var target : targets) {
            if (target.columns() != null
                    && !existing.getOrDefault(target.sourceResourceId(), Set.of())
                            .containsAll(target.columns())) {
                throw BusinessException.invalidField(
                        "targets", "선택한 컬럼이 해당 원천 자원의 현재 컬럼 목록에 없습니다.");
            }
            // 금지 표와 정책상 남는 컬럼이 없는 대상은 접수 때 막는다. 컬럼을 빼는 일은 시작 알림(AnalysisStartService)이 한다.
            columnScope.columnsForModel(
                    resourceKeys.get(target.sourceResourceId()),
                    target.columns(),
                    existing.getOrDefault(target.sourceResourceId(), Set.of()));
        }
    }

    private AnalysisRunResponse read(long id, boolean joined) {
        return queries.findById(id)
                .map(run -> AnalysisRunResponse.of(run, joined))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
        }
    }

    private record Target(long sourceResourceId, List<String> columns) {}
}

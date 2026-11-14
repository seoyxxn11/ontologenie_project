package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.dto.AnalysisStartRequest;
import com.axcore.platform.analysis.dto.AnalysisStartRequest.Source;
import com.axcore.platform.analysis.dto.AnalysisStartRequest.StructureOnlyColumn;
import com.axcore.platform.analysis.dto.AnalysisStartRequest.Target;
import com.axcore.platform.analysis.repository.AnalysisRunRepository;
import com.axcore.platform.analysis.repository.AnalysisStartRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.service.SourceCredentialException;
import com.axcore.platform.source.service.SourceCredentialService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 분석 시작 알림의 본문을 만들고, 알리지 못한 실행을 FAILED로 바꾼다(API 018 하단). 018 커밋 뒤에 불리므로 두 메서드 모두 새 트랜잭션에서 돈다. AI
 * 호출 자체는 트랜잭션 밖({@link AnalysisStartNotifier})에서 한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisStartService {
    private final AnalysisStartRepository repository;
    private final AnalysisRunRepository runs;
    private final SourceCredentialService credentials;
    private final AuditRecorder audits;
    private final Clock clock;
    private final SourceColumnRepository sourceColumns;
    private final AnalysisColumnScope columnScope;

    /**
     * QUEUED 실행의 시작 알림 본문. 이미 다른 상태면 빈 값이다. 원천 AI 계정은 여기서 복호화한다. POSTGRES의 sslmode는 늘
     * require다(2026-11-03 결정).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AnalysisStartRequest> prepare(long runId) {
        var run = repository.findRun(runId).filter(found -> "QUEUED".equals(found.status()));
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<String>> columns = columns(run.get().analysisRange());
        List<AnalysisStartRepository.Target> found = repository.findTargets(runId);
        Map<Long, Map<String, SourceColumn>> catalog = catalog(found);
        List<Target> targets = new ArrayList<>();
        Map<String, List<AnalysisStartRepository.Target>> bySource = new TreeMap<>();
        for (var target : found) {
            targets.add(
                    new Target(
                            target.sourceResourceId(),
                            target.dataSourceCode(),
                            target.resourceKey(),
                            columnsForModel(
                                    target,
                                    columns.get(Long.toString(target.sourceResourceId())),
                                    catalog),
                            columnScope.metadataForModel(
                                    target.resourceKey(), target.sourceMetadata()),
                            structureOnlyColumns(
                                    target,
                                    columns.get(Long.toString(target.sourceResourceId())),
                                    catalog)));
            bySource.computeIfAbsent(target.dataSourceCode(), ignored -> new ArrayList<>())
                    .add(target);
        }
        List<Source> sources = new ArrayList<>();
        for (var group : bySource.values()) {
            sources.add(source(group));
        }
        String runKind = run.get().runKind();
        boolean reanalysis = "REANALYSIS".equals(runKind);
        return Optional.of(
                new AnalysisStartRequest(
                        runId,
                        runKind,
                        targets,
                        sources,
                        reanalysis ? repository.findPastDecisions(runId) : List.of(),
                        reanalysis ? repository.findPreviousProposals(runId) : List.of()));
    }

    /** 아직 QUEUED일 때만 FAILED로 바꾼다. AI가 이미 105로 상태를 바꿨으면 그대로 둔다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long runId, String reason) {
        if (!runs.failIfQueued(runId, reason, Instant.now(clock))) {
            return;
        }
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.ANALYSIS_RUN)
                        .actor(Actor.system())
                        .entryPoint(EntryPoint.SYSTEM)
                        .status(ExecutionStatus.FAILED)
                        .targetType("analysis_run")
                        .targetId(runId)
                        .result(Map.of("reason", reason))
                        .build());
    }

    /**
     * 원천 데이터 정책을 018 접수 때와 같이 다시 적용한다. 접수 뒤 정책이 바뀌었거나 정책 이전에 접수된 실행도 금지 표·컬럼과 민감 컬럼을 AI에 넘기지 않는다.
     */
    private List<String> columnsForModel(
            AnalysisStartRepository.Target target,
            List<String> requested,
            Map<Long, Map<String, SourceColumn>> catalog) {
        try {
            return columnScope.columnsForModel(
                    target.resourceKey(),
                    requested,
                    catalog.getOrDefault(target.sourceResourceId(), Map.of()).keySet());
        } catch (BusinessException ex) {
            throw new AnalysisStartException(ex.getMessage() + " (" + target.resourceKey() + ")");
        }
    }

    /** 민감 컬럼의 이름·자료형·원천 주석(416). 카탈로그에 없는 컬럼은 자료형을 몰라 넘기지 않는다. */
    private List<StructureOnlyColumn> structureOnlyColumns(
            AnalysisStartRepository.Target target,
            List<String> requested,
            Map<Long, Map<String, SourceColumn>> catalog) {
        Map<String, SourceColumn> known = catalog.getOrDefault(target.sourceResourceId(), Map.of());
        return columnScope
                .structureOnlyColumns(target.resourceKey(), requested, known.keySet())
                .stream()
                .map(known::get)
                .filter(Objects::nonNull)
                .map(
                        column ->
                                new StructureOnlyColumn(
                                        column.getColumnName(),
                                        column.getSourceDataType(),
                                        column.getSourceComment(),
                                        column.isNullable(),
                                        SourceColumnGrade.RESTRICTED.name()))
                .toList();
    }

    private Map<Long, Map<String, SourceColumn>> catalog(
            List<AnalysisStartRepository.Target> targets) {
        Map<Long, Map<String, SourceColumn>> catalog = new HashMap<>();
        for (SourceColumn column :
                sourceColumns.findBySourceResourceIdInAndIsPresentTrue(
                        targets.stream()
                                .map(AnalysisStartRepository.Target::sourceResourceId)
                                .toList())) {
            catalog.computeIfAbsent(column.getSourceResourceId(), ignored -> new HashMap<>())
                    .put(column.getColumnName(), column);
        }
        return catalog;
    }

    private Source source(List<AnalysisStartRepository.Target> group) {
        var first = group.getFirst();
        String code = first.dataSourceCode();
        Map<String, Object> config = first.connectionConfig();
        return switch (first.sourceKind()) {
            case "POSTGRES" -> {
                Set<String> schemas = new HashSet<>();
                group.forEach(target -> schemas.add(schema(target.resourceKey())));
                if (schemas.size() > 1) {
                    throw new AnalysisStartException(
                            "한 원천에서 여러 스키마의 테이블을 함께 분석할 수 없습니다 (" + code + ")");
                }
                SourceAccount account = aiAccount(first.dataSourceId(), code);
                Map<String, Object> connection = new LinkedHashMap<>();
                connection.put("host", config.get("host"));
                connection.put("port", config.get("port"));
                connection.put("database", config.get("database"));
                connection.put("sslmode", "require");
                connection.put("username", account.username());
                connection.put("password", account.password());
                yield new Source(code, "POSTGRES", connection, schemas.iterator().next(), null);
            }
            case "CSV" -> {
                if (!(config.get("path") instanceof String path) || path.isBlank()) {
                    throw new AnalysisStartException("CSV 원천의 폴더 경로가 없습니다 (" + code + ")");
                }
                yield new Source(code, "CSV", Map.of(), null, path);
            }
            default -> throw new AnalysisStartException("문서 원천은 아직 분석할 수 없습니다 (" + code + ")");
        };
    }

    private SourceAccount aiAccount(long dataSourceId, String code) {
        try {
            return credentials
                    .findAccount(dataSourceId, CredentialPurpose.AI)
                    .orElseThrow(
                            () ->
                                    new AnalysisStartException(
                                            "원천 AI 계정이 등록되지 않았습니다 (" + code + ")"));
        } catch (SourceCredentialException ex) {
            throw new AnalysisStartException("원천 AI 계정 비밀번호를 풀지 못했습니다 (" + code + ")");
        }
    }

    /** resourceKey는 원천.테이블(예: mes.work_order)이다. 점이 없으면 스키마를 보내지 않아 AI 기본값(public)을 쓴다. */
    private static String schema(String resourceKey) {
        int dot = resourceKey.indexOf('.');
        return dot < 0 ? null : resourceKey.substring(0, dot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> columns(Map<String, Object> analysisRange) {
        return analysisRange.get("columns") instanceof Map<?, ?> columns
                ? (Map<String, List<String>>) columns
                : Map.of();
    }
}

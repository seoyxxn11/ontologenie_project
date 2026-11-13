package com.axcore.platform.check.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.dto.CheckRunCreateRequest;
import com.axcore.platform.check.dto.CheckRunCreateRequest.Scope;
import com.axcore.platform.check.dto.CheckRunRequestResponse;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.repository.CheckRunRepository.NewRun;
import com.axcore.platform.check.repository.CheckRunRepository.ReverificationScope;
import com.axcore.platform.check.repository.CheckRunRepository.RunState;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 060 수동 검사·재검증 요청 접수(S15P31C106-191). 백엔드는 원천에 접속하지 않고 check_run에 QUEUED 작업만 넣는다. 실행은 배치가 한다.
 *
 * <p>같은 종류·비교 대상·범위(규칙 개정본 목록)·데이터 기준이면 같은 작업이다. 대기·실행 중이면 합류한다(202). 이 API는 중복 요청에 409를 쓰지 않는다.
 *
 * <p>사람이 요청한 검사는 끝난 결과를 다시 쓰지 않고 항상 지금 기준으로 새로 돈다(Jira 389). 재검증은 마지막 조치완료 뒤에 시작한 실행만 해결 근거가
 * 되는데(246), 끝난 실행은 정의상 지금보다 먼저 시작했다. 그래서 재검증 통과 뒤 확인중 → 조치완료로 되돌리고 다시 요청했을 때 예전 결과를 돌려주면, 데이터가 바뀔
 * 때까지 해결할 수 없었다. 수동 검사도 같은 규칙으로 맞춘다. 수집 뒤 검사(SYNC, {@link SyncCheckRequester})는 데이터가 그대로면 다시 돌지 않도록
 * 재사용을 유지한다. 응답의 {@code reused}는 프런트 호환을 위해 남기고 늘 false다.
 *
 * <p>감사 기록은 접수 줄을 남긴다(개발 가이드 14.2). 새로 넣었든 합류했든 요청한 사람·{@code SUCCEEDED} 한 줄이고, 배치 실행 틀이 남기는 결과 줄과
 * {@code check_run} ID로 묶인다. 범위 종류와 규칙·문제 개수만 남기고 ID 목록은 넣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CheckRunRequestService {

    private static final int MAX_ATTEMPTS = 3;

    private final CheckRunRepository checkRunRepository;
    private final LinkMappingRepository linkMappingRepository;
    private final IssueRepository issueRepository;
    private final LinkMappingRuleLoader ruleLoader;
    private final AuditRecorder audits;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional
    public CheckRunRequestResponse request(CheckRunCreateRequest request, LoginAccount account) {
        String triggerKind = request.triggerKind();
        Scope scope = request.scope();
        requireShape(triggerKind, scope, request.comparedToRunId());
        if (request.comparedToRunId() != null) {
            requireComparable(request.comparedToRunId());
        }

        Map<String, Object> storedScope = resolveScope(scope);
        String scopeJson = json.writeValueAsString(storedScope);
        ReverificationScope reverificationScope = null;
        if ("REVERIFY".equals(triggerKind)) {
            reverificationScope = new ReverificationScope(scopeJson, request.comparedToRunId());
            checkRunRepository.lockReverificationScope(reverificationScope);
        }
        @SuppressWarnings("unchecked")
        List<Long> ruleIds = (List<Long>) storedScope.get("linkMappingIds");

        TreeSet<Long> resourceIds = new TreeSet<>();
        ruleIds.forEach(id -> resourceIds.addAll(ruleLoader.resourceIds(id)));
        CheckDataBasis basis = new CheckDataBasis(checkRunRepository.loadCheckpoints(resourceIds));
        // 실행기가 같은 변환을 다시 하므로, 바꿀 수 없는 규칙은 접수 때 거절한다.
        for (long ruleId : ruleIds) {
            try {
                ruleLoader.load(ruleId, basis);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "검사할 수 없는 규칙이 있습니다.",
                        Map.of(
                                "field",
                                "scope",
                                "linkMappingId",
                                ruleId,
                                "reason",
                                ex.getMessage()));
            }
        }

        String basisJson = json.writeValueAsString(basis);
        String linkState =
                checkRunRepository.linkState(linkTypeIds(linkMappingRepository, ruleIds));
        String workKey =
                workKey(triggerKind, request.comparedToRunId(), scopeJson, basisJson, linkState);
        NewRun run =
                new NewRun(
                        triggerKind,
                        scopeJson,
                        workKey,
                        basisJson,
                        request.comparedToRunId(),
                        account.id(),
                        Instant.now(clock));

        CheckRunRequestResponse response = accept(run, reverificationScope);
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.CHECK_RUN)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType(QueuedJobType.CHECK_RUN.table())
                        .targetId(response.run().id())
                        .input(
                                intake(
                                        triggerKind,
                                        scope.kind(),
                                        storedScope,
                                        request.comparedToRunId()))
                        .result(
                                Map.of(
                                        "joined", response.joined(),
                                        "reused", response.reused(),
                                        "status", response.run().status()))
                        .build());
        return response;
    }

    // 같은 작업이 그 사이 끝나거나 롤백되면 다시 본다.
    private CheckRunRequestResponse accept(NewRun run, ReverificationScope reverificationScope) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            // 재검증은 대기 중인 작업에만 기준과 상관없이 합류한다. 실행 중인 작업은 같은 기준(work_key)일 때만 아래에서 합류한다.
            if (reverificationScope != null) {
                Optional<Long> queued =
                        checkRunRepository.findQueuedReverification(reverificationScope);
                if (queued.isPresent()) {
                    return response(queued.get(), true);
                }
            }
            Optional<Long> inserted = checkRunRepository.insertQueued(run);
            if (inserted.isPresent()) {
                return response(inserted.get(), false);
            }
            Optional<Long> active = checkRunRepository.findActive(run.workKey());
            if (active.isPresent()) {
                return response(active.get(), true);
            }
        }
        throw new IllegalStateException("검사 요청을 접수하지 못했습니다: " + run.workKey());
    }

    /** 접수 줄의 입력. ID 목록 대신 개수만 남긴다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> intake(
            String triggerKind,
            String scopeKind,
            Map<String, Object> storedScope,
            Long comparedToRunId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("triggerKind", triggerKind);
        input.put("scopeKind", scopeKind);
        input.put("linkMappingCount", ((List<Long>) storedScope.get("linkMappingIds")).size());
        Object issueIds = storedScope.get("issueIds");
        if (issueIds instanceof List<?> issues) {
            input.put("issueCount", issues.size());
        }
        if (comparedToRunId != null) {
            input.put("comparedToRunId", comparedToRunId);
        }
        return input;
    }

    /** 종류·범위·비교 대상의 조합. 틀리면 400이다. */
    private static void requireShape(String triggerKind, Scope scope, Long comparedToRunId) {
        boolean reverify = "REVERIFY".equals(triggerKind);
        if (reverify && comparedToRunId == null) {
            throw BusinessException.invalidField("comparedToRunId", "REVERIFY에는 필수입니다.");
        }
        if (!reverify && comparedToRunId != null) {
            throw BusinessException.invalidField("comparedToRunId", "REVERIFY일 때만 보냅니다.");
        }
        boolean hasRules = scope.linkMappingIds() != null && !scope.linkMappingIds().isEmpty();
        boolean hasIssues = scope.issueIds() != null && !scope.issueIds().isEmpty();
        switch (scope.kind()) {
            case "ALL" -> {
                if (hasRules || hasIssues) {
                    throw BusinessException.invalidField("scope", "ALL에는 ID 목록을 보내지 않습니다.");
                }
            }
            case "LINK_MAPPINGS" -> {
                if (!hasRules) {
                    throw BusinessException.invalidField(
                            "scope.linkMappingIds", "LINK_MAPPINGS에는 규칙 ID가 하나 이상 필요합니다.");
                }
                if (hasIssues) {
                    throw BusinessException.invalidField(
                            "scope.issueIds", "LINK_MAPPINGS에는 문제 ID를 보내지 않습니다.");
                }
            }
            default -> {
                if (!reverify) {
                    throw BusinessException.invalidField("scope.kind", "ISSUES는 REVERIFY에서만 씁니다.");
                }
                if (!hasIssues) {
                    throw BusinessException.invalidField(
                            "scope.issueIds", "ISSUES에는 문제 ID가 하나 이상 필요합니다.");
                }
                if (hasRules) {
                    throw BusinessException.invalidField(
                            "scope.linkMappingIds", "ISSUES에는 규칙 ID를 보내지 않습니다.");
                }
            }
        }
    }

    /** 재검증이 비교할 이전 검사. 성공한 규칙 검사(MANUAL·SYNC·REVERIFY)여야 한다. */
    private void requireComparable(long comparedToRunId) {
        RunState previous =
                checkRunRepository
                        .findState(comparedToRunId)
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "comparedToRunId",
                                                "없는 검사 실행입니다.",
                                                Map.of("comparedToRunId", comparedToRunId)));
        if ("CANDIDATE".equals(previous.triggerKind())) {
            throw rejected(
                    "comparedToRunId",
                    "승인 전 후보 시험과는 비교할 수 없습니다.",
                    Map.of("triggerKind", previous.triggerKind()));
        }
        if (!"SUCCEEDED".equals(previous.status())) {
            throw rejected(
                    "comparedToRunId", "성공한 검사와만 비교할 수 있습니다.", Map.of("status", previous.status()));
        }
    }

    /**
     * 요청 범위를 검사할 규칙 개정본 목록으로 바꿔 저장 형식으로 만든다. ALL도 접수 시점의 규칙 목록을 남겨, 실행기가 같은 규칙을 검사하고 같은 목록끼리만 합류하게
     * 한다.
     */
    private Map<String, Object> resolveScope(Scope scope) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("kind", scope.kind());
        switch (scope.kind()) {
            case "ALL" -> {
                List<Long> active =
                        linkMappingRepository
                                .findByStatusOrderById(LinkMappingStatus.ACTIVE)
                                .stream()
                                .map(LinkMapping::getId)
                                .toList();
                if (active.isEmpty()) {
                    throw rejected("scope", "반영 중인 연결 규칙이 없습니다.", Map.of());
                }
                stored.put("linkMappingIds", active);
            }
            case "LINK_MAPPINGS" -> {
                List<Long> requested = List.copyOf(new TreeSet<>(scope.linkMappingIds()));
                stored.put("linkMappingIds", requireActive(requested, "scope.linkMappingIds"));
            }
            default -> {
                List<Long> issueIds = List.copyOf(new TreeSet<>(scope.issueIds()));
                List<Issue> issues =
                        issueRepository.findAllById(issueIds).stream()
                                .sorted(java.util.Comparator.comparing(Issue::getId))
                                .toList();
                List<Long> found = issues.stream().map(Issue::getId).toList();
                List<Long> missing = issueIds.stream().filter(id -> !found.contains(id)).toList();
                if (!missing.isEmpty()) {
                    throw rejected("scope.issueIds", "없는 문제입니다.", Map.of("issueIds", missing));
                }
                List<Long> resolved =
                        issues.stream()
                                .filter(issue -> issue.getStatus() == IssueStatus.RESOLVED)
                                .map(Issue::getId)
                                .toList();
                if (!resolved.isEmpty()) {
                    throw rejected(
                            "scope.issueIds", "해결된 문제는 재검증하지 않습니다.", Map.of("issueIds", resolved));
                }
                List<Long> fromProposals =
                        issues.stream()
                                .filter(issue -> issue.getLinkMappingId() == null)
                                .map(Issue::getId)
                                .toList();
                if (!fromProposals.isEmpty()) {
                    throw rejected(
                            "scope.issueIds",
                            "승인 전 후보에서 나온 문제는 재검증 대상이 아닙니다.",
                            Map.of("issueIds", fromProposals));
                }
                stored.put("linkMappingIds", currentRevisions(issues));
                stored.put("issueIds", issueIds);
            }
        }
        return stored;
    }

    /**
     * 문제를 재검증할 규칙 개정본: 문제가 나온 개정본의 규칙 코드에서 지금 반영 중인(ACTIVE) 개정본(Jira 246).
     *
     * <p>문제는 규칙 코드로 묶인다({@code IssueDedupeKey}). 그래서 규칙을 개정해도 같은 행의 문제는 처음 발견한 옛 개정본을 가리킨 채 열려 있다.
     * 옛 개정본으로만 재검증하면 개정 뒤에는 접수되지 않아 그 문제를 해결로 바꿀 길이 없다. 규칙이 틀려 생긴 문제(오탐)도 규칙을 고친 새 개정본으로 재검증해 닫는다.
     * 같은 코드의 ACTIVE 개정본은 하나뿐이라 실행기는 문제의 규칙 코드로 검사할 개정본을 찾는다.
     *
     * <p>규칙 코드에 반영 중인 개정본이 없으면(폐기만 됨) 422다.
     */
    private List<Long> currentRevisions(List<Issue> issues) {
        Map<Long, String> codeByRevision =
                linkMappingRepository
                        .findAllById(
                                issues.stream()
                                        .map(Issue::getLinkMappingId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(LinkMapping::getId, LinkMapping::getCode));
        Map<String, Optional<Long>> activeByCode = new LinkedHashMap<>();
        TreeSet<Long> revisions = new TreeSet<>();
        List<Long> withoutActive = new ArrayList<>();
        TreeSet<String> retiredCodes = new TreeSet<>();
        for (Issue issue : issues) {
            String code = codeByRevision.get(issue.getLinkMappingId());
            Optional<Long> active =
                    activeByCode.computeIfAbsent(
                            code,
                            c ->
                                    linkMappingRepository
                                            .findByCodeAndStatus(c, LinkMappingStatus.ACTIVE)
                                            .map(LinkMapping::getId));
            if (active.isPresent()) {
                revisions.add(active.get());
            } else {
                withoutActive.add(issue.getId());
                retiredCodes.add(code);
            }
        }
        if (!withoutActive.isEmpty()) {
            throw rejected(
                    "scope.issueIds",
                    "반영 중인 개정본이 없는 규칙에서 나온 문제입니다.",
                    Map.of(
                            "issueIds",
                            withoutActive,
                            "linkMappingCodes",
                            List.copyOf(retiredCodes)));
        }
        return List.copyOf(revisions);
    }

    private List<Long> requireActive(List<Long> ruleIds, String field) {
        Set<Long> active =
                linkMappingRepository
                        .findByIdInAndStatus(ruleIds, LinkMappingStatus.ACTIVE)
                        .stream()
                        .map(LinkMapping::getId)
                        .collect(Collectors.toSet());
        List<Long> inactive = ruleIds.stream().filter(id -> !active.contains(id)).toList();
        if (!inactive.isEmpty()) {
            throw rejected(field, "없거나 반영 중이 아닌 규칙입니다.", Map.of("linkMappingIds", inactive));
        }
        return ruleIds;
    }

    private CheckRunRequestResponse response(long runId, boolean joined) {
        return new CheckRunRequestResponse(
                checkRunRepository.findById(runId).orElseThrow(), joined, false);
    }

    private static BusinessException rejected(
            String field, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("reason", reason);
        details.putAll(extra);
        return new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION, reason, details);
    }

    /**
     * 검사 실행의 work_key: 종류, 비교 실행, 범위·수집 기준·연결 상태의 해시. 재검증은 완료 때 실제로 읽은 기준으로 다시 만든다.
     *
     * @param linkState 범위 규칙들의 관계 유형 연결 상태({@link CheckRunRepository#linkState}, 367). 수동 연결을 만들거나
     *     끄면 수집 실행이 없어도 바뀌어, 이전 검사를 재사용하거나 그 검사에 합류하지 않는다
     */
    static String workKey(
            String triggerKind,
            Long comparedToRunId,
            String scopeJson,
            String basisJson,
            String linkState) {
        return triggerKind
                + ":"
                + (comparedToRunId == null ? "-" : comparedToRunId)
                + ":"
                + sha256(scopeJson + "|" + basisJson + "|" + linkState);
    }

    /** 규칙 개정본들의 관계 유형(연결 상태를 읽을 범위, 367). 없는 ID는 무시한다. */
    static Set<Long> linkTypeIds(LinkMappingRepository rules, Collection<Long> ruleIds) {
        return rules.findAllById(ruleIds).stream()
                .map(LinkMapping::getLinkTypeId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** work_key의 해시. 수집 뒤 검사(SyncCheckRequester)도 같은 방식으로 만든다. */
    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
        }
    }
}

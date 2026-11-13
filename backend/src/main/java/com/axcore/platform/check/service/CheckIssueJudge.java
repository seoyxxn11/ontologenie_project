package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.LinkCheckPending;
import com.axcore.platform.check.domain.LinkCheckPending.CloseReason;
import com.axcore.platform.check.domain.LinkCheckPending.Status;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.repository.CheckJudgmentReader;
import com.axcore.platform.check.repository.CheckJudgmentReader.ResourceState;
import com.axcore.platform.check.repository.LinkCheckPendingRepository;
import com.axcore.platform.issue.domain.IssueDedupeKey;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.IssueRegistration;
import com.axcore.platform.issue.domain.NewIssue;
import com.axcore.platform.issue.service.IssueRegistrationService;
import com.axcore.platform.ontology.domain.LinkMapping;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SYNC·수동 검사의 판정 결과를 issue와 확인 대기로 보낸다(S15P31C106-191-3, 결정 문서 2026-11-05 8장, 191 설계안 4장).
 *
 * <p><b>규칙 분류</b>: 출발·도착 유형에 활성 매핑된 원천 테이블이 모두 같은 원천이고 모두 CDC면 <i>같은 원천</i>, 그 밖(다른 원천,
 * FULL_COMPARE 표가 섞임)은 <i>원천 사이</i>다.
 *
 * <ul>
 *   <li>같은 원천: 미연결·중복·위반을 바로 issue로 등록한다. 원천 트랜잭션 하나가 플랫폼 트랜잭션 하나로 반영되므로 도착 순서 때문에 생긴 미연결이 아니다.
 *   <li>원천 사이: 중복·위반은 바로 issue(도착 순서와 무관한 판정, 2026-11-10 결정). 미연결은 확인 대기를 거친다.
 * </ul>
 *
 * <p><b>건너뛰기(규칙 단위, run 스냅샷)</b>: 규칙의 원천 테이블 중 CDC 표가 전체 적재 전이거나 전체 읽기가 대기·실행 중이거나 그 원천에 복구가 필요하거나,
 * FULL_COMPARE 표가 한 번도 수집되지 않았으면 판정하지 않는다. 기록은 감사 결과에만 남긴다(2026-11-10 결정).
 *
 * <p><b>미연결 보류</b>: 도착 테이블에 도착 유형의 열린 수집 실패가 있고, 그것이 키 실패(객체를 만들지 못한 행)이거나 비교 조건의 도착 속성 값 실패면 그 규칙의
 * 미연결은 issue·확인 대기로 보내지 않는다(설계 검토 R5). 어느 출발 행이 그 도착 행과 맞았을지 알 수 없어 규칙 단위로 보류한다.
 *
 * <p><b>확인 대기</b>(원천 사이 미연결, 검사마다): 새 미연결은 WAITING으로 넣고, 이번에 미연결이 아닌 WAITING은 일시 불일치로 닫는다(출발 객체를
 * 지웠으면 SOURCE_DELETED, 아니면 LINKED). 아직 미연결인 WAITING은 아래를 모두 만족하면 issue로 올린다.
 *
 * <ul>
 *   <li>규칙의 CDC 원천이 모두 지금 정상(healthy_since가 있음)이다. 대기 시간은 max(처음 본 시각, 그 원천들의 healthy_since)부터 센다.
 *   <li>규칙의 FULL_COMPARE 표는 healthy_since가 없으므로, 처음 본 뒤에 끝난 성공 수집이 있어야 한다(2026-11-10 결정: 마지막 성공 전체
 *       읽기 기준).
 *   <li>그 시작부터 대기 시간({@code axcore.check.pending-wait}, 기본 10분)이 지났다.
 * </ul>
 *
 * <p>반영 중이 아닌 규칙 개정본의 WAITING은 RULE_RETIRED로 닫는다. issue 등록과 확인 대기 변경은 검사 결과 저장과 같은 트랜잭션이다.
 */
@Component
public class CheckIssueJudge {

    private final CheckJudgmentReader reader;
    private final LinkCheckPendingRepository pendings;
    private final IssueRegistrationService issues;
    private final Duration pendingWait;

    public CheckIssueJudge(
            CheckJudgmentReader reader,
            LinkCheckPendingRepository pendings,
            IssueRegistrationService issues,
            @Value("${axcore.check.pending-wait:10m}") Duration pendingWait) {
        this.reader = reader;
        this.pendings = pendings;
        this.issues = issues;
        this.pendingWait = pendingWait;
    }

    /** run 스냅샷에서 규칙 하나의 분류·건너뛰기·보류를 정한다. */
    public RulePlan plan(LinkMappingRule rule, LinkMapping mapping) {
        Set<Long> resources = new TreeSet<>(rule.sourceResourceIds());
        resources.addAll(rule.targetResourceIds());
        List<ResourceState> states = reader.resourceStates(resources);
        String skip = skipReason(states);
        boolean sameSource =
                !states.isEmpty()
                        && states.stream().allMatch(ResourceState::cdc)
                        && states.stream().map(ResourceState::dataSourceId).distinct().count() == 1;
        Set<String> targetProperties =
                rule.input().conditions().stream()
                        .map(c -> c.targetProperty())
                        .collect(Collectors.toSet());
        boolean hold =
                reader.openLoadFailures(rule.targetResourceIds()).stream()
                        .anyMatch(
                                f ->
                                        f.objectTypeId() == rule.targetType().id()
                                                && (f.keyFailure()
                                                        || targetProperties.contains(
                                                                f.property())));
        return new RulePlan(
                mapping.getCode(),
                mapping.getRevision(),
                List.copyOf(resources),
                !sameSource,
                hold,
                skip);
    }

    private static String skipReason(List<ResourceState> states) {
        for (ResourceState state : states) {
            if (state.cdc() && !state.loaded()) {
                return "전체 적재 전인 CDC 표가 있습니다(원천 테이블 %d).".formatted(state.resourceId());
            }
            if (state.cdc() && state.fullPending()) {
                return "전체 읽기가 대기·실행 중인 표가 있습니다(원천 테이블 %d).".formatted(state.resourceId());
            }
            if (state.cdc() && state.recoveryReason() != null) {
                return "복구가 필요한 원천이 있습니다(원천 %d, %s)."
                        .formatted(state.dataSourceId(), state.recoveryReason());
            }
            if (!state.cdc() && state.lastReadAt() == null) {
                return "아직 수집하지 않은 표가 있습니다(원천 테이블 %d).".formatted(state.resourceId());
            }
        }
        return null;
    }

    /** complete 트랜잭션에서 규칙 하나의 판정 결과를 issue·확인 대기로 보낸다. */
    public Totals apply(long checkRunId, RulePlan plan, CheckEvaluation evaluation, Instant now) {
        long ruleId = evaluation.rule().target().linkMappingId();
        Totals totals = new Totals();
        Map<Long, RowResult> unmatched = new LinkedHashMap<>();
        for (RowResult row : evaluation.rows()) {
            if (row.outcome() == CheckOutcome.MATCHED) {
                continue;
            }
            if (row.outcome() == CheckOutcome.UNMATCHED) {
                if (plan.holdUnmatched()) {
                    totals.heldUnmatched++;
                    continue;
                }
                if (plan.crossSource()) {
                    unmatched.put(row.source().objectInstanceId(), row);
                    continue;
                }
            }
            totals.count(register(checkRunId, plan, evaluation, row, Map.of()));
        }
        if (plan.crossSource() && !plan.holdUnmatched()) {
            pending(checkRunId, plan, evaluation, ruleId, unmatched, now, totals);
        }
        return totals;
    }

    private void pending(
            long checkRunId,
            RulePlan plan,
            CheckEvaluation evaluation,
            long ruleId,
            Map<Long, RowResult> unmatched,
            Instant now,
            Totals totals) {
        Map<Long, LinkCheckPending> waiting =
                pendings.findByLinkMappingIdAndStatusOrderById(ruleId, Status.WAITING).stream()
                        .collect(
                                Collectors.toMap(
                                        LinkCheckPending::getSourceObjectId,
                                        Function.identity(),
                                        (a, b) -> a,
                                        LinkedHashMap::new));

        List<LinkCheckPending> resolved =
                waiting.values().stream()
                        .filter(p -> !unmatched.containsKey(p.getSourceObjectId()))
                        .toList();
        Set<Long> deleted =
                reader.deletedObjectIds(
                        resolved.stream().map(LinkCheckPending::getSourceObjectId).toList());
        for (LinkCheckPending p : resolved) {
            p.closeTransient(
                    deleted.contains(p.getSourceObjectId())
                            ? CloseReason.SOURCE_DELETED
                            : CloseReason.LINKED,
                    checkRunId,
                    now);
            totals.pendingClosed++;
        }

        // 원천 상태는 판정 스냅샷이 아니라 지금(승격 시점) 기준이다.
        List<ResourceState> states = reader.resourceStates(plan.resourceIds());
        for (Map.Entry<Long, RowResult> entry : unmatched.entrySet()) {
            LinkCheckPending p = waiting.get(entry.getKey());
            if (p == null) {
                pendings.save(LinkCheckPending.waiting(ruleId, entry.getKey(), checkRunId, now));
                totals.pendingAdded++;
                continue;
            }
            p.seen(checkRunId);
            if (!promotable(p, states, now)) {
                continue;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("pendingId", p.getId());
            extra.put("pendingFirstSeenAt", p.getFirstSeenAt().toString());
            IssueRegistration registration =
                    register(checkRunId, plan, evaluation, entry.getValue(), extra);
            totals.count(registration);
            p.promote(registration.issueId(), checkRunId, now);
            totals.promoted++;
        }
    }

    private boolean promotable(LinkCheckPending p, List<ResourceState> states, Instant now) {
        Instant start = p.getFirstSeenAt();
        for (ResourceState state : states) {
            if (state.cdc()) {
                if (state.healthySince() == null || state.recoveryReason() != null) {
                    return false;
                }
                if (state.healthySince().isAfter(start)) {
                    start = state.healthySince();
                }
            } else if (state.lastReadAt() == null
                    || !state.lastReadAt().isAfter(p.getFirstSeenAt())) {
                return false;
            }
        }
        return !start.plus(pendingWait).isAfter(now);
    }

    /** 반영 중이 아닌 규칙 개정본의 대기를 닫는다. 닫은 수를 돌려준다. */
    public int closeRetiredRules(long checkRunId, Instant now) {
        List<LinkCheckPending> retired = pendings.findWaitingOfInactiveRules();
        retired.forEach(p -> p.closeTransient(CloseReason.RULE_RETIRED, checkRunId, now));
        return retired.size();
    }

    private IssueRegistration register(
            long checkRunId,
            RulePlan plan,
            CheckEvaluation evaluation,
            RowResult row,
            Map<String, Object> extra) {
        IssueKind kind = IssueKind.valueOf(row.outcome().name());
        long objectId = row.source().objectInstanceId();
        Map<String, String> transformed = new LinkedHashMap<>();
        row.transformedValues()
                .forEach((seq, value) -> transformed.put(String.valueOf(seq), value));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleCode", plan.ruleCode());
        evidence.put("revision", plan.revision());
        evidence.put("reason", row.reason());
        evidence.put("transformedValues", transformed);
        evidence.put(
                "targetObjectIds",
                row.targets().stream()
                        .map(MatchedTarget::row)
                        .map(CheckRow::objectInstanceId)
                        .toList());
        evidence.put("crossSource", plan.crossSource());
        evidence.put("causeConfirmed", false);
        evidence.putAll(extra);
        return issues.register(
                new NewIssue(
                        kind,
                        IssueDedupeKey.forRule(kind, plan.ruleCode(), objectId),
                        checkRunId,
                        row.reason(),
                        evaluation.rule().target().linkMappingId(),
                        null,
                        objectId,
                        CheckRowText.rawValue(row, evaluation.rule().conditions()),
                        CheckRowText.transformedValue(row, evaluation.rule().conditions()),
                        evidence));
    }

    /**
     * 규칙 하나의 판정 계획.
     *
     * @param resourceIds 규칙이 읽는 원천 테이블(출발·도착)
     * @param crossSource 원천 사이 규칙이면 true(미연결은 확인 대기)
     * @param holdUnmatched 도착 쪽 열린 수집 실패로 미연결을 보류하면 true
     * @param skipReason 판정하지 않을 이유. 판정하면 null
     */
    public record RulePlan(
            String ruleCode,
            int revision,
            List<Long> resourceIds,
            boolean crossSource,
            boolean holdUnmatched,
            String skipReason) {}

    /** 검사 하나의 판정 집계. 감사 결과 줄에 남긴다. */
    public static final class Totals {
        private int issuesCreated;
        private int issuesExisting;
        private int heldUnmatched;
        private int pendingAdded;
        private int pendingClosed;
        private int promoted;

        void count(IssueRegistration registration) {
            if (registration.created()) {
                issuesCreated++;
            } else {
                issuesExisting++;
            }
        }

        public void add(Totals other) {
            issuesCreated += other.issuesCreated;
            issuesExisting += other.issuesExisting;
            heldUnmatched += other.heldUnmatched;
            pendingAdded += other.pendingAdded;
            pendingClosed += other.pendingClosed;
            promoted += other.promoted;
        }

        public void addRetiredClosed(int count) {
            pendingClosed += count;
        }

        public Map<String, Object> toResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("issuesCreated", issuesCreated);
            result.put("issuesExisting", issuesExisting);
            result.put("heldUnmatched", heldUnmatched);
            result.put("pendingAdded", pendingAdded);
            result.put("pendingClosed", pendingClosed);
            result.put("pendingPromoted", promoted);
            return result;
        }

        public int issuesCreated() {
            return issuesCreated;
        }

        public int promoted() {
            return promoted;
        }

        public int heldUnmatched() {
            return heldUnmatched;
        }

        public int pendingAdded() {
            return pendingAdded;
        }

        public int pendingClosed() {
            return pendingClosed;
        }
    }
}

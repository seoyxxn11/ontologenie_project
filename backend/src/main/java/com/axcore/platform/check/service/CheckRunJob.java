package com.axcore.platform.check.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.IssueReverification;
import com.axcore.platform.check.domain.IssueReverification.Verdict;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.repository.CheckObjectReader;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.repository.CheckRunRepository.ReverificationScope;
import com.axcore.platform.check.repository.CheckRunRepository.RunToExecute;
import com.axcore.platform.check.service.CheckIssueJudge.RulePlan;
import com.axcore.platform.check.service.CheckIssueJudge.Totals;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 실행(check_run)을 배치에서 돌린다(S15P31C106-191, FN-CHK-01·02). 수동 검사(MANUAL)·재검증(REVERIFY)·수집 뒤
 * 검사(SYNC)가 같은 경로를 쓴다. 승인 전 후보 시험(CANDIDATE)은 원천을 읽어야 해서 가져가지 않는다({@link #claimCondition}).
 *
 * <p><b>run</b>(읽기 전용 REPEATABLE READ 트랜잭션 하나): 규칙과 객체를 한 스냅샷에서 읽는다(결정 문서 2026-11-05 8장).
 *
 * <ol>
 *   <li>접수 때 정해 둔 규칙 개정본 목록({@code scope.linkMappingIds})을 읽는다.
 *   <li>규칙마다 190 변환기로 판정 엔진 입력을 만들고, 출발·도착 유형의 활성 객체를 읽어 행 조건을 적용한 뒤 판정한다. 검사는 원천이 아니라 수집한 객체를
 *       본다(9.1). 엔진은 조건의 속성 이름({@code 유형코드.속성코드})으로 값을 찾으므로 그 이름으로 넣는다.
 *   <li>규칙 하나를 판정할 수 없으면(반영 중이 아님, 조건·변환·행 조건을 읽을 수 없음, 데이터 기준에 자원이 빠짐, 도착 값 변환 실패) 그 규칙만 건너뛰고 감사
 *       결과에 이유를 남긴다. 모든 규칙을 건너뛰면 검사는 FAILED다. 단 SYNC는 결과 없는 성공으로 끝낸다(주기마다 실패가 쌓이지 않게).
 * </ol>
 *
 * <p><b>complete</b>(한 트랜잭션): 검사 행을 잠가 RUNNING인지 보고, 판정한 규칙마다 {@code check_result} 한 행(건수, 같은 규칙
 * 개정본의 직전 결과, 정상이 아닌 행 표본 최대 {@value #SAMPLE_LIMIT}개)을 저장한 뒤 SUCCEEDED로 바꾼다.
 *
 * <p><b>판정(191-3, SYNC·MANUAL만)</b>: run에서 {@link CheckIssueJudge#plan}으로 규칙을 분류하고, 수집이 준비되지 않은 규칙은
 * 건너뛴다. complete에서 결과를 저장한 같은 트랜잭션에 issue 등록·확인 대기를 처리한다({@link CheckIssueJudge#apply}).
 * 재검증(REVERIFY)은 issue를 만들지 않는다(문제별 검증 기록·비교는 134·135).
 *
 * <p><b>문제별 재검증(246)</b>: 범위가 ISSUES인 재검증이면 run에서 문제마다 판정 결과({@link IssueReverification})를 만든다. 문제의
 * 규칙 코드로 판정한 개정본을 찾고, 그 판정에서 문제 객체의 출발 행 결과를 본다. complete에서 결과 저장과 같은 트랜잭션에 {@link
 * IssueReverificationRecorder}(135 구현)에 넘긴다. 구현이 없으면 넘기지 않는다.
 *
 * <p>MVP라 규칙마다 양 끝 유형의 활성 객체를 모두 읽는다(181과 같은 한계).
 */
@Slf4j
@Component
@Profile("batch")
public class CheckRunJob implements QueuedJobHandler<CheckRunJob.Outcome> {

    static final int SAMPLE_LIMIT = 20;
    static final String NOT_ACTIVE = "반영 중인 규칙이 아닙니다.";
    static final String MANUAL_LINK = "관리자가 수동으로 연결함";

    private final CheckRunRepository runs;
    private final CheckResultRepository results;
    private final LinkMappingRepository linkMappings;
    private final LinkMappingRuleLoader ruleLoader;
    private final CheckObjectReader objects;
    private final RuleObjectEvaluator evaluator;
    private final CheckIssueJudge judge;
    private final IssueRepository issues;
    private final ObjectProvider<IssueReverificationRecorder> reverificationRecorders;
    private final TransactionTemplate snapshot;
    private final Clock clock;
    private final JsonMapper json;

    public CheckRunJob(
            CheckRunRepository runs,
            CheckResultRepository results,
            LinkMappingRepository linkMappings,
            LinkMappingRuleLoader ruleLoader,
            CheckObjectReader objects,
            RuleObjectEvaluator evaluator,
            CheckIssueJudge judge,
            IssueRepository issues,
            ObjectProvider<IssueReverificationRecorder> reverificationRecorders,
            PlatformTransactionManager transactionManager,
            Clock clock,
            JsonMapper json) {
        this.runs = runs;
        this.results = results;
        this.linkMappings = linkMappings;
        this.ruleLoader = ruleLoader;
        this.objects = objects;
        this.evaluator = evaluator;
        this.judge = judge;
        this.issues = issues;
        this.reverificationRecorders = reverificationRecorders;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = clock;
        this.json = json;
    }

    @Override
    public QueuedJobType type() {
        return QueuedJobType.CHECK_RUN;
    }

    @Override
    public String claimCondition() {
        return "trigger_kind <> 'CANDIDATE'";
    }

    @Override
    public Outcome run(long jobId) {
        return snapshot.execute(
                status -> {
                    // 읽기만 하므로 커밋하지 않는다. 규칙 하나를 건너뛸 때 변환기(@Transactional) 안에서 난 예외가 이 트랜잭션을
                    // rollback-only로 표시해도, 직접 되돌리기로 끝내면 UnexpectedRollbackException이 나지 않는다.
                    status.setRollbackOnly();
                    return evaluate(jobId);
                });
    }

    private Outcome evaluate(long jobId) {
        RunToExecute run =
                runs.findToExecute(jobId)
                        .orElseThrow(() -> new IllegalStateException("검사 실행이 없습니다: " + jobId));
        if ("CANDIDATE".equals(run.triggerKind())) {
            throw new QueuedJobFailure("후보 시험은 검사 실행기가 처리하지 않습니다.");
        }
        if (run.linkMappingIds().isEmpty()) {
            throw new QueuedJobFailure("검사할 규칙이 없습니다.");
        }
        boolean reverification = "REVERIFY".equals(run.triggerKind());
        CheckDataBasis basis = reverification ? latestDataBasis(run.dataBasis()) : run.dataBasis();
        Map<Long, LinkMapping> active =
                linkMappings
                        .findByIdInAndStatus(run.linkMappingIds(), LinkMappingStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toMap(LinkMapping::getId, Function.identity()));
        boolean judges = judges(run.triggerKind());

        List<RuleEvaluated> evaluated = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        for (long linkMappingId : run.linkMappingIds()) {
            if (!active.containsKey(linkMappingId)) {
                skipped.add(new SkippedRule(linkMappingId, NOT_ACTIVE));
                continue;
            }
            try {
                LinkMappingRule rule = ruleLoader.load(linkMappingId, basis);
                RulePlan plan = judges ? judge.plan(rule, active.get(linkMappingId)) : null;
                if (plan != null && plan.skipReason() != null) {
                    skipped.add(new SkippedRule(linkMappingId, plan.skipReason()));
                    continue;
                }
                evaluated.add(evaluateRule(rule, active.get(linkMappingId).getLinkTypeId(), plan));
            } catch (RuleObjectEvaluator.RuleNotEvaluable
                    | IllegalArgumentException
                    | IllegalStateException e) {
                // 규칙 하나의 입력·데이터 문제다. DB 오류(DataAccessException)는 여기서 잡지 않아 검사 전체가 실패한다.
                String reason =
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn(
                        "검사 규칙 건너뜀 checkRunId={} linkMappingId={}: {}",
                        jobId,
                        linkMappingId,
                        reason);
                skipped.add(new SkippedRule(linkMappingId, reason));
            }
        }
        // 수집 뒤 검사(SYNC)는 적재·복구 중이면 모든 규칙을 건너뛸 수 있다. 실패로 두면 다시 쓰이지 않아 주기마다 쌓이므로 결과 없는 성공으로
        // 끝내고 이유는 감사 결과에 남긴다. 데이터 기준이 바뀌면(적재·복구가 끝나면) 새 SYNC가 들어온다. 사람이 요청한 검사는 실패로 알린다.
        if (evaluated.isEmpty() && !"SYNC".equals(run.triggerKind())) {
            throw new QueuedJobFailure(
                    "검사할 수 있는 규칙이 없습니다. 건너뛴 규칙: "
                            + skipped.stream()
                                    .map(s -> s.linkMappingId() + "(" + s.reason() + ")")
                                    .collect(Collectors.joining(", ")));
        }
        List<IssueReverification> reverifications =
                reverification && !run.issueIds().isEmpty()
                        ? reverify(run.issueIds(), active, evaluated)
                        : List.of();
        // 재검증은 완료 때 실제로 읽은 기준으로 work_key를 다시 만든다. 연결 상태도 같은 읽기 스냅샷에서 읽는다(367).
        String linkState =
                reverification
                        ? runs.linkState(
                                CheckRunRequestService.linkTypeIds(
                                        linkMappings, run.linkMappingIds()))
                        : null;
        return new Outcome(
                run.triggerKind(),
                List.copyOf(evaluated),
                List.copyOf(skipped),
                new Totals(),
                reverifications,
                run.reverificationScope(),
                basis,
                linkState);
    }

    private CheckDataBasis latestDataBasis(CheckDataBasis requestedBasis) {
        List<Long> resourceIds =
                requestedBasis.resources().stream()
                        .map(ResourceCheckpoint::sourceResourceId)
                        .toList();
        return new CheckDataBasis(runs.loadCheckpoints(resourceIds));
    }

    /**
     * 문제마다 재검증 판정을 만든다(246). 문제는 규칙 코드로 묶이므로 문제가 나온 개정본의 코드로, 이 검사가 판정한 같은 코드의 개정본을 찾는다(접수 때 같은 코드의
     * 반영 중인 개정본을 범위에 넣었다).
     */
    private List<IssueReverification> reverify(
            List<Long> issueIds, Map<Long, LinkMapping> active, List<RuleEvaluated> evaluated) {
        Map<String, Long> activeByCode = new LinkedHashMap<>();
        active.values().forEach(rule -> activeByCode.put(rule.getCode(), rule.getId()));
        Map<Long, RuleEvaluated> evaluationByRule = new LinkedHashMap<>();
        for (RuleEvaluated rule : evaluated) {
            evaluationByRule.put(rule.evaluation().rule().target().linkMappingId(), rule);
        }
        List<Issue> targets =
                issues.findAllById(issueIds).stream()
                        .sorted(Comparator.comparing(Issue::getId))
                        .toList();
        Map<Long, String> codeByRevision =
                linkMappings
                        .findAllById(
                                targets.stream()
                                        .map(Issue::getLinkMappingId)
                                        .filter(Objects::nonNull)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(LinkMapping::getId, LinkMapping::getCode));

        List<IssueReverification> verdicts = new ArrayList<>();
        for (Issue issue : targets) {
            Long revision = activeByCode.get(codeByRevision.get(issue.getLinkMappingId()));
            RuleEvaluated ruleResult = revision == null ? null : evaluationByRule.get(revision);
            CheckEvaluation evaluation = ruleResult == null ? null : ruleResult.evaluation();
            if (issue.getObjectInstanceId() == null) {
                verdicts.add(notEvaluated(issue, revision, "문제에 객체가 없습니다."));
            } else if (evaluation == null) {
                verdicts.add(notEvaluated(issue, revision, "같은 코드의 규칙을 판정하지 못했습니다."));
            } else {
                Optional<RowResult> row =
                        evaluation.rows().stream()
                                .filter(
                                        r ->
                                                issue.getObjectInstanceId()
                                                        .equals(r.source().objectInstanceId()))
                                .findFirst();
                verdicts.add(
                        row.map(r -> judged(issue, revision, ruleResult, r))
                                .orElseGet(
                                        () ->
                                                notEvaluated(
                                                        issue,
                                                        revision,
                                                        "객체가 판정 대상에 없습니다(원천 삭제·행 조건 밖).")));
            }
        }
        return List.copyOf(verdicts);
    }

    private static IssueReverification judged(
            Issue issue, long revision, RuleEvaluated ruleResult, RowResult row) {
        List<CheckCondition> conditions = ruleResult.evaluation().rule().conditions();
        List<Long> manual = ruleResult.manualTargets().get(row.source().objectInstanceId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rawValue", CheckRowText.rawValue(row, conditions));
        detail.put("transformedValue", CheckRowText.transformedValue(row, conditions));
        detail.put("targetFound", !row.targets().isEmpty() || manual != null);
        detail.put("classification", row.outcome().name());
        detail.put("reason", row.reason());
        if (row.outcome() == CheckOutcome.MATCHED) {
            // 수동 연결로 통과했으면 그 근거와 실제로 이은 대상을 남긴다(367). 대상이 규칙의 도착 행 밖이어도 남는다.
            detail.put("matchedBy", manual == null ? "RULE" : "MANUAL_LINK");
            if (manual != null) {
                detail.put("manualTargetObjectIds", manual);
            }
        }
        Verdict verdict = row.outcome() == CheckOutcome.MATCHED ? Verdict.PASSED : Verdict.FAILED;
        return new IssueReverification(issue.getId(), revision, verdict, detail, null);
    }

    private static IssueReverification notEvaluated(Issue issue, Long revision, String reason) {
        return new IssueReverification(
                issue.getId(), revision, Verdict.NOT_EVALUATED, Map.of(), reason);
    }

    /** issue를 만드는 검사인지. SYNC와 수동 검사(복구 뒤 안전 점검, 결정 8장)만이다. */
    private static boolean judges(String triggerKind) {
        return "SYNC".equals(triggerKind) || "MANUAL".equals(triggerKind);
    }

    private RuleEvaluated evaluateRule(LinkMappingRule rule, long linkTypeId, RulePlan plan) {
        // 연결 재계산과 같은 계산(388). 읽는 객체·행 조건·판정이 같다.
        RuleObjectEvaluator.Evaluated evaluated = evaluator.evaluate(rule);
        CheckEvaluation evaluation = evaluated.evaluation();
        List<CheckRow> targetRows = evaluated.targetRows();
        Map<Long, List<Long>> manualLinks = objects.manualLinkTargets(linkTypeId);
        Map<Long, List<Long>> manualTargets = new LinkedHashMap<>();
        if (!manualLinks.isEmpty()) {
            Map<Long, CheckRow> targetById = new HashMap<>();
            targetRows.forEach(row -> targetById.put(row.objectInstanceId(), row));
            List<RowResult> rows = new ArrayList<>(evaluation.rows().size());
            for (RowResult row : evaluation.rows()) {
                long source = row.source().objectInstanceId();
                List<Long> targets = manualLinks.get(source);
                if (row.outcome() != CheckOutcome.UNMATCHED || targets == null) {
                    rows.add(row);
                    continue;
                }
                rows.add(manuallyMatched(row, targets, targetById));
                manualTargets.put(source, targets);
            }
            evaluation =
                    new CheckEvaluation(evaluation.rule(), rows, evaluation.excludedTargetCount());
        }
        return new RuleEvaluated(evaluation, samples(evaluation), plan, Map.copyOf(manualTargets));
    }

    /**
     * 규칙으로는 미연결이지만 관리자가 수동으로 이은 출발을 정상으로 바꾼다(366). 수동 연결은 승인 당시 값이 그대로인 동안만 활성이다(값이 바뀌면 연결 재계산이
     * 끈다). 그래서 활성 수동 연결이 있으면 믿고, 그 행의 문제는 재검증에서 PASSED가 된다. 중복·조건 위반은 규칙이 가리킨 대상의 문제라 바꾸지 않는다.
     */
    private static RowResult manuallyMatched(
            RowResult row, List<Long> targetIds, Map<Long, CheckRow> targetById) {
        List<MatchedTarget> targets = new ArrayList<>();
        for (Long id : targetIds) {
            CheckRow target = targetById.get(id);
            if (target != null) {
                targets.add(new MatchedTarget(target, Map.of()));
            }
        }
        return new RowResult(
                row.source(), CheckOutcome.MATCHED, row.transformedValues(), targets, MANUAL_LINK);
    }

    /**
     * 정상이 아닌 행 표본. 062가 보여 주는 rawValue·transformedValue·classification·reason에 객체·원천 위치와 대응한 도착 객체를
     * 더한다. 조건이 여럿이면 값을 조건 순서대로 " / "로 잇는다.
     */
    private static List<Map<String, Object>> samples(CheckEvaluation evaluation) {
        List<CheckCondition> conditions = evaluation.rule().conditions();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (RowResult row : evaluation.rows()) {
            if (row.outcome() == CheckOutcome.MATCHED) {
                continue;
            }
            if (samples.size() == SAMPLE_LIMIT) {
                break;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("objectInstanceId", row.source().objectInstanceId());
            sample.put("sourceResourceId", row.source().sourceResourceId());
            sample.put("sourceKey", row.source().sourceKey());
            sample.put("classification", row.outcome().name());
            sample.put("rawValue", CheckRowText.rawValue(row, conditions));
            sample.put("transformedValue", CheckRowText.transformedValue(row, conditions));
            sample.put("reason", row.reason());
            sample.put(
                    "targetObjectIds",
                    row.targets().stream()
                            .map(MatchedTarget::row)
                            .map(CheckRow::objectInstanceId)
                            .toList());
            samples.add(sample);
        }
        return samples;
    }

    @Override
    public void complete(long jobId, Outcome outcome) {
        ReverificationScope scope = outcome.reverificationScope();
        if (scope != null) {
            runs.lockReverificationScope(scope);
        }
        if (!runs.lockIfRunning(jobId)) {
            throw new QueuedJobFailure("검사 실행이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
        Instant now = Instant.now(clock);
        for (RuleEvaluated rule : outcome.evaluated()) {
            long linkMappingId = rule.evaluation().rule().target().linkMappingId();
            Long previous =
                    results.findTopByLinkMappingIdOrderByIdDesc(linkMappingId)
                            .map(CheckResult::getId)
                            .orElse(null);
            results.save(CheckResult.of(jobId, previous, rule.evaluation(), rule.samples()));
            if (rule.plan() != null) {
                outcome.judgment().add(judge.apply(jobId, rule.plan(), rule.evaluation(), now));
            }
        }
        if (judges(outcome.triggerKind())) {
            outcome.judgment().addRetiredClosed(judge.closeRetiredRules(jobId, now));
        }
        if (scope == null) {
            runs.markSucceeded(jobId, now);
        } else {
            String basisJson = json.writeValueAsString(outcome.dataBasis());
            String workKey =
                    CheckRunRequestService.workKey(
                            outcome.triggerKind(),
                            scope.comparedToRunId(),
                            scope.scopeJson(),
                            basisJson,
                            outcome.linkState());
            runs.markReverificationSucceeded(jobId, now, workKey, basisJson);
        }
        // 문제별 판정은 실제로 읽은 데이터 기준을 저장한 뒤에 넘긴다. 문제를 확인중으로 되돌릴지 그 기준으로 판단한다(376). 같은 트랜잭션이라
        // 기록이 실패하면 결과·상태 저장도 함께 롤백된다.
        if (!outcome.reverifications().isEmpty()) {
            reverificationRecorders
                    .orderedStream()
                    .forEach(recorder -> recorder.record(jobId, outcome.reverifications(), now));
        }
    }

    @Override
    public Map<String, Object> auditResult(Outcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggerKind", outcome.triggerKind());
        result.put("checkedRuleCount", outcome.evaluated().size());
        result.put(
                "manualMatchedCount",
                outcome.evaluated().stream().mapToInt(RuleEvaluated::manualMatched).sum());
        result.put(
                "skippedRules",
                outcome.skipped().stream()
                        .map(
                                s ->
                                        Map.<String, Object>of(
                                                "linkMappingId", s.linkMappingId(),
                                                "reason", s.reason()))
                        .toList());
        if (judges(outcome.triggerKind())) {
            result.put("judgment", outcome.judgment().toResult());
        }
        if (!outcome.reverifications().isEmpty()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Verdict verdict : Verdict.values()) {
                counts.put(
                        verdict.name(),
                        outcome.reverifications().stream()
                                .filter(r -> r.verdict() == verdict)
                                .count());
            }
            result.put("reverification", counts);
        }
        return result;
    }

    /**
     * run이 판정한 결과. complete가 저장한다. judgment는 complete가 issue·확인 대기를 처리하며 채우는 집계로, 감사 결과 줄에 남긴다(작업
     * 실행기가 complete와 auditResult에 같은 객체를 넘긴다).
     */
    public record Outcome(
            String triggerKind,
            List<RuleEvaluated> evaluated,
            List<SkippedRule> skipped,
            Totals judgment,
            List<IssueReverification> reverifications,
            ReverificationScope reverificationScope,
            CheckDataBasis dataBasis,
            String linkState) {}

    /**
     * 규칙 하나의 판정과 저장할 표본. plan은 issue를 만드는 검사(SYNC·MANUAL)에만 있다. manualTargets는 규칙으로는 미연결이지만 활성 수동
     * 연결이 있어 정상으로 본 출발 → 그 수동 연결의 도착 객체다(366·367, 재검증 근거에 쓴다).
     */
    public record RuleEvaluated(
            CheckEvaluation evaluation,
            List<Map<String, Object>> samples,
            RulePlan plan,
            Map<Long, List<Long>> manualTargets) {

        /** 규칙으로는 미연결이지만 활성 수동 연결이 있어 정상으로 본 출발 수(366). */
        public int manualMatched() {
            return manualTargets.size();
        }
    }

    /** 판정하지 않은 규칙과 이유. 이유에는 원천 값을 넣지 않는다. */
    public record SkippedRule(long linkMappingId, String reason) {}
}

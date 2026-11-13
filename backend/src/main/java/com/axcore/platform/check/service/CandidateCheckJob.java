package com.axcore.platform.check.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.Plan;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.RowMapping;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.Side;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import com.axcore.platform.source.repository.SourceTableReader.TooManyRowsException;
import com.axcore.platform.source.service.SourceCredentialException;
import com.axcore.platform.source.service.SourceCredentialService;
import com.axcore.platform.source.service.SourceRowConverter;
import com.axcore.platform.source.service.SourceRowConverter.ConversionException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 승인 전 후보를 원천 행으로 시험한다. 원천 데이터 정책(384·400)을 지킨다.
 *
 * <ul>
 *   <li>금지 표·컬럼이 낀 후보는 원천을 읽지 않고 실패로 끝낸다. 이유(금지 표·컬럼 이름)는 error_message에 남는다.
 *   <li>행 조건(출발·도착 조건)에 민감 속성을 쓴 후보도 원천을 읽지 않고 실패로 끝낸다. 승인과 같은 기준이다.
 *   <li>민감 컬럼·민감 속성의 값은 표본에 저장하지 않는다. 건수와 분류(classification·reason)만 남기고, 비운 칸 이름을 표본의
 *       restrictedFields에 적는다. 비교값이 민감하면 rawValue·transformedValue를, 키 컬럼이 민감하면
 *       sourceKey·targetKey를 비운다.
 * </ul>
 */
@Slf4j
@Component
@Profile("batch")
public class CandidateCheckJob implements QueuedJobHandler<CandidateCheckJob.Evaluated> {

    static final String FORBIDDEN_MESSAGE = "원천 데이터 정책상 읽지 않는 표·컬럼이 있어 후보 시험을 건너뜁니다: ";
    static final String RESTRICTED_FILTER_MESSAGE = "민감 속성은 연결 규칙의 출발·도착 조건에 쓸 수 없어 후보 시험을 건너뜁니다: ";

    private final CandidateCheckPlanLoader plans;
    private final CheckRunRepository runs;
    private final CheckResultRepository results;
    private final DataSourceRepository sources;
    private final SourceCredentialService credentials;
    private final SourceTableReader tables;
    private final SourceRowConverter converter;
    private final RuleEvaluationEngine engine;
    private final SourceColumnPolicy policy;
    private final Clock clock;
    private final int maxRows;

    public CandidateCheckJob(
            CandidateCheckPlanLoader plans,
            CheckRunRepository runs,
            CheckResultRepository results,
            DataSourceRepository sources,
            SourceCredentialService credentials,
            SourceTableReader tables,
            SourceRowConverter converter,
            RuleEvaluationEngine engine,
            SourceColumnPolicy policy,
            Clock clock,
            @Value("${axcore.source-load.max-rows}") int maxRows) {
        this.plans = plans;
        this.policy = policy;
        this.runs = runs;
        this.results = results;
        this.sources = sources;
        this.credentials = credentials;
        this.tables = tables;
        this.converter = converter;
        this.engine = engine;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    @Override
    public QueuedJobType type() {
        return QueuedJobType.CHECK_RUN;
    }

    @Override
    public String claimCondition() {
        return "trigger_kind = 'CANDIDATE'";
    }

    @Override
    public Evaluated run(long jobId) {
        Plan plan = plans.load(jobId);
        requireReadable(plan);
        requireNormalFilters(plan);
        // 접수 시점의 수집 ID는 작업 기준을 남기는 메타데이터다. 원천은 지금 조회한 행으로 판정한다.
        Map<Long, List<SourceRow>> rows = readRows(jobId, plan);
        try {
            return new Evaluated(
                    engine.evaluate(
                            new CheckInput(
                                    plan.input(),
                                    toCheckRows(plan.source(), rows),
                                    toCheckRows(plan.target(), rows)),
                            plan.source().typeCode().equals(plan.target().typeCode())),
                    restrictedFields(plan));
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure("후보의 비교 조건을 원천 행에 적용할 수 없습니다.");
        }
    }

    /** 금지 표·컬럼이 하나라도 있으면 원천을 읽기 전에 멈춘다. 메시지에는 표·컬럼 이름만 넣고 값은 넣지 않는다. */
    private void requireReadable(Plan plan) {
        Set<String> denied = new LinkedHashSet<>();
        for (RowMapping mapping : mappings(plan)) {
            String resourceKey = mapping.resourceKey();
            if (policy.forbidsTable(resourceKey)) {
                denied.add(resourceKey + ".*");
                continue;
            }
            policy.forbiddenColumns(resourceKey, table(List.of(mapping)).columns())
                    .forEach(column -> denied.add(resourceKey + "." + column));
        }
        if (!denied.isEmpty()) {
            throw new QueuedJobFailure(FORBIDDEN_MESSAGE + String.join(", ", denied));
        }
    }

    /**
     * 표본에서 비울 칸. 조건의 출발·도착 속성 중 하나라도 민감하면 값과 원천 키를 모두 비우고 건수와 분류만 남긴다. 대응한 행이면 양쪽 비교값이 같아서, 값을 비워도
     * 출발·도착 키 쌍과 일반 쪽 값으로 민감 값을 알아낼 수 있다.
     */
    private Set<String> restrictedFields(Plan plan) {
        Set<String> fields = new LinkedHashSet<>();
        for (CheckCondition condition : plan.input().conditions()) {
            if (restrictedReference(plan.source(), condition.sourceProperty())
                    || restrictedReference(plan.target(), condition.targetProperty())) {
                return Set.of("rawValue", "transformedValue", "sourceKey", "targetKey");
            }
        }
        if (restrictedKey(plan.source())) {
            fields.add("sourceKey");
        }
        if (restrictedKey(plan.target())) {
            fields.add("targetKey");
        }
        return Set.copyOf(fields);
    }

    /**
     * 행 조건(출발·도착 조건)에 민감 속성이 있으면 원천을 읽기 전에 멈춘다. 값을 저장하지 않아도 건수와 원천 키로 "그 민감 값을 가진 행"을 알아낼 수 있다.
     * 승인(LinkRuleApplier)과 같은 기준이다.
     */
    private void requireNormalFilters(Plan plan) {
        Set<String> denied = new LinkedHashSet<>();
        for (Side side : List.of(plan.source(), plan.target())) {
            String code = side.filter().propertyCode();
            if (code != null && restricted(side, code)) {
                denied.add(side.typeCode() + "." + code);
            }
        }
        if (!denied.isEmpty()) {
            throw new QueuedJobFailure(RESTRICTED_FILTER_MESSAGE + String.join(", ", denied));
        }
    }

    /** reference는 "유형코드.속성코드"다. */
    private boolean restrictedReference(Side side, String reference) {
        return restricted(side, reference.substring(side.typeCode().length() + 1));
    }

    /** 정의·후보가 민감이거나, 매핑된 원천 컬럼이 민감이면 민감 속성으로 본다. */
    private boolean restricted(Side side, String code) {
        if (side.restrictedProperties().contains(code)) {
            return true;
        }
        return side.mappings().stream()
                .anyMatch(
                        mapping -> {
                            var property = mapping.properties().get(code);
                            return property != null && restrictedColumn(mapping, property.column());
                        });
    }

    /** 키 컬럼이 정책상 민감이거나 민감 속성을 담으면, 원천 키로 그 값이 드러난다. */
    private boolean restrictedKey(Side side) {
        return side.mappings().stream()
                .anyMatch(
                        mapping ->
                                mapping.keyColumns().stream()
                                        .anyMatch(column -> restrictedColumn(mapping, column)));
    }

    private boolean restrictedColumn(RowMapping mapping, String column) {
        return mapping.restrictedColumns().contains(column)
                || policy.gradeOf(mapping.resourceKey(), column) == SourceColumnGrade.RESTRICTED;
    }

    private static List<RowMapping> mappings(Plan plan) {
        return Stream.concat(plan.source().mappings().stream(), plan.target().mappings().stream())
                .toList();
    }

    private Map<Long, List<SourceRow>> readRows(long jobId, Plan plan) {
        Map<Long, Map<Long, List<RowMapping>>> bySource = new LinkedHashMap<>();
        mappings(plan)
                .forEach(
                        mapping ->
                                bySource.computeIfAbsent(
                                                mapping.dataSourceId(),
                                                ignored -> new LinkedHashMap<>())
                                        .computeIfAbsent(
                                                mapping.resourceId(), ignored -> new ArrayList<>())
                                        .add(mapping));

        Map<Long, List<SourceRow>> rows = new LinkedHashMap<>();
        int remaining = maxRows;
        for (var entry : bySource.entrySet()) {
            long sourceId = entry.getKey();
            List<Table> toRead =
                    entry.getValue().values().stream().map(CandidateCheckJob::table).toList();
            Map<Long, List<SourceRow>> read = readSource(jobId, sourceId, toRead, remaining);
            remaining -= read.values().stream().mapToInt(List::size).sum();
            rows.putAll(read);
        }
        return rows;
    }

    private Map<Long, List<SourceRow>> readSource(
            long jobId, long sourceId, List<Table> toRead, int remaining) {
        var source =
                sources.findById(sourceId)
                        .orElseThrow(() -> new QueuedJobFailure("후보 시험의 원천 연결 설정이 없습니다."));
        if (source.getSourceKind() != SourceKind.POSTGRES) {
            throw new QueuedJobFailure("후보 시험은 PostgreSQL 원천만 읽을 수 있습니다.");
        }
        try {
            var endpoint = SourceEndpoint.from(source.getConnectionConfig());
            var account =
                    credentials
                            .findAccount(sourceId, CredentialPurpose.SYNC)
                            .orElseThrow(
                                    () -> new QueuedJobFailure("SYNC 계정이 등록되지 않아 원천을 읽을 수 없습니다."));
            return tables.read(endpoint, account, toRead, remaining).rows();
        } catch (TooManyRowsException e) {
            throw new QueuedJobFailure("후보 시험의 원천 행 수가 조회 한도(%d건)를 넘었습니다.".formatted(maxRows));
        } catch (SQLException e) {
            log.warn(
                    "후보 시험 원천 조회 실패 checkRunId={} dataSourceId={} sqlState={}",
                    jobId,
                    sourceId,
                    e.getSQLState());
            throw new QueuedJobFailure("후보 시험의 원천 테이블을 읽을 수 없습니다.");
        } catch (SourceCredentialException | IllegalArgumentException e) {
            throw new QueuedJobFailure("후보 시험의 원천 연결 설정을 확인해 주세요.");
        }
    }

    private static Table table(List<RowMapping> mappings) {
        RowMapping first = mappings.getFirst();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        for (RowMapping mapping : mappings) {
            keys.addAll(mapping.keyColumns());
            mapping.properties().values().forEach(property -> columns.add(property.column()));
        }
        columns.addAll(keys);
        return new Table(first.resourceId(), first.resourceKey(), List.copyOf(columns), keys);
    }

    private List<CheckRow> toCheckRows(Side side, Map<Long, List<SourceRow>> rows) {
        List<CheckRow> result = new ArrayList<>();
        for (RowMapping mapping : side.mappings()) {
            for (SourceRow row : rows.get(mapping.resourceId())) {
                Map<String, Object> properties = new LinkedHashMap<>();
                mapping.properties()
                        .forEach(
                                (code, property) -> {
                                    try {
                                        properties.put(
                                                code,
                                                converter.propertyValue(
                                                        row.values().get(property.column()),
                                                        property.steps(),
                                                        property.dataType()));
                                    } catch (ConversionException ignored) {
                                        // 수집과 같이 바꿀 수 없는 값은 속성에서 뺀다.
                                    }
                                });
                if (!side.filter().matches(properties)) {
                    continue;
                }
                Map<String, Object> key = new LinkedHashMap<>();
                mapping.keyColumns().forEach(column -> key.put(column, row.keyTexts().get(column)));
                Map<String, Object> values = new LinkedHashMap<>();
                // 키가 없는 행도 검사 건수에는 남기되, 객체로 연결될 수 없는 행이므로 매칭하지 않는다.
                if (!key.containsValue(null)) {
                    properties.forEach(
                            (code, value) -> values.put(side.typeCode() + "." + code, value));
                }
                result.add(new CheckRow(mapping.resourceId(), key, null, values));
            }
        }
        return result;
    }

    @Override
    public void complete(long jobId, Evaluated evaluated) {
        if (!runs.lockIfRunning(jobId)) {
            throw new QueuedJobFailure("검사 실행이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
        results.save(
                CheckResult.of(
                        jobId,
                        null,
                        evaluated.evaluation(),
                        samples(evaluated.evaluation(), evaluated.restrictedFields())));
        runs.markSucceeded(jobId, Instant.now(clock));
    }

    @Override
    public Map<String, Object> auditResult(Evaluated evaluated) {
        CheckEvaluation evaluation = evaluated.evaluation();
        return Map.of(
                "proposalItemId", evaluation.rule().target().proposalItemId(),
                "revision", evaluation.rule().target().proposalItemRevision(),
                "totalCount", evaluation.totalCount(),
                "matchedCount", evaluation.matchedCount());
    }

    private static List<Map<String, Object>> samples(
            CheckEvaluation evaluation, Set<String> restricted) {
        var conditions = evaluation.rule().conditions();
        return evaluation.rows().stream()
                .filter(row -> row.outcome() != CheckOutcome.MATCHED)
                .limit(CheckRunJob.SAMPLE_LIMIT)
                .<Map<String, Object>>map(
                        row -> {
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("sourceResourceId", row.source().sourceResourceId());
                            sample.put(
                                    "sourceKey",
                                    restricted.contains("sourceKey")
                                            ? null
                                            : row.source().sourceKey());
                            sample.put("classification", row.outcome().name());
                            boolean values = restricted.contains("rawValue");
                            sample.put(
                                    "rawValue",
                                    values ? null : CheckRowText.rawValue(row, conditions));
                            sample.put(
                                    "transformedValue",
                                    values ? null : CheckRowText.transformedValue(row, conditions));
                            sample.put("reason", row.reason());
                            if (!row.targets().isEmpty()) {
                                sample.put(
                                        "targetKey",
                                        restricted.contains("targetKey")
                                                ? null
                                                : row.targets().getFirst().row().sourceKey());
                            }
                            if (!restricted.isEmpty()) {
                                // 값이 없는 것과 민감해서 남기지 않은 것을 화면이 나눌 수 있게 한다.
                                sample.put(
                                        "restrictedFields", restricted.stream().sorted().toList());
                            }
                            return sample;
                        })
                .toList();
    }

    /** run이 complete에 넘기는 결과. restrictedFields는 표본에서 비울 칸 이름이다. */
    public record Evaluated(CheckEvaluation evaluation, Set<String> restrictedFields) {}
}

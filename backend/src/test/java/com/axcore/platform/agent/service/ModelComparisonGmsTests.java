package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.account.service.InitialAdminInitializer;
import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in evaluation against a separately restored, read-only localhost snapshot. Uses the actual
 * AgentAnswerProcessor and query services. Answer persistence, audit writes, summaries and Actions
 * are replaced, so the snapshot is shared unchanged across all models and repetitions.
 */
@SpringBootTest(
        properties = {
            "test.context=agent-model-comparison",
            "spring.flyway.enabled=false",
            "spring.datasource.hikari.read-only=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "axcore.agent.history.summary-max-tokens=0"
        })
@ActiveProfiles("api")
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_EVAL", matches = "models")
class ModelComparisonGmsTests {
    @Autowired ChatModel chatModel;
    @Autowired DataSource database;
    @Autowired ObjectSearchQuery objects;
    @Autowired ObjectProvider<ObjectDetailQuery> details;
    @Autowired ObjectProvider<ObjectEvidenceQuery> evidence;
    @Autowired ObjectProvider<ObjectPathPlanner> objectPaths;
    @Autowired AuditExecutionQueryService executions;
    @Autowired ModelSummaryService modelSummary;
    @Autowired DataAccessPolicy dataAccess;
    @Autowired CompletionEstimator completionEstimator;
    @Autowired EquipmentCapabilityComparer capabilityComparer;
    @Autowired PropertyValueSampleService propertyValues;
    @Autowired AgentTargetResolver targets;
    @Autowired DataAsOfResolver dataAsOf;
    @Autowired JsonMapper json;
    @MockitoBean InitialAdminInitializer initialAdmin;
    @MockitoBean AgentActionTools actions;

    @Test
    void comparesModelsOnSameLocalSnapshot() throws Exception {
        verifyReadOnlyLocalDatabase();
        Specification spec =
                json.readValue(
                        Files.readString(Path.of(required("AXCORE_AGENT_EVAL_QUESTIONS"))),
                        Specification.class);
        Path output = Path.of(required("AXCORE_AGENT_EVAL_OUTPUT"));
        Files.createDirectories(output);
        Clock clock = Clock.fixed(Instant.parse(spec.now()), ZoneOffset.UTC);
        LoginAccount defaultActor =
                new LoginAccount(
                        1L,
                        "model-evaluation",
                        System.getenv().getOrDefault("AXCORE_AGENT_EVAL_ROLE", "VIEWER"),
                        false);
        List<String> models = Arrays.asList(required("AXCORE_AGENT_EVAL_MODELS").split(","));
        int repeat = Integer.parseInt(required("AXCORE_AGENT_EVAL_REPEAT"));
        int limit = Integer.parseInt(required("AXCORE_AGENT_EVAL_TURN_LIMIT"));
        String reasoningEffort =
                System.getenv().getOrDefault("AXCORE_AGENT_EVAL_REASONING_EFFORT", "none");
        when(actions.forAnswer(any())).thenReturn(List.of());
        Map<String, List<Long>> expectedIds = expectedObjects(spec, defaultActor, clock);
        AtomicInteger written = new AtomicInteger();
        int parallel =
                Math.min(
                        models.size(),
                        Integer.parseInt(
                                System.getenv().getOrDefault("AXCORE_AGENT_EVAL_PARALLEL", "1")));
        try (ExecutorService pool = Executors.newFixedThreadPool(parallel)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (String modelId : models) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int run = 1; run <= repeat; run++) {
                                        List<AgentMessage> history = new ArrayList<>();
                                        QuestionInterpretation previous = null;
                                        PendingClarification pending = null;
                                        String activeScenario = null;
                                        String activeRole = null;
                                        for (int index = 0;
                                                index < Math.min(limit, spec.turns().size());
                                                index++) {
                                            Turn turn = spec.turns().get(index);
                                            LoginAccount actor = actorFor(turn, defaultActor);
                                            String scenario =
                                                    turn.scenario() == null
                                                            ? "default"
                                                            : turn.scenario();
                                            if (!scenario.equals(activeScenario)
                                                    || !actor.roleCode().equals(activeRole)) {
                                                history.clear();
                                                previous = null;
                                                pending = null;
                                                activeScenario = scenario;
                                                activeRole = actor.roleCode();
                                            }
                                            ModelUsage model = new ModelUsage(chatModel, modelId);
                                            StaticListableBeanFactory beans =
                                                    new StaticListableBeanFactory();
                                            beans.addBean("chatModel", model);
                                            ObjectProvider<ChatModel> provider =
                                                    beans.getBeanProvider(ChatModel.class);
                                            QuestionInterpreter interpreter =
                                                    new QuestionInterpreter(provider, clock);
                                            AgentResponder responder =
                                                    new AgentResponder(
                                                            provider,
                                                            executions,
                                                            objects,
                                                            details,
                                                            evidence,
                                                            objectPaths,
                                                            modelSummary,
                                                            dataAccess,
                                                            actions,
                                                            completionEstimator,
                                                            capabilityComparer,
                                                            propertyValues,
                                                            clock,
                                                            reasoningEffort,
                                                            false,
                                                            false);
                                            AtomicReference<Result> result =
                                                    new AtomicReference<>();
                                            List<Map<String, Object>> calls = new ArrayList<>();
                                            AgentQueryRecorder recorder = recording(calls);
                                            AgentAnswerService answers = answers(result);
                                            AgentAnswerProcessor processor =
                                                    new AgentAnswerProcessor(
                                                            answers,
                                                            interpreter,
                                                            responder,
                                                            recorder,
                                                            targets,
                                                            clock,
                                                            new SyncTaskExecutor(),
                                                            dataAsOf,
                                                            mock(ConversationSummaries.class));
                                            long startedAtMs = System.currentTimeMillis();
                                            long started = System.nanoTime();
                                            processor.onRequested(
                                                    new AgentAnswerRequested(
                                                            index * 2L + 2,
                                                            index * 2L + 1,
                                                            actor,
                                                            turn.question(),
                                                            Map.of(),
                                                            pending,
                                                            previous,
                                                            new RecentTurns(4, 2000)
                                                                    .pick(history, 1),
                                                            null));
                                            long elapsed =
                                                    (System.nanoTime() - started) / 1_000_000;
                                            Result used = result.get();
                                            if (used == null) {
                                                used =
                                                        new Result(
                                                                "ERROR",
                                                                "처리 결과 없음",
                                                                null,
                                                                Map.of());
                                            }
                                            List<Long> expectedTargets =
                                                    expectedIds.getOrDefault(turn.id(), List.of());
                                            String candidateValue = null;
                                            boolean candidateReady = true;
                                            if (turn.candidateIndex() != null) {
                                                expectedTargets =
                                                        candidateIds(
                                                                turn.candidateIndex(), pending);
                                                candidateReady = !expectedTargets.isEmpty();
                                                if (candidateReady
                                                        && turn.candidateProperty() != null) {
                                                    Object value =
                                                            details.getObject()
                                                                    .detail(
                                                                            actor,
                                                                            expectedTargets
                                                                                    .getFirst())
                                                                    .properties()
                                                                    .get(turn.candidateProperty());
                                                    candidateReady = value != null;
                                                    candidateValue =
                                                            value == null ? null : value.toString();
                                                }
                                            }
                                            Map<String, Object> row =
                                                    outcome(
                                                            modelId,
                                                            run,
                                                            index,
                                                            turn,
                                                            used,
                                                            calls,
                                                            expectedTargets,
                                                            elapsed,
                                                            model);
                                            boolean candidateFactOk =
                                                    candidateValue == null
                                                            || used.answer()
                                                                    .contains(candidateValue);
                                            row.put(
                                                    "targetOk",
                                                    Boolean.TRUE.equals(row.get("targetOk"))
                                                            && candidateReady);
                                            row.put(
                                                    "factsOk",
                                                    Boolean.TRUE.equals(row.get("factsOk"))
                                                            && candidateFactOk);
                                            row.put(
                                                    "passed",
                                                    Boolean.TRUE.equals(row.get("passed"))
                                                            && candidateReady
                                                            && candidateFactOk);
                                            row.put("expectedTargetIds", expectedTargets);
                                            row.put("candidateExpectedValue", candidateValue);
                                            row.put("answerReasoningEffort", reasoningEffort);
                                            row.put("role", actor.roleCode());
                                            row.put("startedAtMs", startedAtMs);
                                            row.put("endedAtMs", System.currentTimeMillis());
                                            synchronized (this) {
                                                Files.writeString(
                                                        output.resolve("outcomes.jsonl"),
                                                        json.writeValueAsString(row) + "\n",
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.APPEND);
                                            }
                                            written.incrementAndGet();
                                            System.out.printf(
                                                    "Agent 평가: model=%s run=%d turn=%s status=%s passed=%s%n",
                                                    modelId,
                                                    run,
                                                    turn.id(),
                                                    used.status(),
                                                    row.get("passed"));
                                            history.add(
                                                    AgentMessage.question(
                                                            1L,
                                                            turn.question(),
                                                            Map.of(),
                                                            clock.instant()));
                                            AgentMessage answer =
                                                    AgentMessage.runningAnswer(1L, clock.instant());
                                            if (used.status().equals("ERROR")) {
                                                answer.fail(used.answer(), "평가 호출 실패");
                                            } else {
                                                answer.succeed(used.answer(), used.meta());
                                            }
                                            history.add(answer);
                                            if (used.status().equals("CLARIFY")) {
                                                pending =
                                                        PendingClarification.fromAnswer(
                                                                used.answer(), used.meta());
                                            } else if (used.status().equals("ANSWERED")) {
                                                previous = used.conditions();
                                                pending = null;
                                            }
                                            // HELP/OUT_OF_SCOPE/PERSON_EVALUATION preserve the
                                            // previous business
                                            // state, like production.
                                        }
                                    }
                                    return null;
                                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        }
        assertThat(written.get())
                .isEqualTo(models.size() * repeat * Math.min(limit, spec.turns().size()));
    }

    private static LoginAccount actorFor(Turn turn, LoginAccount fallback) {
        return new LoginAccount(
                1L,
                "model-evaluation",
                turn.role() == null ? fallback.roleCode() : turn.role(),
                false);
    }

    static List<Long> candidateIds(int oneBasedIndex, PendingClarification pending) {
        if (pending == null || oneBasedIndex < 1) {
            return List.of();
        }
        List<Long> ids =
                pending.preferred().stream()
                        .filter(object -> object.name() != null)
                        .map(AgentReferenceObject::id)
                        .distinct()
                        .toList();
        return oneBasedIndex > ids.size() ? List.of() : List.of(ids.get(oneBasedIndex - 1));
    }

    private void verifyReadOnlyLocalDatabase() throws Exception {
        try (Connection connection = database.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/axcore_eval");
            assertThat(connection.getMetaData().getUserName()).isEqualTo("axcore_eval_reader");
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "select current_setting('transaction_read_only'), "
                                            + "has_table_privilege(current_user, 'platform.object_instance', 'INSERT,UPDATE,DELETE')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("on");
                assertThat(rows.getBoolean(2)).isFalse();
            }
        }
    }

    private Map<String, List<Long>> expectedObjects(
            Specification spec, LoginAccount actor, Clock clock) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (Turn turn : spec.turns()) {
            if (turn.targets().isEmpty()) {
                continue;
            }
            AgentTargetResolver.Resolution resolved =
                    targets.resolve(
                            turn.targets(),
                            List.of(),
                            new AgentQuerySession(
                                    actorFor(turn, actor),
                                    0,
                                    mock(AgentQueryRecorder.class),
                                    clock));
            if (!resolved.issues().isEmpty()) {
                throw new IllegalStateException(
                        "평가 정답 대상을 DB/역할에서 정할 수 없습니다: " + turn.id() + ". 스냅샷과 --role을 확인하세요.");
            }
            result.put(turn.id(), resolved.found().stream().map(AgentReferenceObject::id).toList());
        }
        return result;
    }

    private AgentQueryRecorder recording(List<Map<String, Object>> calls) {
        AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
        doAnswer(
                        invocation -> {
                            AgentQueryResult result = invocation.getArgument(6);
                            Map<String, Object> call = new LinkedHashMap<>();
                            call.put("tool", result.toolCode());
                            call.put("input", invocation.getArgument(3));
                            call.put("objectId", invocation.getArgument(5));
                            call.put("succeeded", result.succeeded());
                            call.put("category", result.resultCategory());
                            call.put("sources", result.sources());
                            calls.add(call);
                            return null;
                        })
                .when(recorder)
                .record(
                        any(),
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        return recorder;
    }

    private AgentAnswerService answers(AtomicReference<Result> result) {
        AgentAnswerService answers = mock(AgentAnswerService.class);
        doAnswer(
                        call -> {
                            QuestionInterpretation conditions = call.getArgument(2);
                            result.set(
                                    new Result(
                                            "ANSWERED",
                                            call.getArgument(1),
                                            conditions,
                                            Map.of("conditions", conditions.toConditions())));
                            return null;
                        })
                .when(answers)
                .answerWithResults(anyLong(), any(), any(), any(), any());
        doAnswer(
                        call -> {
                            QuestionInterpretation conditions = call.getArgument(1);
                            List<AgentTargetResolver.TargetIssue> issues = call.getArgument(2);
                            result.set(
                                    new Result(
                                            "CLARIFY",
                                            AgentAnswerService.clarifyingQuestion(
                                                    conditions, issues),
                                            conditions,
                                            Map.of(
                                                    "conditions",
                                                    conditions.toConditions(),
                                                    "clarification",
                                                    AgentAnswerService.clarification(
                                                            conditions,
                                                            issues,
                                                            call.getArgument(3)))));
                            return null;
                        })
                .when(answers)
                .answerClarification(anyLong(), any(), any(), any());
        doAnswer(
                        call -> {
                            QuestionScope scope = call.getArgument(1);
                            result.set(
                                    new Result(
                                            scope.name(),
                                            AgentAnswerService.outsideBusinessContent(scope),
                                            null,
                                            Map.of("scope", scope.name())));
                            return null;
                        })
                .when(answers)
                .answerOutsideBusiness(anyLong(), any());
        doAnswer(
                        call -> {
                            result.set(new Result("ERROR", "AI 호출 실패", null, Map.of()));
                            return null;
                        })
                .when(answers)
                .fail(anyLong(), any(), any());
        doAnswer(
                        call -> {
                            result.set(new Result("ERROR", "AI 호출 또는 조회 실패", null, Map.of()));
                            return null;
                        })
                .when(answers)
                .failWithResults(anyLong(), any(), any());
        return answers;
    }

    static Map<String, Object> outcome(
            String model,
            int run,
            int index,
            Turn turn,
            Result used,
            List<Map<String, Object>> calls,
            List<Long> expectedIds,
            long elapsed,
            ModelUsage usage) {
        Set<Long> actualIds =
                used.conditions() == null
                        ? Set.of()
                        : Set.copyOf(used.conditions().referenceObjectIds());
        boolean targetOk = actualIds.equals(Set.copyOf(expectedIds));
        Set<String> tools =
                calls.stream()
                        .filter(call -> Boolean.TRUE.equals(call.get("succeeded")))
                        .filter(
                                call ->
                                        turn.categories()
                                                .contains(String.valueOf(call.get("category"))))
                        .map(call -> call.get("tool").toString())
                        .collect(Collectors.toSet());
        boolean toolsOk = tools.containsAll(turn.tools());
        boolean factsOk =
                turn.patterns().stream()
                        .allMatch(
                                pattern -> Pattern.compile(pattern).matcher(used.answer()).find());
        boolean statusOk = used.status().equals(turn.status());
        boolean forbiddenOk =
                turn.forbiddenPatterns().stream()
                        .noneMatch(
                                pattern -> Pattern.compile(pattern).matcher(used.answer()).find());
        boolean periodOk =
                Boolean.TRUE.equals(turn.periodAbsent())
                        ? used.conditions() != null && !used.conditions().hasPeriod()
                        : turn.from() == null && turn.to() == null
                                || used.conditions() != null
                                        && used.conditions().hasPeriod()
                                        && used.conditions().from().toString().equals(turn.from())
                                        && used.conditions().to().toString().equals(turn.to());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model);
        row.put("run", run);
        row.put("turn", index + 1);
        row.put("caseId", turn.id());
        row.put("scenario", turn.scenario() == null ? "default" : turn.scenario());
        row.put("question", turn.question());
        row.put("status", used.status());
        row.put("targetOk", targetOk);
        row.put("toolsOk", toolsOk);
        row.put("factsOk", factsOk);
        row.put("forbiddenOk", forbiddenOk);
        row.put("periodOk", periodOk);
        row.put("passed", targetOk && toolsOk && factsOk && statusOk && forbiddenOk && periodOk);
        row.put("elapsedMs", elapsed);
        row.put("promptTokens", usage.promptTokens);
        row.put("completionTokens", usage.completionTokens);
        row.put("modelCalls", usage.calls);
        row.put("usageMissing", usage.usageMissing);
        row.put("errorType", usage.errorType);
        row.put("conditions", used.conditions() == null ? null : used.conditions().toConditions());
        row.put("answer", used.answer());
        row.put("toolCalls", calls);
        return row;
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 평가 환경 변수 없음: " + name);
        }
        return value;
    }

    record Specification(String now, List<Turn> turns) {}

    record Turn(
            String id,
            String question,
            List<String> targets,
            String status,
            List<String> tools,
            List<String> patterns,
            String scenario,
            String role,
            String from,
            String to,
            List<String> forbiddenPatterns,
            List<String> categories,
            Integer candidateIndex,
            String candidateProperty,
            Boolean periodAbsent) {
        Turn {
            forbiddenPatterns = forbiddenPatterns == null ? List.of() : forbiddenPatterns;
            categories = categories == null ? List.of("FOUND") : categories;
            if (Boolean.TRUE.equals(periodAbsent) && (from != null || to != null)) {
                throw new IllegalArgumentException("기간 없음과 날짜 범위를 함께 기대할 수 없습니다.");
            }
        }

        Turn(
                String id,
                String question,
                List<String> targets,
                String status,
                List<String> tools,
                List<String> patterns) {
            this(
                    id,
                    question,
                    targets,
                    status,
                    tools,
                    patterns,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of("FOUND"),
                    null,
                    null,
                    null);
        }

        Turn(
                String id,
                String question,
                List<String> targets,
                String status,
                List<String> tools,
                List<String> patterns,
                String scenario,
                String role,
                String from,
                String to,
                List<String> forbiddenPatterns,
                List<String> categories) {
            this(
                    id,
                    question,
                    targets,
                    status,
                    tools,
                    patterns,
                    scenario,
                    role,
                    from,
                    to,
                    forbiddenPatterns,
                    categories,
                    null,
                    null,
                    null);
        }
    }

    record Result(
            String status,
            String answer,
            QuestionInterpretation conditions,
            Map<String, Object> meta) {}

    static final class ModelUsage implements ChatModel {
        private final ChatModel delegate;
        private final String model;
        long promptTokens;
        long completionTokens;
        int calls;
        int usageMissing;
        String errorType;

        ModelUsage(ChatModel delegate, String model) {
            this.delegate = delegate;
            this.model = model;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            OpenAiChatOptions options =
                    prompt.getOptions() instanceof OpenAiChatOptions provided
                            ? provided
                            : (OpenAiChatOptions) delegate.getOptions();
            calls++;
            try {
                ChatResponse response =
                        delegate.call(
                                new Prompt(
                                        prompt.getInstructions(),
                                        options.mutate().model(model).build()));
                Usage usage =
                        response.getMetadata() == null ? null : response.getMetadata().getUsage();
                if (usage == null) {
                    usageMissing++;
                } else {
                    promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    completionTokens +=
                            usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                }
                return response;
            } catch (RuntimeException error) {
                errorType = error.getClass().getSimpleName();
                throw error;
            }
        }

        @Override
        public ChatOptions getOptions() {
            return ((OpenAiChatOptions) delegate.getOptions()).mutate().model(model).build();
        }
    }
}

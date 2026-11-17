package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 답변 하나를 만드는 동안의 도구 호출 모음. 조회 도구는 호출마다 순번을 매겨 기록하고 결과를 answerMeta.results로 모은다. Action 도구의 결과는
 * answerMeta.actions로 모은다(160). 답변 하나에만 쓰고 버린다.
 */
@Slf4j
public class AgentQuerySession {
    static final String QUERY_FAILED = "조회하지 못했습니다.";
    static final String NOT_TARGET =
            "이 질문의 대상 객체가 아니라 조회하지 않았습니다. '참고 객체 ID'나 이번 답변의 조회 결과에 나온 객체만 조회할 수 있습니다.";

    /** 객체 하나를 조회하는 도구의 대상 종류. */
    static final String OBJECT_INSTANCE = "object_instance";

    /**
     * 답변 하나에서 객체 하나를 대상으로 조회할 수 있는 횟수. 목록·검색으로 찾은 객체를 하나씩 끝없이 조회하지 않게 한다. 여러 객체는 객체 목록 조회나 Object
     * Set으로 한 번에 본다.
     */
    static final int MAX_OBJECT_LOOKUPS = 20;

    static final String OBJECT_LOOKUP_LIMIT =
            "이번 답변에서 객체 하나씩 조회는 "
                    + MAX_OBJECT_LOOKUPS
                    + "번까지라 조회하지 않았습니다. 여러 객체는 객체 목록 조회나 Object Set으로 한 번에 확인하거나, 지금까지 조회한 결과로 답하면서"
                    + " 일부만 확인했다고 말해 주세요.";

    /**
     * 답변 하나에서 AI가 부를 수 있는 도구 호출 수. Spring AI의 한도(AgentResponder)보다 먼저 걸려, 한도에 닿아도 AI가 지금까지 받은 결과로
     * 답을 맺게 한다. Spring AI 한도에 걸리면 답이 맺어지지 않는다.
     */
    static final int MAX_TOOL_CALLS = 25;

    static final String TOOL_CALL_LIMIT =
            "이번 답변에서 도구는 "
                    + MAX_TOOL_CALLS
                    + "번까지 부를 수 있어 더 조회하지 않았습니다. 도구를 더 부르지 말고 지금까지 조회한 결과로 답해 주세요. 확인하지 못한 부분은 일부만"
                    + " 확인했다고 말합니다.";

    /** 도구 호출 한도에 걸린 호출의 기록 이름. */
    static final String TOOL_CALL_LIMIT_LABEL = "도구 호출 한도";

    /** AI에게 알려 주는 기준 시점의 시간대. 답변은 한국 시간으로 말한다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    /** 물은 상태와 비교할 조건 값을 모으는 도구. 관계 따라가기·목표 유형 찾기의 조건은 비교하지 않는다(2026-11-16 리뷰). */
    private static final Set<String> FILTERING_TOOLS =
            Set.of(AgentObjectListTools.OBJECT_LIST, AgentObjectSetTools.OBJECT_SET_START);

    private final LoginAccount asker;
    private final long questionId;
    private final AgentQueryRecorder recorder;
    private final Clock clock;
    // AI가 부른 조회 도구 호출(거절 포함)을 부른 순서대로 담는다. 실패한 호출을 뒤에서 다시 조회해 성공했는지 볼 때 쓴다(449).
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<AgentActionOutcome> actions = new ArrayList<>();
    // 결과가 나온(FOUND) 조회가 거른 저장 값을 (유형, 속성 코드)별로 모은다. 사용자가 물은 값과 다른 값으로 찾았는지 서버가
    // 비교한다(PropertyValueNotice).
    private final Map<String, Set<String>> filteredByProperty = new LinkedHashMap<>();
    // 객체 목록·객체 집합 시작 결과에 온 속성 값 종류. 물은 값이 저장돼 있으면 "저장된 값은 없어"라고 알리지 않는다.
    private final Set<String> storedValues = new LinkedHashSet<>();
    private boolean unrecorded;
    // 이 답변에서 남긴 조회 기록의 순번. 서버 조회(lookup)와 도구 조회(run)가 함께 쓴다.
    private int sequence;
    // 객체별 도구로 조회할 수 있는 객체. null이면 제한하지 않는다.
    private Set<Long> allowedObjectIds;
    // 이번 답변에서 객체 하나를 대상으로 실행한 조회 수. MAX_OBJECT_LOOKUPS를 넘기지 않는다.
    private int objectLookups;
    // 이번 답변에서 AI가 부른 도구 수. 한도에 걸린 호출도 센다.
    private int toolCalls;

    // 조회 결과의 기준 시점을 구한다(145). null이면 구하지 않고 기준 시점을 비운다.
    private final DataAsOfResolver dataAsOf;

    // 이번 답변에서 만든 Object Set(380). 답변이 끝나면 세션과 함께 버린다.
    private final Map<String, ObjectSet> objectSets = new LinkedHashMap<>();
    // 이번 답변에서 성공한 Search Around 수(380).
    private int searchArounds;

    /**
     * 서버가 들고 있는 객체 집합(380). 한 유형의 객체 ID를 중복 없이 id 순으로 담는다. AI에게는 id와 건수만 주고, 객체는 목록 보기로 20건씩 보여 준다.
     */
    record ObjectSet(String id, long objectTypeId, List<Long> objectIds) {
        ObjectSet {
            objectIds = List.copyOf(objectIds);
        }
    }

    /** 새 Object Set을 이번 답변에 보관하고 돌려준다. id는 답변 안에서만 쓰는 S1, S2, … 순번이다. */
    synchronized ObjectSet saveObjectSet(long objectTypeId, List<Long> objectIds) {
        ObjectSet set = new ObjectSet("S" + (objectSets.size() + 1), objectTypeId, objectIds);
        objectSets.put(set.id(), set);
        return set;
    }

    /** 이번 답변에서 만든 Object Set. 없거나 다른 답변의 id면 null이다. */
    synchronized ObjectSet objectSet(String id) {
        return id == null ? null : objectSets.get(id.strip());
    }

    synchronized int searchArounds() {
        return searchArounds;
    }

    synchronized void countSearchAround() {
        searchArounds++;
    }

    // 이번 답변에서 성공한 목표 유형 찾기 수. 한 번에 경로 여러 개를 건너므로 따로 센다.
    private int finds;

    synchronized int finds() {
        return finds;
    }

    synchronized void countFind() {
        finds++;
    }

    // 목표 유형 찾기가 만든 Object Set id. 이 집합을 관계 따라가기로 거르다 실패하면 찾기를 다시 부르라고 안내한다.
    private final Set<String> foundSetIds = new HashSet<>();

    synchronized void markFoundSet(String id) {
        foundSetIds.add(id);
    }

    synchronized boolean isFoundSet(String id) {
        return id != null && foundSetIds.contains(id.strip());
    }

    // 이번 답변에서 설비 조건을 비교한 작업지시와, 비교한 공정이 지금 배정된 설비 ID. 재배정 계획의 정지 설비를 이 값으로 확인한다.
    private final Map<Long, Set<Long>> comparedAssignments = new LinkedHashMap<>();

    synchronized void recordAssignments(long workOrderId, Collection<Long> equipmentIds) {
        comparedAssignments
                .computeIfAbsent(workOrderId, id -> new LinkedHashSet<>())
                .addAll(equipmentIds);
    }

    /** 비교한 공정이 배정된 설비 ID. 이번 답변에서 그 작업지시를 비교하지 않았으면 null이다. */
    synchronized Set<Long> assignedEquipment(long workOrderId) {
        Set<Long> ids = comparedAssignments.get(workOrderId);
        return ids == null ? null : Set.copyOf(ids);
    }

    /** 기준 시점을 구하지 않는 세션. 기준 시점과 관계없는 도구·기록 테스트가 쓴다. */
    public AgentQuerySession(
            LoginAccount asker, long questionId, AgentQueryRecorder recorder, Clock clock) {
        this(asker, questionId, recorder, clock, null);
    }

    public AgentQuerySession(
            LoginAccount asker,
            long questionId,
            AgentQueryRecorder recorder,
            Clock clock,
            DataAsOfResolver dataAsOf) {
        this.asker = asker;
        this.questionId = questionId;
        this.recorder = recorder;
        this.clock = clock;
        this.dataAsOf = dataAsOf;
    }

    /**
     * AI에게 넘기는 시각을 한국 시간 오프셋으로 적는다(예: 2026-11-11T10:00+09:00). UTC로 넘기면 AI가 날짜·시각을 9시간 틀리게 말할 수 있다.
     * null이면 null이다.
     */
    static String inUserZone(Instant instant) {
        return instant == null ? null : instant.atZone(USER_ZONE).toOffsetDateTime().toString();
    }

    /** 객체들이 나온 원천 표 기준의 기준 시점(145). 구하다 실패해도 조회 결과까지 실패로 만들지 않고 기준 시점만 비운다("기준 시점 확인 불가"). */
    Instant dataAsOfObjects(Collection<Long> objectIds) {
        return safely(() -> dataAsOf.forObjects(objectIds));
    }

    /** 관계 한 건의 양 끝 객체가 나온 원천 표 기준의 기준 시점(145). */
    Instant dataAsOfLink(long linkId) {
        return safely(() -> dataAsOf.forLink(linkId));
    }

    private Instant safely(Supplier<Instant> query) {
        if (dataAsOf == null) {
            return null;
        }
        try {
            return query.get();
        } catch (RuntimeException ex) {
            log.warn("질문 {}의 조회 결과 기준 시점을 구하지 못했습니다. 기준 시점을 비웁니다.", questionId, ex);
            return null;
        }
    }

    /**
     * AI가 보낸 도구 입력이 잘못되어 조회하지 않았다. 실패로 기록하되 AI에게는 고칠 수 있게 이유를 돌려준다. 답변의 results에는 다른 실패와 같은 문구만
     * 남긴다(359).
     */
    static final class InvalidInput extends RuntimeException {
        InvalidInput(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /**
     * 이번 답변의 조회 한도(목표 유형 찾기·관계 따라가기 횟수)에 닿아 조회하지 않았다(449). 입력 오류와 같이 AI에게 이유를 돌려주지만, 조회 기록에는 한도로
     * 남기고 AI에게 입력을 고치라고 하지 않는다.
     */
    static final class LimitReached extends RuntimeException {
        LimitReached(String reason) {
            super(reason);
        }
    }

    /**
     * 조회 도구 호출 한 번(449). 거절된 호출은 result가 null이다. objectId는 객체 하나를 보는 도구일 때만 있다. 같은 도구·같은 객체로 뒤에 성공한
     * 호출이 있으면 이 호출이 실패했어도 다시 조회해 확인한 것으로 본다.
     */
    private record Attempt(
            String toolCode, Long objectId, boolean succeeded, AgentQueryResult result) {
        boolean sameLookup(Attempt other) {
            return toolCode.equals(other.toolCode) && Objects.equals(objectId, other.objectId);
        }
    }

    /** 조회 결과. data는 AI에게 넘길 값이다. */
    public record Outcome(
            Object data,
            AgentResultCategory category,
            List<Map<String, Object>> sources,
            Instant dataAsOf) {}

    /**
     * 이번 답변에서 객체별 도구로 조회할 수 있는 객체를 정한다(147). 질문을 해석해 정한 대상 객체를 허용하고, 이번 답변의 도구 결과(검색·관계·목록)에 실제로 나온
     * 객체도 허용한다(326). 결과에 나온 ID만 더하므로 AI가 지어낸 ID는 계속 막힌다. 허용 목록은 이 세션에만 있어 다음 답변으로 넘어가지 않는다. 종류 전체
     * 질문도 같다. 찾은 객체를 하나씩 끝없이 조회하는 것은 질문 종류가 아니라 조회 횟수({@link #MAX_OBJECT_LOOKUPS})로 막는다. 부르지 않으면
     * 대상은 제한하지 않는다(답변 처리기는 늘 부른다).
     */
    public synchronized void allowObjects(Collection<Long> objectIds) {
        allowedObjectIds = new HashSet<>(objectIds);
    }

    private boolean objectAllowed(Long objectId) {
        return allowedObjectIds == null || allowedObjectIds.contains(objectId);
    }

    /** 객체 여러 개를 한 번에 받는 도구(완료 시각 계산, 148)가 쓴다. 하나라도 이번 답변의 대상이 아니면 false다. */
    synchronized boolean objectsAllowed(Collection<Long> objectIds) {
        return objectIds.stream().allMatch(this::objectAllowed);
    }

    /** 도구가 "지금"을 정할 때 쓰는 시각. 기록 시각과 같은 시계다. */
    Instant now() {
        return Instant.now(clock);
    }

    private void allowFound(List<Map<String, Object>> sources) {
        if (allowedObjectIds == null) {
            return;
        }
        for (Map<String, Object> source : sources) {
            if (source.get("objectId") instanceof Long objectId) {
                allowedObjectIds.add(objectId);
            }
        }
    }

    public LoginAccount asker() {
        return asker;
    }

    public long questionId() {
        return questionId;
    }

    /**
     * 조회하지 않고 거절한다("대상 아님", 조회·도구 한도). 데이터에 닿지 않았으므로 답변의 results에는 넣지 않지만, 어디서 막혔는지 알 수 있게 거절 이유를
     * 실패 조회로 기록한다. AI에게는 같은 이유를 돌려준다.
     *
     * @return AI에게 돌려줄 값
     */
    synchronized Map<String, Object> refuse(
            String toolCode,
            String label,
            Map<String, Object> input,
            String targetType,
            Long targetId,
            String reason) {
        AgentFailureKind kind =
                NOT_TARGET.equals(reason) ? AgentFailureKind.NOT_TARGET : AgentFailureKind.LIMIT;
        AgentQueryResult result = AgentQueryResult.failed(label, toolCode, reason, kind, reason);
        attempts.add(new Attempt(toolCode, objectIdOf(targetType, targetId), false, null));
        record(input, targetType, targetId, result, Instant.now(clock));
        return Map.of("error", reason);
    }

    private static Long objectIdOf(String targetType, Long targetId) {
        return OBJECT_INSTANCE.equals(targetType) ? targetId : null;
    }

    /**
     * AI의 도구 호출 하나를 센다. {@link #MAX_TOOL_CALLS}를 넘었으면 부르지 않고 거절을 기록한 뒤 AI에게 돌려줄 값을 준다. 넘지 않았으면
     * null이다.
     */
    synchronized Map<String, Object> countToolCall(String toolCode, Map<String, Object> input) {
        toolCalls++;
        if (toolCalls <= MAX_TOOL_CALLS) {
            return null;
        }
        return refuse(toolCode, TOOL_CALL_LIMIT_LABEL, input, null, null, TOOL_CALL_LIMIT);
    }

    /**
     * 도구 하나를 실행하고 기록한다. 서버 오류·시간 초과처럼 응답을 받지 못했을 때만 실패이고, 그 사실을 AI에게 알려 "데이터 없음"으로 바꿔 말하지 않게 한다.
     *
     * @return AI에게 돌려줄 값
     */
    public synchronized Map<String, Object> run(
            String toolCode,
            String label,
            Map<String, Object> input,
            String targetType,
            Long targetId,
            Supplier<Outcome> query) {
        if (OBJECT_INSTANCE.equals(targetType) && !objectAllowed(targetId)) {
            // 이번 답변의 대상이 아닌 객체는 조회하지 않는다. AI가 "데이터 없음"으로 말하지 않게 조회하지 않은 이유를 돌려준다(147).
            return refuse(toolCode, label, input, targetType, targetId, NOT_TARGET);
        }
        if (OBJECT_INSTANCE.equals(targetType)) {
            if (objectLookups >= MAX_OBJECT_LOOKUPS) {
                // AI가 남은 객체를 "데이터 없음"으로 말하지 않고 다른 방법을 고르게 이유를 돌려준다.
                return refuse(toolCode, label, input, targetType, targetId, OBJECT_LOOKUP_LIMIT);
            }
            objectLookups++;
        }
        Instant requestedAt = Instant.now(clock);
        AgentQueryResult result;
        Map<String, Object> reply = new LinkedHashMap<>();
        try {
            Outcome outcome = query.get();
            allowFound(outcome.sources());
            if (FILTERING_TOOLS.contains(toolCode)) {
                collectForNotice(toolCode, input, outcome);
            }
            result =
                    new AgentQueryResult(
                            label,
                            toolCode,
                            true,
                            outcome.category(),
                            outcome.sources(),
                            outcome.dataAsOf(),
                            false,
                            null);
            reply.put("resultCategory", outcome.category().name());
            reply.put("data", outcome.data());
            // AI가 계획의 기준 시점(dataAsOf)을 조회한 데이터의 시점으로 채울 수 있게 함께 알려 준다. 모르면 null이다(160).
            // 답변에 기준 시점을 한국 시간으로 말하게, 같은 순간을 한국 시간 오프셋으로 적어 준다(145).
            reply.put("dataAsOf", inUserZone(outcome.dataAsOf()));
        } catch (InvalidInput ex) {
            log.warn("Agent 조회 도구 {} 입력 오류 question={}: {}", toolCode, questionId, ex.getMessage());
            result =
                    AgentQueryResult.failed(
                            label,
                            toolCode,
                            QUERY_FAILED,
                            AgentFailureKind.INVALID_INPUT,
                            ex.getMessage());
            reply.put("error", QUERY_FAILED + " 입력 오류: " + ex.getMessage());
        } catch (LimitReached ex) {
            log.warn("Agent 조회 도구 {} 한도 question={}: {}", toolCode, questionId, ex.getMessage());
            result =
                    AgentQueryResult.failed(
                            label, toolCode, QUERY_FAILED, AgentFailureKind.LIMIT, ex.getMessage());
            reply.put("error", QUERY_FAILED + " " + ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Agent 조회 도구 {} 실패 question={}", toolCode, questionId, ex);
            result =
                    AgentQueryResult.failed(
                            label, toolCode, QUERY_FAILED, AgentFailureKind.ERROR, errorKind(ex));
            reply.put("error", QUERY_FAILED);
        }
        attempts.add(
                new Attempt(
                        toolCode, objectIdOf(targetType, targetId), result.succeeded(), result));
        record(input, targetType, targetId, result, requestedAt);
        return reply;
    }

    /**
     * 서버 오류를 조회 기록에 남길 때의 까닭. 예외 메시지에는 SQL이나 저장된 값이 들어갈 수 있어 남기지 않고 예외 종류만 남긴다. 전체 내용은 서버 로그에 질문
     * 번호와 함께 남는다.
     */
    private static String errorKind(RuntimeException ex) {
        return ex.getClass().getSimpleName();
    }

    /**
     * 서버가 답변 전에 직접 하는 조회다(147 대상 찾기). 도구 조회와 같이 순번을 매겨 기록하지만, 사용자가 물은 정보가 아니므로 정보별 결과(results)에는 넣지
     * 않는다. 조회가 실패하면 실패로 기록하고 예외를 다시 던져 답변을 실패로 끝낸다.
     */
    public synchronized Outcome lookup(
            String toolCode, String label, Map<String, Object> input, Supplier<Outcome> query) {
        Instant requestedAt = Instant.now(clock);
        Outcome outcome;
        try {
            outcome = query.get();
        } catch (RuntimeException ex) {
            log.error("Agent 서버 조회 {} 실패 question={}", toolCode, questionId, ex);
            try {
                record(
                        input,
                        null,
                        null,
                        AgentQueryResult.failed(
                                label,
                                toolCode,
                                QUERY_FAILED,
                                AgentFailureKind.ERROR,
                                errorKind(ex)),
                        requestedAt);
            } catch (RuntimeException recordEx) {
                ex.addSuppressed(recordEx);
            }
            throw ex;
        }
        record(
                input,
                null,
                null,
                new AgentQueryResult(
                        label,
                        toolCode,
                        true,
                        outcome.category(),
                        outcome.sources(),
                        outcome.dataAsOf(),
                        false,
                        null),
                requestedAt);
        return outcome;
    }

    private void record(
            Map<String, Object> input,
            String targetType,
            Long targetId,
            AgentQueryResult result,
            Instant requestedAt) {
        sequence++;
        try {
            recorder.record(
                    asker, questionId, sequence, input, targetType, targetId, result, requestedAt);
        } catch (RuntimeException ex) {
            unrecorded = true;
            throw ex;
        }
    }

    /** 조회 기록을 하나라도 남기지 못했으면 답변을 끝내지 않는다. 기록 없는 조회 답변이 나가지 않게 한다. */
    public synchronized void requireRecorded() {
        if (unrecorded) {
            throw new IllegalStateException("조회 기록을 남기지 못했습니다.");
        }
    }

    /** 모든 조회 기록을 남겼는가. 실패 답변에 조회 결과를 남길지 정할 때 쓴다(160). */
    public synchronized boolean allRecorded() {
        return !unrecorded;
    }

    /** 정보별 결과(answerMeta.results). 실패한 결과에는 뒤에서 다시 조회해 성공했는지(retried)를 붙인다(449). 거절된 호출은 넣지 않는다. */
    public synchronized List<AgentQueryResult> results() {
        List<AgentQueryResult> results = new ArrayList<>();
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            if (attempt.result() != null) {
                results.add(attempt.result().withRetried(!attempt.succeeded() && retried(i)));
            }
        }
        return List.copyOf(results);
    }

    /**
     * 결과가 나온 조회(객체 목록·객체 집합 시작)에서 실제로 거른 저장 값. 넣은 순서대로 한 번씩 담는다. 값 종류(values)를 아는 속성의 조건만 담고, 넣은 값이
     * 아니라 076이 맞춘 저장 값 모양으로 담는다("정비 중" → "정비중"). 076이 맞추지 못한 값(unmatchedValues)은 실제로 거른 값이 아니라 빠진다.
     */
    public synchronized List<String> filteredValues() {
        Set<String> all = new LinkedHashSet<>();
        filteredByProperty.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    /** {@link #filteredValues()}를 (유형, 속성 코드)별로 나눈 것. 넣은 순서대로다. */
    public synchronized List<List<String>> filteredValuesByProperty() {
        return filteredByProperty.values().stream().map(List::copyOf).toList();
    }

    // 안내용 부가 집계라, 실패해도 이미 성공한 조회를 실패로 바꾸지 않는다(2026-11-16 리뷰).
    private void collectForNotice(String toolCode, Map<String, Object> input, Outcome outcome) {
        try {
            // 0건이어도 그 유형의 값 종류는 온다. 거른 값은 결과가 나온 조회에서만 모은다.
            rememberStoredValues(outcome.data());
            if (outcome.category() == AgentResultCategory.FOUND) {
                collectFilteredValues(input, outcome.data());
            }
        } catch (RuntimeException ex) {
            log.warn("Agent 조회 {}의 안내용 값 집계를 건너뜁니다 question={}", toolCode, questionId, ex);
        }
    }

    /** 객체 목록·객체 집합 시작 결과로 받은 속성 값 종류. 물은 값이 정말 저장돼 있지 않은지 확인하는 데 쓴다(PropertyValueNotice). */
    public synchronized List<String> storedValues() {
        return List.copyOf(storedValues);
    }

    private void rememberStoredValues(Object data) {
        for (AgentObjectListTools.PropertyItem property : properties(data)) {
            if (property.values() != null) {
                storedValues.addAll(property.values());
            }
        }
    }

    // 입력의 propertyValues는 속성 코드 → 값 하나 또는 값 목록이다(AgentConditions와 같은 모양).
    private void collectFilteredValues(Map<String, Object> input, Object data) {
        if (input == null || !(input.get("propertyValues") instanceof Map<?, ?> conditions)) {
            return;
        }
        List<AgentObjectListTools.PropertyItem> properties = properties(data);
        Object typeId = input.get("objectTypeId");
        conditions.forEach(
                (code, value) -> {
                    List<String> stored = storedValuesOf(properties, code);
                    if (stored == null) {
                        // 값 종류가 많거나 문자가 아닌 속성은 물은 상태와 짝지을 수 없다(2026-11-16 리뷰).
                        return;
                    }
                    Collection<?> values =
                            value instanceof Collection<?> items
                                    ? items
                                    : Collections.singletonList(value);
                    for (Object item : values) {
                        String matched = item == null ? null : storedValue(stored, item.toString());
                        if (matched != null) {
                            filteredByProperty
                                    .computeIfAbsent(
                                            typeId + ":" + code, key -> new LinkedHashSet<>())
                                    .add(matched);
                        }
                    }
                });
    }

    // 객체 목록은 ObjectList, 객체 집합 시작은 Map으로 그 유형의 속성(properties)을 돌려준다.
    private static List<AgentObjectListTools.PropertyItem> properties(Object data) {
        Object found = null;
        if (data instanceof AgentObjectListTools.ObjectList list) {
            found = list.properties();
        } else if (data instanceof Map<?, ?> map) {
            found = map.get("properties");
        }
        if (!(found instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(AgentObjectListTools.PropertyItem.class::isInstance)
                .map(AgentObjectListTools.PropertyItem.class::cast)
                .toList();
    }

    private static List<String> storedValuesOf(
            List<AgentObjectListTools.PropertyItem> properties, Object code) {
        return properties.stream()
                .filter(property -> property.code().equals(code))
                .map(AgentObjectListTools.PropertyItem::values)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // 076과 같은 기준으로 넣은 값이 걸린 저장 값을 찾는다. 똑같은 값이 있으면 그 값, 없으면 띄어쓰기만 다른 저장 값이 하나뿐일 때 그 값이다.
    private static String storedValue(List<String> stored, String value) {
        String text = value.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (stored.contains(text)) {
            return text;
        }
        String compact = withoutSpaces(text);
        List<String> same =
                stored.stream()
                        .filter(Objects::nonNull)
                        .filter(candidate -> withoutSpaces(candidate).equals(compact))
                        .toList();
        return same.size() == 1 ? same.getFirst() : null;
    }

    private static String withoutSpaces(String value) {
        return value.codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * 실패하거나 거절된 뒤 다시 조회해 성공하지 못한 호출 수(449). 같은 도구(객체 하나를 보는 도구는 같은 객체)로 뒤에 성공한 호출이 있으면 AI가 입력을 고치거나
     * 다시 불러 확인한 것으로 보고 세지 않는다. 화면이 답변에 빠진 정보가 있을 수 있다고 알리는 데 쓴다. 모델이 답에 밝히는지에 맡기지 않는다.
     */
    public synchronized int unconfirmedLookups() {
        int count = 0;
        for (int i = 0; i < attempts.size(); i++) {
            if (!attempts.get(i).succeeded() && !retried(i)) {
                count++;
            }
        }
        return count;
    }

    private boolean retried(int index) {
        Attempt failed = attempts.get(index);
        for (int i = index + 1; i < attempts.size(); i++) {
            Attempt later = attempts.get(i);
            if (later.succeeded() && later.sameLookup(failed)) {
                return true;
            }
        }
        return false;
    }

    /** Action 도구 결과를 모은다. 실행 기록은 실행기가 이미 남겼으므로 여기에는 답변에 보여 줄 값만 둔다. */
    public synchronized void addAction(AgentActionOutcome action) {
        actions.add(action);
    }

    public synchronized List<AgentActionOutcome> actions() {
        return List.copyOf(actions);
    }
}

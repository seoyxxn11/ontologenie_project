package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** AI에게 알려 주는 기준 시점의 시간대. 답변은 한국 시간으로 말한다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final LoginAccount asker;
    private final long questionId;
    private final AgentQueryRecorder recorder;
    private final Clock clock;
    private final List<AgentQueryResult> results = new ArrayList<>();
    private final List<AgentActionOutcome> actions = new ArrayList<>();
    private boolean unrecorded;
    // 이 답변에서 남긴 조회 기록의 순번. 서버 조회(lookup)와 도구 조회(run)가 함께 쓴다.
    private int sequence;
    // 객체별 도구로 조회할 수 있는 객체. null이면 제한하지 않는다.
    private Set<Long> allowedObjectIds;
    // 도구 결과에 나온 객체도 조회를 허용하는가. 종류 전체 질문이면 허용하지 않는다(326).
    private boolean allowFoundObjects;

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

    /** 조회 결과. data는 AI에게 넘길 값이다. */
    public record Outcome(
            Object data,
            AgentResultCategory category,
            List<Map<String, Object>> sources,
            Instant dataAsOf) {}

    /**
     * 이번 답변에서 객체별 도구로 조회할 수 있는 객체를 정한다(147). 질문을 해석해 정한 대상 객체를 허용하고, includeFound면 이번 답변의 도구
     * 결과(검색·관계)에 실제로 나온 객체도 허용한다(326). 결과에 나온 ID만 더하므로 AI가 지어낸 ID는 계속 막힌다. 허용 목록은 이 세션에만 있어 다음 답변으로
     * 넘어가지 않는다. 종류 전체 질문은 검색으로 객체를 많이 찾아 하나씩 조회하지 않게 includeFound를 끈다. 부르지 않으면 제한하지 않는다(답변 처리기는 늘
     * 부른다).
     */
    public synchronized void allowObjects(Collection<Long> objectIds, boolean includeFound) {
        allowedObjectIds = new HashSet<>(objectIds);
        allowFoundObjects = includeFound;
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
        if (allowedObjectIds == null || !allowFoundObjects) {
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
            // 이번 답변의 대상이 아닌 객체는 조회하지 않는다. 데이터에 닿지 않았으므로 기록하지 않고, AI가 "데이터 없음"으로 말하지 않게
            // 조회하지 않은 이유를 돌려준다(147).
            return Map.of("error", NOT_TARGET);
        }
        Instant requestedAt = Instant.now(clock);
        AgentQueryResult result;
        Map<String, Object> reply = new LinkedHashMap<>();
        try {
            Outcome outcome = query.get();
            allowFound(outcome.sources());
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
                    new AgentQueryResult(
                            label, toolCode, false, null, List.of(), null, false, QUERY_FAILED);
            reply.put("error", QUERY_FAILED + " 입력 오류: " + ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Agent 조회 도구 {} 실패 question={}", toolCode, questionId, ex);
            result =
                    new AgentQueryResult(
                            label, toolCode, false, null, List.of(), null, false, QUERY_FAILED);
            reply.put("error", QUERY_FAILED);
        }
        results.add(result);
        record(input, targetType, targetId, result, requestedAt);
        return reply;
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
                        new AgentQueryResult(
                                label, toolCode, false, null, List.of(), null, false, QUERY_FAILED),
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

    public synchronized List<AgentQueryResult> results() {
        return List.copyOf(results);
    }

    /** Action 도구 결과를 모은다. 실행 기록은 실행기가 이미 남겼으므로 여기에는 답변에 보여 줄 값만 둔다. */
    public synchronized void addAction(AgentActionOutcome action) {
        actions.add(action);
    }

    public synchronized List<AgentActionOutcome> actions() {
        return List.copyOf(actions);
    }
}

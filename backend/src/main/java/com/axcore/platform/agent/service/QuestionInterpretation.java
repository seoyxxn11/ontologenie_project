package com.axcore.platform.agent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 질문에서 뽑은 조회 조건(142). AI 출력을 서버에서 검사한 뒤의 값이다. 답변의 answerMeta.conditions로 저장해 다음 질문 때 읽는다(대화방 상태,
 * 2026-11-04 결정). referenceObjects는 이 답변이 대상으로 삼은 객체다(147). kinds는 특정 대상이 아니라 "전체 설비"처럼 종류 전체를 물을
 * 때의 종류 이름이다(147). scope는 질문이 Agent가 다루는 범위인가이며, 해석 직후에만 본다. 조건을 합치거나 바꾸면 업무(BUSINESS)로 둔다.
 * propertyValues는 "진행 중", "1라인", "가이드 마모"처럼 조회 조건으로 말한 속성값이다(360). 어느 속성의 값인지는 답변 AI가 목록·관계 결과의 속성
 * 정보로 맞춘다.
 */
public record QuestionInterpretation(
        String purpose,
        List<String> targets,
        LocalDate from,
        LocalDate to,
        List<AgentReferenceObject> referenceObjects,
        String vaguePeriod,
        List<String> kinds,
        QuestionScope scope,
        List<String> propertyValues) {

    public QuestionInterpretation {
        scope = scope == null ? QuestionScope.BUSINESS : scope;
        propertyValues = propertyValues == null ? List.of() : List.copyOf(propertyValues);
    }

    private static boolean sameValue(String left, String right) {
        return left.replaceAll("\\s+", "").equalsIgnoreCase(right.replaceAll("\\s+", ""));
    }

    /** 속성 조건이 없는 해석. */
    public QuestionInterpretation(
            String purpose,
            List<String> targets,
            LocalDate from,
            LocalDate to,
            List<AgentReferenceObject> referenceObjects,
            String vaguePeriod,
            List<String> kinds,
            QuestionScope scope) {
        this(purpose, targets, from, to, referenceObjects, vaguePeriod, kinds, scope, List.of());
    }

    /** 업무 질문의 해석. */
    public QuestionInterpretation(
            String purpose,
            List<String> targets,
            LocalDate from,
            LocalDate to,
            List<AgentReferenceObject> referenceObjects,
            String vaguePeriod,
            List<String> kinds) {
        this(
                purpose,
                targets,
                from,
                to,
                referenceObjects,
                vaguePeriod,
                kinds,
                QuestionScope.BUSINESS);
    }

    /** 종류 전체를 묻지 않는 해석. */
    public QuestionInterpretation(
            String purpose,
            List<String> targets,
            LocalDate from,
            LocalDate to,
            List<AgentReferenceObject> referenceObjects,
            String vaguePeriod) {
        this(purpose, targets, from, to, referenceObjects, vaguePeriod, List.of());
    }

    /** 무엇을 묻는지(대상·객체·종류) 하나라도 말했는가. */
    private boolean namesSubject() {
        return !targets.isEmpty() || !referenceObjects.isEmpty() || !kinds.isEmpty();
    }

    public boolean hasPeriod() {
        return from != null && to != null;
    }

    public List<Long> referenceObjectIds() {
        return referenceObjects.stream().map(AgentReferenceObject::id).toList();
    }

    /** 되물어야 하는 조건(143). 기간은 모호한 표현만 있을 때 묻고, 기간을 말하지 않은 질문은 묻지 않는다. */
    public List<MissingCondition> missing() {
        List<MissingCondition> missing = new ArrayList<>();
        if (!namesSubject()) {
            missing.add(MissingCondition.TARGET);
        }
        if (purpose == null) {
            missing.add(MissingCondition.PURPOSE);
        }
        if (vaguePeriod != null && !hasPeriod()) {
            missing.add(MissingCondition.PERIOD);
        }
        return missing;
    }

    /**
     * 되물은 뒤 받은 답과 합친다(143). 이번 답에서 새로 확인한 값만 이전 조건을 덮는다. "전체 설비로"처럼 종류 전체로 답하면 이전 대상과 객체는 쓰지 않는다
     * (147). 되묻기의 답은 새로 알려 준 값만 담으므로 속성 조건은 이전 값에 더한다(360).
     */
    public QuestionInterpretation fillFrom(QuestionInterpretation previous) {
        boolean wholeKind = !kinds.isEmpty();
        List<String> mergedValues = new ArrayList<>(previous.propertyValues);
        for (String value : propertyValues) {
            // 띄어쓰기만 다른 값("진행 중"·"진행중")은 같은 조건으로 보고 한 번만 둔다. 해석 결과와 같은 개수 상한을 지킨다.
            boolean known = mergedValues.stream().anyMatch(kept -> sameValue(kept, value));
            if (!known && mergedValues.size() < QuestionInterpreter.MAX_TARGETS) {
                mergedValues.add(value);
            }
        }
        return new QuestionInterpretation(
                purpose != null ? purpose : previous.purpose,
                !targets.isEmpty() || wholeKind ? targets : previous.targets,
                hasPeriod() ? from : previous.from,
                hasPeriod() ? to : previous.to,
                !referenceObjects.isEmpty() || wholeKind
                        ? referenceObjects
                        : previous.referenceObjects,
                vaguePeriod != null ? vaguePeriod : previous.vaguePeriod,
                namesSubject() ? kinds : previous.kinds,
                QuestionScope.BUSINESS,
                mergedValues);
    }

    /**
     * 같은 대화의 후속 질문을 직전 답변의 조건에 이어 붙인다(147). 이번 질문에서 새로 말한 값만 바꾸고 나머지는 이어 쓴다. 대상을 새로 말하면 이전 대상과 참고
     * 객체를 함께 버린다. 다른 대상을 물었는데 이전 객체로 답하지 않게 하기 위해서다. "전체 설비"처럼 종류 전체를 물어도 이전 대상을 잇지 않는다. "최근"처럼 범위
     * 없는 기간을 말하면 이전 기간을 쓰지 않고 되묻는다.
     *
     * <p>속성 조건(360)은 해석이 이번 질문에 적용할 값을 모두 적는다("그중 1라인만" → 이전의 "진행"과 새 "1라인"). 그래서 값이 있으면 그대로 쓰고,
     * 없으면 이전 값을 잇는다. 새 대상·종류를 물으면 이전 속성 조건은 잇지 않는다.
     */
    public QuestionInterpretation continueFrom(QuestionInterpretation previous) {
        boolean newSubject = namesSubject();
        boolean keepPeriod = !hasPeriod() && vaguePeriod == null;
        return new QuestionInterpretation(
                purpose != null ? purpose : previous.purpose,
                newSubject ? targets : previous.targets,
                keepPeriod ? previous.from : from,
                keepPeriod ? previous.to : to,
                newSubject ? referenceObjects : previous.referenceObjects,
                vaguePeriod,
                newSubject ? kinds : previous.kinds,
                QuestionScope.BUSINESS,
                newSubject || !propertyValues.isEmpty() ? propertyValues : previous.propertyValues);
    }

    /**
     * 새 대상을 말한 후속 질문에서, 질문 글에 그대로 다시 쓴 이전 대상을 targets에 되살린다. 새 대상이 대상 전체를 바꾸므로 해석이 이전 대상을 빼면 그 대상을
     * 잃는다(재배정 계획 재질문에서 작업지시·대체 설비가 빠지고 정지 설비만 남은 일, 2026-11-14 대화 95). 코드 앞뒤가 영문·숫자로 이어지면 같은 말로 보지
     * 않는다 (P10과 P103). 대상을 새로 말하지 않았으면 이전 대상을 그대로 잇는 continueFrom에 맡긴다.
     */
    public QuestionInterpretation withRepeatedTargets(
            String question, QuestionInterpretation previous) {
        if (previous == null || targets.isEmpty() || question == null) {
            return this;
        }
        List<String> merged = new ArrayList<>(targets);
        for (String target : previous.targets) {
            boolean listed = merged.stream().anyMatch(kept -> kept.equalsIgnoreCase(target));
            // 이번에 속성값으로 쓴 말은 대상으로 되살리지 않는다(해석기 verify와 같은 기준).
            boolean property =
                    propertyValues.stream().anyMatch(value -> value.equalsIgnoreCase(target));
            if (!listed
                    && !property
                    && merged.size() < QuestionInterpreter.MAX_TARGETS
                    && mentions(question, target)) {
                merged.add(target);
            }
        }
        return merged.size() == targets.size()
                ? this
                : new QuestionInterpretation(
                        purpose,
                        merged,
                        from,
                        to,
                        referenceObjects,
                        vaguePeriod,
                        kinds,
                        scope,
                        propertyValues);
    }

    private static boolean mentions(String question, String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        String text = question.toLowerCase(Locale.ROOT);
        String word = target.strip().toLowerCase(Locale.ROOT);
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            if (!asciiAlnum(text, at - 1)
                    && !asciiAlnum(text, end)
                    && !excluded(text.substring(end))) {
                return true;
            }
        }
        return false;
    }

    /** "P103 말고", "P103은 빼고", "P103 대신"처럼 그 대상을 빼는 말이 바로 뒤에 오면 다시 쓴 대상으로 보지 않는다. */
    private static boolean excluded(String after) {
        String rest = after.stripLeading();
        if (!rest.isEmpty() && "은는이가을를".indexOf(rest.charAt(0)) >= 0) {
            rest = rest.substring(1).stripLeading();
        }
        for (String word : EXCLUDING_WORDS) {
            if (rest.startsWith(word)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> EXCLUDING_WORDS =
            List.of("말고", "대신", "아니라", "아닌", "빼고", "제외", "외에", "외의");

    private static boolean asciiAlnum(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        char c = text.charAt(index);
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** 서버가 찾은 대상 객체로 바꾼다(147). */
    public QuestionInterpretation withReferenceObjects(List<AgentReferenceObject> found) {
        return new QuestionInterpretation(
                purpose, targets, from, to, found, vaguePeriod, kinds, scope, propertyValues);
    }

    /** 대상 이름과 객체를 함께 바꾼다. 되물은 대상만 채울 때 쓴다(147). */
    public QuestionInterpretation withTargets(
            List<String> newTargets, List<AgentReferenceObject> found) {
        return new QuestionInterpretation(
                purpose, newTargets, from, to, found, vaguePeriod, kinds, scope, propertyValues);
    }

    /**
     * answerMeta.conditions 값. 확인하지 못한 조건은 null로 둔다. referenceObjectIds는 referenceObjects의 ID만 모은
     * 것이다.
     */
    public Map<String, Object> toConditions() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("purpose", purpose);
        conditions.put("targets", targets);
        conditions.put(
                "period",
                hasPeriod() ? Map.of("from", from.toString(), "to", to.toString()) : null);
        conditions.put("referenceObjectIds", referenceObjectIds());
        conditions.put(
                "referenceObjects",
                referenceObjects.stream().map(AgentReferenceObject::toMap).toList());
        conditions.put("kinds", kinds);
        conditions.put("propertyValues", propertyValues);
        return conditions;
    }

    /** answerMeta.clarification 값. 모호한 기간 표현은 다음 답과 합칠 때 다시 묻기 위해 남긴다. */
    public Map<String, Object> toClarification() {
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("missing", missing().stream().map(Enum::name).toList());
        clarification.put("vaguePeriod", vaguePeriod);
        return clarification;
    }

    /**
     * 답변의 answerMeta에서 그때 확정한 조건을 다시 읽는다(143·147). referenceObjects가 없는 예전 답변은 referenceObjectIds로
     * ID만 읽는다.
     */
    public static QuestionInterpretation fromAnswerMeta(Map<String, Object> answerMeta) {
        Map<?, ?> conditions = map(answerMeta.get("conditions"));
        Map<?, ?> period = map(conditions.get("period"));
        Map<?, ?> clarification = map(answerMeta.get("clarification"));
        return new QuestionInterpretation(
                text(conditions.get("purpose")),
                strings(conditions.get("targets")),
                date(period.get("from")),
                date(period.get("to")),
                references(conditions),
                text(clarification.get("vaguePeriod")),
                strings(conditions.get("kinds")),
                QuestionScope.BUSINESS,
                strings(conditions.get("propertyValues")));
    }

    private static List<AgentReferenceObject> references(Map<?, ?> conditions) {
        if (conditions.get("referenceObjects") instanceof List<?> list) {
            return list.stream()
                    .map(AgentReferenceObject::fromMap)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return ids(conditions.get("referenceObjectIds")).stream()
                .map(AgentReferenceObject::idOnly)
                .toList();
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> found ? found : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String found ? found : null;
    }

    private static LocalDate date(Object value) {
        return value instanceof String found ? LocalDate.parse(found) : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static List<Long> ids(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Number.class::isInstance)
                .map(id -> ((Number) id).longValue())
                .toList();
    }
}

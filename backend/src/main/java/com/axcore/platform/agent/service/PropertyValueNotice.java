package com.axcore.platform.agent.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 사용자가 물은 속성 값과 실제로 거른 값이 다르면 답변 끝에 알린다(360 후속, 2026-11-16 결정). "정지 중인 설비"처럼 저장 값에 없는 상태를 물으면 답변
 * AI가 뜻이 가까운 "정비중"으로 거르면서 다른 값으로 찾았다는 것을 말하지 않았다. 지시문으로는 밝히게 하지 못해(되묻기 0/6) 서버가 비교한다.
 *
 * <p>띄어쓰기와 끝에 붙은 "중"·"상태"·"인"은 빼고 비교한다. "진행 중"과 저장 값 "진행"은 같은 값이라 알리지 않는다. 목록 질문에서, 결과가 나온 객체 목록·객체
 * 집합 시작 조회가 값 종류를 아는 속성으로 거른 저장 값만 (유형, 속성)별로 본다(AgentQuerySession.filteredValuesByProperty). 물은 값과
 * 다른 거른 값이 한 속성에서만 나왔을 때만 알리고, 물은 값이 조회 결과의 값 종류에 들어 있으면 저장된 값이라 "저장된 값은 없어"라고 말하지 않는다.
 */
final class PropertyValueNotice {
    private static final List<String> SUFFIXES = List.of("상태", "중", "인");

    private PropertyValueNotice() {}

    /**
     * 목록 질문(앞 대화를 이은 해석에 종류가 있고 대상이 없음)일 때, 이번 질문에서 말한 값(said)만 비교한다(2026-11-16 리뷰). 대상이 있는 질문은 설비
     * 코드 같은 다른 속성 값으로도 거르고, 앞 질문에서 이어받은 상태 값("정지 중인 설비" → "그 설비 비가동 이력은?")은 이번 조회와 짝지을 수 없다. "그럼 대기
     * 중인 건?"처럼 조건만 바꾼 후속 목록 질문은 종류를 이어받아도 이번에 말한 값으로 알린다. 탐색 화면의 참고 객체는 목록 질문이면 목록 규칙을 따르므로(답변 지시)
     * 판정에 넣지 않는다.
     */
    static String append(
            String content,
            QuestionInterpretation interpretation,
            List<String> said,
            List<List<String>> filteredByProperty,
            List<String> stored) {
        if (!isListQuestion(interpretation)) {
            return content;
        }
        return append(content, noticeByProperty(said, filteredByProperty, stored));
    }

    static boolean isListQuestion(QuestionInterpretation interpretation) {
        return interpretation != null
                && !isEmpty(interpretation.kinds())
                && isEmpty(interpretation.targets());
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    /** 알릴 것이 없으면 답변을 그대로 돌려준다. */
    static String append(String content, List<String> asked, List<String> filtered) {
        return append(content, notice(asked, filtered));
    }

    private static String append(String content, String notice) {
        if (notice == null) {
            return content;
        }
        return content == null || content.isBlank()
                ? notice
                : content.stripTrailing() + "\n\n" + notice;
    }

    static String notice(List<String> asked, List<String> filtered) {
        return notice(asked, filtered, List.of());
    }

    /**
     * 물은 값 중 거른 값에 없고 저장 값 종류(stored)에도 없는 것이 있고, 거른 값 중 물은 값에 없는 것이 있을 때만 안내 문장을 만든다. 아니면 null. 물은
     * 값이 저장돼 있으면("가이드 마모"가 사유 값) 다른 조건으로 찾고 행에서 읽은 것이라 "저장된 값은 없어"가 틀린 말이 된다(2026-11-16 리뷰).
     */
    static String notice(List<String> asked, List<String> filtered, List<String> stored) {
        return noticeByProperty(asked, filtered == null ? null : List.of(filtered), stored);
    }

    /**
     * 거른 값을 (유형, 속성)별로 받아, 물은 값과 다른 거른 값이 한 속성에서만 나왔을 때만 알린다. "정지 중인 설비의 비가동"에서 설비를 상태 "정비중"으로,
     * 비가동을 설비 코드 "P103"으로 거르면 두 속성에서 다른 값이 나와 어느 쪽이 물은 상태를 대신했는지 알 수 없다(2026-11-16 리뷰). 설비가 20대 이하면
     * 설비 코드에도 값 종류가 와서, 값 종류를 아는 속성만 모으는 것으로는 막지 못한다.
     */
    static String noticeByProperty(
            List<String> asked, List<List<String>> filteredByProperty, List<String> stored) {
        if (asked == null || asked.isEmpty() || filteredByProperty == null) {
            return null;
        }
        List<String> filtered = filteredByProperty.stream().flatMap(List::stream).toList();
        if (filtered.isEmpty()) {
            return null;
        }
        List<String> known = stored == null ? List.of() : stored;
        List<String> missing =
                asked.stream()
                        .filter(value -> !matchesAny(value, filtered))
                        .filter(value -> !matchesAny(value, known))
                        .toList();
        List<List<String>> differing =
                filteredByProperty.stream()
                        .map(
                                values ->
                                        values.stream()
                                                .filter(value -> !matchesAny(value, asked))
                                                .toList())
                        .filter(values -> !values.isEmpty())
                        .toList();
        if (missing.isEmpty() || differing.size() != 1) {
            return null;
        }
        List<String> instead = differing.getFirst();
        return quoted(missing)
                + withRo(missing)
                + " 저장된 값은 없어 "
                + quoted(instead)
                + withRo(instead)
                + " 찾았어요. 다른 값을 찾으시면 말씀해 주세요.";
    }

    private static boolean matchesAny(String value, List<String> others) {
        String key = normalized(value);
        return others.stream().anyMatch(other -> normalized(other).equals(key));
    }

    static String normalized(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (text.length() > suffix.length() && text.endsWith(suffix)) {
                    text = text.substring(0, text.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return text;
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", "));
    }

    // 마지막 글자에 받침이 있으면 "으로", 없거나 ㄹ 받침이면 "로". 한글이 아니면 둘 다 적는다.
    static String withRo(List<String> values) {
        String last = values.get(values.size() - 1).strip();
        char end = last.isEmpty() ? ' ' : last.charAt(last.length() - 1);
        if (end < '가' || end > '힣') {
            return "(으)로";
        }
        int finalConsonant = (end - '가') % 28;
        return finalConsonant == 0 || finalConsonant == 8 ? "로" : "으로";
    }
}

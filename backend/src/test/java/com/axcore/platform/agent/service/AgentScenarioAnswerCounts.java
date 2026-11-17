package com.axcore.platform.agent.service;

/** GMS 답변에서 "N건 중 M건"의 M을 우선하고, 없으면 처음 쓴 "N건"의 N을 읽는다. */
final class AgentScenarioAnswerCounts {

    private AgentScenarioAnswerCounts() {}

    static String listedCount(String answer) {
        String firstCount = null;
        int previousCountEnd = -1;
        int index = 0;
        // 숫자 묶음을 한 번씩만 읽어 긴 숫자나 일치하지 않는 접미사도 선형 시간에 처리한다.
        while (index < answer.length()) {
            if (!isDigit(answer.charAt(index))) {
                index++;
                continue;
            }
            int start = index;
            do {
                index++;
            } while (index < answer.length() && isDigit(answer.charAt(index)));

            int countSuffix = skipSpace(answer, index);
            if (!answer.startsWith("건", countSuffix)) {
                continue;
            }
            if (followsPartialCount(answer, previousCountEnd, start)) {
                return answer.substring(start, index);
            }
            if (firstCount == null) {
                firstCount = answer.substring(start, index);
            }
            previousCountEnd = countSuffix + 1;
        }
        return firstCount;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean followsPartialCount(String answer, int previousCountEnd, int start) {
        if (previousCountEnd < 0) {
            return false;
        }
        int separator = skipSpace(answer, previousCountEnd);
        return answer.startsWith("중", separator) && skipSpace(answer, separator + 1) == start;
    }

    private static int skipSpace(String answer, int index) {
        return index < answer.length() && answer.charAt(index) == ' ' ? index + 1 : index;
    }
}

package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentScenarioAnswerCountsTests {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "전체 10건 중 5건입니다.|5",
                "전체 10건중5건입니다.|5",
                "전체 10 건 중 5 건입니다.|5",
                "10건 중5건|5",
                "10건중 5건|5",
                "앞서 2건, 전체 10건 중 5건을 나열합니다.|5",
                "10건 중 5건, 20건 중 3건|5",
                "10건 중 5건 중 2건|5",
                "2026년 10월 비가동 12건, 추가 3건|12",
                "비가동 0건입니다.|0",
                "비가동 005건입니다.|005",
                "10건 중 데이터 5건|10",
                "10건 중 2개, 5건|10",
                "10건  중 5건|10",
                "10건 중  5건|10",
                "10건 중 5  건|10"
            })
    void preservesPartialCountPriorityAndFirstCountFallback(String answer, String expected) {
        assertThat(AgentScenarioAnswerCounts.listedCount(answer)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "비가동 이력이 없습니다.", "DT-0422", "123", "5  건", "5\t건", "５건"})
    void returnsNullWhenNoCountIsWritten(String answer) {
        assertThat(AgentScenarioAnswerCounts.listedCount(answer)).isNull();
    }

    @Test
    void scansLongUnmatchedDigitRunsWithoutRetryingEachStartingPosition() {
        String digits = "9".repeat(100_000);
        String answer = digits + "개, " + digits + " 건 중 " + digits + "개, 5건";

        assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThat(AgentScenarioAnswerCounts.listedCount(answer)).isEqualTo(digits));
    }
}

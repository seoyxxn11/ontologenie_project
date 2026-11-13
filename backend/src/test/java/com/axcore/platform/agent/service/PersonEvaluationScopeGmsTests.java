package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 개인 평가·감시 가드레일(384-④)을 실제 GMS 질문 해석으로 확인한다. AXCORE_AGENT_LLM=openai와 키를 넣었을 때만 돈다. 질문만 해석하고 조회·답변은
 * 하지 않는다. 막을 질문과 통과시킬 질문을 함께 물어, 사람 단위 평가는 막고 기록 단위 담당자 찾기와 설비 단위 집계는 막지 않는지 본다.
 */
@Slf4j
@SpringBootTest(properties = "test.context=gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class PersonEvaluationScopeGmsTests {
    @Autowired QuestionInterpreter interpreter;

    @Value("${spring.ai.openai.chat.model}")
    String model;

    @Test
    void blocksPersonEvaluationButKeepsRecordLevelQuestions() {
        List<String> wrong = new ArrayList<>();
        PersonEvaluationExamples.BLOCKED.forEach(
                question -> check(question, QuestionScope.PERSON_EVALUATION, wrong));
        PersonEvaluationExamples.ALLOWED.forEach(
                question -> check(question, QuestionScope.BUSINESS, wrong));
        log.info("GMS 개인 평가 가드레일 모델: {}, 틀림: {}", model, wrong);
        assertThat(wrong).isEmpty();
    }

    private void check(String question, QuestionScope expected, List<String> wrong) {
        QuestionScope scope =
                interpreter.interpret(question, Map.of(), null, null, List.of(), null).scope();
        log.info("GMS 개인 평가 가드레일: 질문={}, 범위={}", question, scope);
        if (scope != expected) {
            wrong.add(question + " → " + scope + " (기대 " + expected + ")");
        }
    }
}

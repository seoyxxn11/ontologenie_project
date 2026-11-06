package com.axcore.platform.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 085로 접수한 질문에 답한다. 커밋 뒤 다른 스레드에서 처리해 AI 호출이 길어도 085 응답을 붙잡지 않는다. 서버가 처리 중에 멈추면 남은 답변은 5분 뒤 정리된다(정책
 * 문서 3.4).
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AgentAnswerProcessor {
    private final AgentAnswerService answers;
    private final QuestionInterpreter interpreter;
    private final TaskExecutor applicationTaskExecutor;

    @TransactionalEventListener
    public void onRequested(AgentAnswerRequested event) {
        try {
            applicationTaskExecutor.execute(() -> answer(event));
        } catch (RuntimeException ex) {
            log.error("Agent 답변 {}을 처리 대기열에 넣지 못했습니다. 처리 중으로 남습니다.", event.answerId(), ex);
        }
    }

    private void answer(AgentAnswerRequested event) {
        long answerId = event.answerId();
        try {
            if (!interpreter.isConfigured()) {
                answers.fail(
                        answerId,
                        AgentAnswerService.NOT_CONFIGURED_CONTENT,
                        AgentAnswerService.NOT_CONFIGURED_REASON);
                return;
            }
            answers.answerUnderstood(
                    answerId, interpreter.interpret(event.question(), event.context()));
        } catch (RuntimeException ex) {
            log.error("Agent 답변 {}의 AI 처리가 실패했습니다.", answerId, ex);
            try {
                answers.fail(
                        answerId,
                        AgentAnswerService.FAILED_CONTENT,
                        AgentAnswerService.FAILED_REASON);
            } catch (RuntimeException saveEx) {
                log.error("Agent 답변 {}을 실패로 저장하지 못했습니다. 처리 중으로 남습니다.", answerId, saveEx);
            }
        }
    }
}

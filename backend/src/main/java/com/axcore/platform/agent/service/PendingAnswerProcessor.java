package com.axcore.platform.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 085로 접수한 질문의 답변을 처리한다. AI 답변(기능 흐름 3단계) 전까지는 바로 "준비 중" 안내로 끝내는 임시 처리다. 커밋 뒤에 실행되므로 여기서 난 오류를 085
 * 응답으로 올리지 않는다. 남은 처리 중 답변은 5분 뒤 정리된다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class PendingAnswerProcessor {
    private final AgentMessageService service;

    @TransactionalEventListener
    public void onRequested(AgentAnswerRequested event) {
        try {
            service.answerNotReady(event.answerId());
        } catch (RuntimeException ex) {
            log.error("Agent 답변 {}을 마무리하지 못했습니다. 처리 중으로 남습니다.", event.answerId(), ex);
        }
    }
}

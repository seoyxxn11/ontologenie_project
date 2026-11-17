package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 조회 도구 호출을 action_execution(AGENT_QUERY)에 남긴다(144, 정책 문서 3.1). 도구를 부를 때마다 서버가 남기므로 모델이 기록을
 * 빼먹을 수 없다. agentMessageId는 이 답변의 질문(USER) 메시지다(2026-11-05 결정). 관리자 실행 이력 화면에는 보이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AgentQueryRecorder {
    private final AuditRecorder audits;

    /** 답변 처리가 실패해도 조회 기록은 남도록 따로 저장한다. */
    public void record(
            LoginAccount asker,
            long questionId,
            int sequenceNo,
            Map<String, Object> input,
            String targetType,
            Long targetId,
            AgentQueryResult result,
            Instant requestedAt) {
        audits.recordSeparately(
                AuditEntry.builder()
                        .eventKind(EventKind.AGENT_QUERY)
                        .actor(Actor.agent(asker))
                        .entryPoint(EntryPoint.AGENT)
                        .status(
                                result.succeeded()
                                        ? ExecutionStatus.SUCCEEDED
                                        : ExecutionStatus.FAILED)
                        .targetType(targetType)
                        .targetId(targetId)
                        .input(input)
                        .result(
                                result.succeeded()
                                        ? Map.of("sources", result.sources())
                                        : failure(result))
                        .requestedAt(requestedAt)
                        .resultCategory(result.resultCategory())
                        .dataAsOf(result.dataAsOf())
                        .agentMessageId(questionId)
                        .toolCode(result.toolCode())
                        .sequenceNo(sequenceNo)
                        .build());
    }

    /**
     * 실패한 조회의 기록. 사용자에게 보이는 문구(errorMessage)와 함께, 나중에 왜 실패했는지 가릴 수 있게 까닭의 종류와 이유를 남긴다(449). 이유는
     * AI에게 돌려준 문장이나 서버 오류의 종류라, 모델에게 이미 보낸 것보다 더 드러내지 않는다.
     */
    static Map<String, Object> failure(AgentQueryResult result) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("errorMessage", result.errorMessage());
        if (result.failureKind() != null) {
            failure.put("failureKind", result.failureKind().name());
        }
        if (result.failureReason() != null) {
            failure.put("reason", result.failureReason());
        }
        return failure;
    }
}

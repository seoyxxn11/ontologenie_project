package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.domain.AnalysisRunCreated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 018 트랜잭션이 커밋된 뒤 AI에 분석 시작을 한 번 알린다. 알리지 못하면 실행을 FAILED로 바꿔 019에서 이유가 보이게 한다. 실행은 이미 접수됐으므로 여기서 난
 * 오류를 018 응답으로 올리지 않는다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AnalysisStartNotifier {
    private final AnalysisStartService service;
    private final AiAnalysisClient client;

    @TransactionalEventListener
    public void onCreated(AnalysisRunCreated event) {
        if (!client.isConfigured()) {
            return;
        }
        long runId = event.runId();
        try {
            var request = service.prepare(runId);
            if (request.isPresent() && !client.start(request.get())) {
                log.info("분석 실행 {}은 AI에서 이미 실행 중이라 새로 시작하지 않았습니다.", runId);
            }
        } catch (AnalysisStartException ex) {
            fail(runId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("분석 실행 {} 시작 알림 중 예상하지 못한 오류가 났습니다.", runId, ex);
            fail(runId, AiAnalysisClient.FAILURE_MESSAGE);
        }
    }

    private void fail(long runId, String reason) {
        try {
            service.fail(runId, reason);
        } catch (RuntimeException ex) {
            log.error("분석 실행 {}을 FAILED로 바꾸지 못했습니다. QUEUED로 남습니다.", runId, ex);
        }
    }
}

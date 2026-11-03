package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.dto.AnalysisResultAck;
import com.axcore.platform.analysis.dto.AnalysisResultRequest;
import com.axcore.platform.analysis.repository.AnalysisRunRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.review.service.AiProposalService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisResultService {
    private final AnalysisRunRepository runs;
    private final AiProposalService proposals;
    private final AuditRecorder audits;
    private final Clock clock;

    @Transactional
    public AnalysisResultAck accept(long id, AnalysisResultRequest request) {
        validateBody(request);
        var run =
                runs.findForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "analysis_run")));
        String next = request.status();
        boolean allowed =
                switch (run.status()) {
                    case "QUEUED" -> next.equals("RUNNING") || next.equals("FAILED");
                    case "RUNNING" -> next.equals("SUCCEEDED") || next.equals("FAILED");
                    default -> false;
                };
        if (!allowed) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT,
                    "허용되지 않는 상태 전이입니다.",
                    Map.of("currentStatus", run.status(), "requestedStatus", next));
        }
        Instant now = Instant.now(clock);
        if (next.equals("RUNNING")) {
            runs.start(id, now);
            return new AnalysisResultAck(id, next, now, null, List.of());
        }
        List<Long> ids =
                next.equals("SUCCEEDED")
                        ? proposals.save(
                                id, request.proposals() == null ? List.of() : request.proposals())
                        : List.of();
        if (next.equals("SUCCEEDED")) {
            Map<String, Object> summary =
                    request.resultSummary() == null
                            ? Map.of("proposalCount", ids.size())
                            : request.resultSummary();
            runs.succeed(id, summary, now);
        } else {
            runs.fail(id, request.errorMessage(), now);
        }
        // FAILED 알림을 정상 반영한 경우도 상태 변경과 결과 기록을 함께 커밋한다.
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.ANALYSIS_RUN)
                        .actor(Actor.system())
                        .entryPoint(EntryPoint.INTERNAL)
                        .status(ExecutionStatus.valueOf(next))
                        .targetType("analysis_run")
                        .targetId(id)
                        .result(Map.of("proposalCount", ids.size()))
                        .build());
        return new AnalysisResultAck(id, next, run.startedAt(), now, ids);
    }

    private static void validateBody(AnalysisResultRequest request) {
        if ("FAILED".equals(request.status())) {
            if (request.errorMessage() == null || request.errorMessage().isBlank()) {
                throw BusinessException.invalidField("errorMessage", "FAILED이면 실패 사유가 필요합니다.");
            }
        } else if (request.errorMessage() != null) {
            throw BusinessException.invalidField("errorMessage", "FAILED일 때만 보낼 수 있습니다.");
        }
        if (!"SUCCEEDED".equals(request.status())) {
            if (request.proposals() != null) {
                throw BusinessException.invalidField("proposals", "SUCCEEDED일 때만 보낼 수 있습니다.");
            }
            if (request.resultSummary() != null) {
                throw BusinessException.invalidField("resultSummary", "SUCCEEDED일 때만 보낼 수 있습니다.");
            }
        }
    }
}

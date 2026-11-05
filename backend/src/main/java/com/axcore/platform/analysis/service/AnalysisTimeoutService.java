package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.repository.AnalysisRunRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끝나지 않고 남은 분석 실행을 시간 초과로 FAILED 처리한다(S15P31C106-273). 105 완료 알림이 오지 않으면 QUEUED·RUNNING으로 남아 같은 대상의
 * 새 요청이 그 실행에 합류하므로(uq_analysis_run_active) 다시 분석할 수 없다. 정리 뒤 늦게 온 105는 기존 규칙대로 409다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AnalysisTimeoutProperties.class)
public class AnalysisTimeoutService {

    private final AnalysisRunRepository runs;
    private final AuditRecorder audits;
    private final AnalysisTimeoutProperties properties;
    private final Clock clock;

    /** 정리한 실행 ID. 실행마다 감사 기록(ANALYSIS_RUN FAILED)을 같은 트랜잭션에 남긴다. */
    @Transactional
    public List<Long> failStaleRuns() {
        Instant now = Instant.now(clock);
        List<Long> failed = new ArrayList<>();
        failed.addAll(
                record(
                        runs.failQueuedBefore(
                                now.minus(properties.queued()),
                                "분석이 %s 안에 시작되지 않아 시간 초과로 중단했습니다."
                                        .formatted(label(properties.queued())),
                                now),
                        "QUEUED"));
        failed.addAll(
                record(
                        runs.failRunningBefore(
                                now.minus(properties.running()),
                                "분석이 시작 후 %s 안에 끝나지 않아 시간 초과로 중단했습니다."
                                        .formatted(label(properties.running())),
                                now),
                        "RUNNING"));
        return failed;
    }

    private List<Long> record(List<Long> runIds, String previousStatus) {
        for (Long runId : runIds) {
            audits.record(
                    AuditEntry.builder()
                            .eventKind(EventKind.ANALYSIS_RUN)
                            .actor(Actor.system())
                            .entryPoint(EntryPoint.BATCH)
                            .status(ExecutionStatus.FAILED)
                            .targetType("analysis_run")
                            .targetId(runId)
                            .result(Map.of("reason", "TIMEOUT", "previousStatus", previousStatus))
                            .build());
        }
        return runIds;
    }

    /** 사용자에게 보여 줄 시간. 분 단위로 떨어지면 "N분", 아니면 "N초". */
    static String label(Duration duration) {
        return duration.toSecondsPart() == 0 && duration.toMinutes() > 0
                ? duration.toMinutes() + "분"
                : duration.toSeconds() + "초";
    }
}

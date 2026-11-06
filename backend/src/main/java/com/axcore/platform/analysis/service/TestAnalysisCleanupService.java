package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.repository.TestAnalysisCleanupRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 테스트 분석 지우기(S15P31C106-307, 시연 전 없앨 임시 기능). 끝난 분석만, 제안이 어디에도 쓰이지 않았을 때만 지운다. */
@Service
@RequiredArgsConstructor
public class TestAnalysisCleanupService {

    private final TestAnalysisCleanupRepository repository;
    private final AuditRecorder audits;

    /** 끝난 테스트 분석을 모두 지운다. 제안이 이미 쓰였거나 검사가 실행 중인 분석은 건너뛴다. */
    @Transactional
    public Map<String, Object> deleteAll(LoginAccount account) {
        List<Long> deleted = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        int proposals = 0;
        for (long runId : repository.finishedRunIds()) {
            // 제안·후보 검사 행을 먼저 잠근다. 승인(결정)과 같은 순서로 잠가 동시에 눌러도 섞이지 않는다.
            repository.lockParts(runId);
            if (!repository.blockers(runId).isEmpty()) {
                skipped.add(runId);
                continue;
            }
            proposals += deleteRun(runId, account).get("proposals");
            deleted.add(runId);
        }
        return Map.of(
                "deletedRuns", deleted, "deletedProposals", proposals, "skippedRuns", skipped);
    }

    private Map<String, Integer> deleteRun(long runId, LoginAccount account) {
        Map<String, Integer> deleted = repository.delete(runId);
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.ANALYSIS_RUN)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType("analysis_run")
                        .targetId(runId)
                        .input(Map.of("action", "TEST_ANALYSIS_DELETE"))
                        .result(Map.copyOf(deleted))
                        .build());
        return deleted;
    }
}

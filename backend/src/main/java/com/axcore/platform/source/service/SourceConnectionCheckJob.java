package com.axcore.platform.source.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.repository.SourceCatalogReader;
import com.axcore.platform.source.repository.SourceCatalogReader.Catalog;
import com.axcore.platform.source.repository.SourceCatalogRepository;
import com.axcore.platform.source.repository.SourceCatalogRepository.CheckTarget;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 연결 검사 작업(source_connection_check, API 013·100)을 배치에서 실행한다. 저장된 SYNC·AI 계정으로 각각 접속해 읽을 수 있는 표·컬럼을
 * 세고, SYNC 계정이 읽은 목록으로 카탈로그를 갱신한다.
 *
 * <ul>
 *   <li>연결 상태는 SYNC 계정 기준이다. SYNC가 성공하면 CONNECTED, 실패하면 FAILED이고 AI 계정 실패는 계정별 결과에만
 *       남긴다(2026-11-04).
 *   <li>계정 접속 실패는 검사 결과이므로 작업은 SUCCEEDED다. SYNC 계정이 없거나 접속 정보를 풀 수 없는 등 검사 자체를 못 하면 작업이 FAILED다.
 *   <li>PostgreSQL 원천만 검사한다. CSV·DOCUMENT는 지원하지 않는다는 사유로 FAILED다(2026-11-04).
 * </ul>
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class SourceConnectionCheckJob
        implements QueuedJobHandler<SourceConnectionCheckJob.Outcome> {

    private final SourceCatalogRepository catalogRepository;
    private final SourceCatalogReader catalogReader;
    private final SourceCredentialService credentialService;
    private final Clock clock;

    @Override
    public QueuedJobType type() {
        return QueuedJobType.SOURCE_CHECK;
    }

    @Override
    public Outcome run(long jobId) {
        CheckTarget target =
                catalogRepository
                        .findCheckTarget(jobId)
                        .orElseThrow(() -> new IllegalStateException("연결 검사 작업이 없습니다: " + jobId));
        if (target.sourceKind() != SourceKind.POSTGRES) {
            throw new QueuedJobFailure(
                    "연결 검사는 PostgreSQL 원천만 지원합니다. 이 원천의 종류는 %s입니다.".formatted(target.sourceKind()));
        }
        SourceEndpoint endpoint;
        try {
            endpoint = SourceEndpoint.from(target.connectionConfig());
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure(e.getMessage());
        }

        List<AccountCheck> accounts = new ArrayList<>();
        Catalog syncCatalog = null;
        for (CredentialPurpose purpose : CredentialPurpose.values()) {
            Optional<SourceAccount> account = findAccount(target.dataSourceId(), purpose);
            if (account.isEmpty()) {
                if (purpose == CredentialPurpose.SYNC) {
                    throw new QueuedJobFailure("SYNC 계정이 등록되지 않아 연결을 검사할 수 없습니다.");
                }
                continue;
            }
            try {
                Catalog catalog = catalogReader.read(endpoint, account.get());
                accounts.add(AccountCheck.succeeded(purpose, catalog));
                if (purpose == CredentialPurpose.SYNC) {
                    syncCatalog = catalog;
                }
            } catch (SQLException e) {
                log.warn(
                        "원천 계정 접속 검사 실패 dataSourceId={} purpose={} sqlState={}",
                        target.dataSourceId(),
                        purpose,
                        e.getSQLState());
                accounts.add(AccountCheck.failed(purpose, failureMessage(e)));
            }
        }
        return new Outcome(target, syncCatalog, List.copyOf(accounts));
    }

    @Override
    public void complete(long jobId, Outcome outcome) {
        long dataSourceId = outcome.target().dataSourceId();
        if (!outcome.target().stamp().equals(catalogRepository.lockConnectionStamp(dataSourceId))) {
            throw new QueuedJobFailure("검사하는 동안 원천 접속 정보가 바뀌었습니다. 다시 검사해 주세요.");
        }
        Instant now = Instant.now(clock);
        if (outcome.syncCatalog() != null) {
            catalogRepository.saveCatalog(dataSourceId, outcome.syncCatalog(), now);
        }
        catalogRepository.markConnection(dataSourceId, outcome.connectionStatus(), now);
        catalogRepository.finishCheck(
                jobId,
                Map.of(
                        "accounts",
                        outcome.accounts().stream().map(AccountCheck::toResult).toList()),
                now);
    }

    @Override
    public Map<String, Object> auditResult(Outcome outcome) {
        return Map.of(
                "connectionStatus",
                outcome.connectionStatus().name(),
                "accounts",
                outcome.accounts().stream().map(AccountCheck::toResult).toList());
    }

    private Optional<SourceAccount> findAccount(long dataSourceId, CredentialPurpose purpose) {
        try {
            return credentialService.findAccount(dataSourceId, purpose);
        } catch (SourceCredentialException e) {
            throw new QueuedJobFailure("원천 접속 정보를 풀지 못했습니다. 서버의 암호화 키 설정을 확인해 주세요.", e);
        }
    }

    // 드라이버 메시지에는 주소·계정명이 들어갈 수 있어 화면에는 SQLSTATE로 고른 문구만 남긴다.
    private static String failureMessage(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.equals("28P01") || state.equals("28000")) {
            return "계정 인증에 실패했습니다. 계정 이름과 비밀번호를 확인해 주세요.";
        }
        if (state.startsWith("08")) {
            return "원천 DB에 접속하지 못했습니다. 주소·포트·네트워크와 SSL 설정을 확인해 주세요.";
        }
        if (state.equals("3D000")) {
            return "원천 데이터베이스를 찾을 수 없습니다. 접속 설정의 database를 확인해 주세요.";
        }
        if (state.equals("57014")) {
            return "원천 DB 응답 시간이 초과되었습니다.";
        }
        return state.isEmpty()
                ? "원천 DB를 검사하지 못했습니다."
                : "원천 DB를 검사하지 못했습니다(SQLSTATE %s).".formatted(state);
    }

    /** run이 원천에서 읽은 결과. syncCatalog는 SYNC 계정이 성공했을 때만 있다. */
    public record Outcome(CheckTarget target, Catalog syncCatalog, List<AccountCheck> accounts) {

        ConnectionStatus connectionStatus() {
            return syncCatalog == null ? ConnectionStatus.FAILED : ConnectionStatus.CONNECTED;
        }
    }

    /** 계정 하나의 검사 결과. API 100의 result.accounts[] 한 칸이 된다. */
    public record AccountCheck(
            CredentialPurpose purpose,
            boolean succeeded,
            Integer readableTableCount,
            Integer readableColumnCount,
            String errorMessage) {

        static AccountCheck succeeded(CredentialPurpose purpose, Catalog catalog) {
            return new AccountCheck(
                    purpose, true, catalog.resources().size(), catalog.columnCount(), null);
        }

        static AccountCheck failed(CredentialPurpose purpose, String errorMessage) {
            return new AccountCheck(purpose, false, null, null, errorMessage);
        }

        Map<String, Object> toResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("purpose", purpose.name());
            result.put("status", succeeded ? "SUCCEEDED" : "FAILED");
            result.put("readableTableCount", readableTableCount);
            result.put("readableColumnCount", readableColumnCount);
            result.put("errorMessage", errorMessage);
            return result;
        }
    }
}

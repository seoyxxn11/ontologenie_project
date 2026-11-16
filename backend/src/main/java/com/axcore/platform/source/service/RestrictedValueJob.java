package com.axcore.platform.source.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.ValueSummary;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 제안 검토 때 제한 컬럼의 값 확인(S15P31C106-447)을 배치에서 실행한다. 원천 표본(016)과 같은 작업 표(source_preview)·SYNC
 * 계정·{@link SourceTableReader}를 쓴다.
 *
 * <ul>
 *   <li>한 컬럼의 서로 다른 값 row_limit개와 전체·빈 값·서로 다른 값의 수만 읽는다. 키 컬럼과 같은 행의 다른 컬럼은 읽지 않는다.
 *   <li>실행 때 원천 데이터 정책을 다시 본다. 접수 뒤 정책이 바뀌어 그 컬럼이 제한이 아니게 되거나 금지가 되었으면 읽지 않는다(일반 컬럼은 원천 표본으로 본다).
 *   <li>값은 결과 칸에만 두고 로그·감사 기록에는 남기지 않는다. 결과는 요청한 관리자가 읽으면 지운다(API 쪽).
 * </ul>
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class RestrictedValueJob implements QueuedJobHandler<RestrictedValueJob.Result> {

    private final SourcePreviewRepository previews;
    private final SourceResourceRepository resources;
    private final DataSourceRepository sources;
    private final SourceCredentialService credentials;
    private final SourceTableReader reader;
    private final SourceColumnPolicy sourceColumnPolicy;
    private final RestrictedValueTargets targets;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public QueuedJobType type() {
        return QueuedJobType.SOURCE_PREVIEW;
    }

    @Override
    public String claimCondition() {
        return "preview_kind = 'RESTRICTED_VALUES'";
    }

    @Override
    public Result run(long jobId) {
        var preview =
                previews.findById(jobId)
                        .filter(
                                row ->
                                        SourcePreviewRepository.RESTRICTED_VALUES.equals(
                                                row.previewKind()))
                        .filter(row -> row.expiresAt().isAfter(Instant.now(clock)))
                        .orElseThrow(() -> new QueuedJobFailure("값 확인 작업이 없거나 만료되었습니다."));
        JsonNode request = request(preview.request());
        String column = request.path("column").asText("");
        if (column.isBlank() || !request.path("revision").canConvertToInt()) {
            throw new QueuedJobFailure("값 확인 요청을 읽지 못했습니다.");
        }
        // 접수 뒤 제안이 승인·거절·개정되었으면 원천을 읽지 않는다(447 검토).
        if (!targets.stillEligible(
                request.path("proposalId").asLong(),
                request.path("revision").asInt(),
                preview.sourceResourceId(),
                column)) {
            throw new QueuedJobFailure("제안이 승인·거절되었거나 개정되어 값 확인이 더는 유효하지 않습니다.");
        }
        var resource =
                resources
                        .findById(preview.sourceResourceId())
                        .filter(SourceSampleRequestService::isReadableTable)
                        .orElseThrow(() -> new QueuedJobFailure("읽을 수 있는 원천 테이블을 찾을 수 없습니다."));
        var source =
                sources.findById(resource.getDataSourceId())
                        .filter(row -> row.getSourceKind() == SourceKind.POSTGRES)
                        .orElseThrow(() -> new QueuedJobFailure("PostgreSQL 원천을 찾을 수 없습니다."));
        String resourceKey = resource.getResourceKey();
        if (sourceColumnPolicy.forbidsTable(resourceKey)
                || sourceColumnPolicy.gradeOf(resourceKey, column)
                        != SourceColumnGrade.RESTRICTED) {
            throw new QueuedJobFailure("원천 데이터 정책상 값 확인 대상(제한 컬럼)이 아닙니다.");
        }
        SourceEndpoint endpoint;
        try {
            endpoint = SourceEndpoint.from(source.getConnectionConfig());
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure("원천 접속 설정을 확인해 주세요.");
        }
        SourceAccount account = syncAccount(resource.getDataSourceId());
        try {
            ValueSummary summary =
                    reader.distinctValues(
                            endpoint, account, resourceKey, column, preview.rowLimit());
            return new Result(
                    summary.values(),
                    summary.totalCount(),
                    summary.emptyCount(),
                    summary.distinctCount());
        } catch (SQLException e) {
            log.warn("제한 값 확인 읽기 실패 previewId={} sqlState={}", jobId, e.getSQLState());
            throw new QueuedJobFailure(SourceReadFailure.message(e));
        } catch (RuntimeException e) {
            log.warn(
                    "제한 값 확인 변환 실패 previewId={} exception={}", jobId, e.getClass().getSimpleName());
            throw new QueuedJobFailure("원천 값을 처리하지 못했습니다.");
        }
    }

    @Override
    public void complete(long jobId, Result result) {
        String payload;
        try {
            payload = json.writeValueAsString(result);
        } catch (RuntimeException e) {
            throw new QueuedJobFailure("값 확인 결과를 처리하지 못했습니다.");
        }
        try {
            if (!previews.finish(jobId, payload, Instant.now(clock))) {
                throw new QueuedJobFailure("값 확인 작업이 이미 종료되었거나 정리되었습니다.");
            }
        } catch (DataAccessException e) {
            log.warn(
                    "제한 값 확인 결과 저장 실패 previewId={} exception={}",
                    jobId,
                    e.getClass().getSimpleName());
            throw new QueuedJobFailure("값 확인 결과를 저장하지 못했습니다.");
        }
    }

    /** 감사 기록에는 건수만 남긴다. 값은 남기지 않는다. */
    @Override
    public Map<String, Object> auditResult(Result result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("valueCount", result.values().size());
        summary.put("totalCount", result.totalCount());
        return summary;
    }

    private JsonNode request(String requestJson) {
        try {
            return json.readTree(requestJson);
        } catch (RuntimeException e) {
            throw new QueuedJobFailure("값 확인 요청을 읽지 못했습니다.");
        }
    }

    private SourceAccount syncAccount(long sourceId) {
        try {
            return credentials
                    .findAccount(sourceId, CredentialPurpose.SYNC)
                    .orElseThrow(() -> new QueuedJobFailure("원천 SYNC 계정이 등록되지 않았습니다."));
        } catch (SourceCredentialException e) {
            throw new QueuedJobFailure("원천 SYNC 계정의 접속 정보를 읽을 수 없습니다.");
        }
    }

    /**
     * @param values 서로 다른 값(이름순)
     * @param totalCount 전체 행 수
     * @param emptyCount 빈 값 행 수
     * @param distinctCount 서로 다른 값의 수
     */
    public record Result(
            java.util.List<String> values, long totalCount, long emptyCount, long distinctCount) {}
}

package com.axcore.platform.review.service;

import com.axcore.platform.action.repository.ActionReferenceRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ProposalOption;
import com.axcore.platform.review.domain.ReviewErrorCode;
import com.axcore.platform.review.dto.RestrictedColumnResponse;
import com.axcore.platform.review.dto.RestrictedValueAcceptedResponse;
import com.axcore.platform.review.dto.RestrictedValueRequest;
import com.axcore.platform.review.dto.RestrictedValueResultResponse;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository.Preview;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.service.RestrictedValueTargets;
import com.axcore.platform.source.service.SourceSampleRequestService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 제안 검토 때 제한 컬럼의 실제 값 확인(S15P31C106-447, 384 보완).
 *
 * <p>제한(민감) 컬럼은 AI에 값 없이 이름·자료형·주석만 넘어가고(416) 제안 근거에도 값이 남지 않는다(399). 그래서 승인하는 관리자가 AI의 추측만 보고
 * 승인했다. 이 서비스는 검토 중인 제안이 가리키는 제한 컬럼에 한해, 그 컬럼의 서로 다른 값 몇 개와 건수를 행과 떼어서 보여 준다.
 *
 * <ul>
 *   <li>부를 수 있는 사람: 제안 결정 권한(PROPOSAL_DECIDE)이 있는 역할. 화면은 관리자 전용 API에서 부른다.
 *   <li>대상: 그 제안(지금 개정본과 선택지)의 payload·근거가 가리키는 원천 컬럼 중 원천 데이터 정책상 제한 등급인 것. 금지·일반 컬럼은 받지 않는다(일반
 *       컬럼은 원천 표본으로 본다).
 *   <li>결정 전(대기·보류) 제안만 받는다. 승인·거절된 제안은 검토가 끝났다. 요청에 개정본 번호를 남기고, 배치가 읽기 직전과 결과를 돌려주기 직전에 제안
 *       상태·개정본·컬럼 대상 여부를 다시 본다({@link #stillEligible}). 바뀌었으면 값을 읽거나 내보내지 않는다.
 *   <li>원천은 배치가 읽는다({@code RestrictedValueJob}). 결과는 요청한 관리자만 읽고, 한 번 읽으면 열람 기록(394)을 남긴 뒤 지운다.
 *   <li>업무 데이터 화면과 Agent의 가림은 바꾸지 않는다. 관리자는 평소에는 제한 값을 보지 못한다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RestrictedValueCheckService implements RestrictedValueTargets {

    static final String PERMISSION = "PROPOSAL_DECIDE";

    /** 보여 줄 서로 다른 값의 수. */
    static final int VALUE_LIMIT = 5;

    /** 열람 기록의 경로 이름. */
    static final String VIEW = "PROPOSAL_RESTRICTED_VALUES";

    /** 요청 뒤 제안이 승인·거절·개정되었거나 컬럼이 더는 확인 대상이 아닐 때 결과 대신 돌려주는 이유. */
    static final String INVALIDATED =
            "제안이 승인·거절되었거나 개정되어, 또는 그 컬럼이 더는 확인 대상이 아니어서 이 확인은 무효가 되었습니다. 필요하면 다시 확인해 주세요.";

    private final ProposalItemRepository items;
    private final ProposalOptionRepository options;
    private final SourceResourceRepository resources;
    private final SourceColumnRepository columns;
    private final DataSourceRepository sources;
    private final SourceColumnPolicy policy;
    private final SourcePreviewRepository previews;
    private final ActionReferenceRepository permissions;
    private final AuditRecorder audits;
    private final RestrictedViewRecorder restrictedViews;
    private final JsonMapper json;
    private final Clock clock;

    /** 제안이 가리키는 제한 컬럼 목록. 값은 담지 않는다. */
    @Transactional(readOnly = true)
    public List<RestrictedColumnResponse> columns(long proposalId, LoginAccount account) {
        requirePermission(account);
        return restrictedColumns(find(proposalId));
    }

    /** 값 확인을 접수한다. 같은 관리자의 같은 요청이 대기·실행 중이면 그 작업에 합친다. */
    @Transactional
    public RestrictedValueAcceptedResponse request(
            long proposalId, RestrictedValueRequest request, LoginAccount account) {
        requirePermission(account);
        ProposalItem item = find(proposalId);
        if ("APPROVED".equals(item.getReviewStatus())
                || "REJECTED".equals(item.getReviewStatus())) {
            throw new BusinessException(
                    ReviewErrorCode.PROPOSAL_ALREADY_DECIDED,
                    Map.of("proposalId", proposalId, "reviewStatus", item.getReviewStatus()));
        }
        RestrictedColumnResponse target =
                restrictedColumns(item).stream()
                        .filter(column -> column.sourceResourceId() == request.sourceResourceId())
                        .filter(column -> column.column().equalsIgnoreCase(request.column().trim()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        BusinessException.invalidField(
                                                "column", "이 제안이 가리키는 제한 컬럼만 값을 확인할 수 있습니다."));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("proposalId", proposalId);
        requestBody.put("revision", item.getRevision());
        requestBody.put("column", target.column());
        String requestJson = json.writeValueAsString(requestBody);
        long resourceId = target.sourceResourceId();
        previews.lockRequester(account.id(), resourceId);
        Instant now = Instant.now(clock);
        var active =
                previews.findActiveRestrictedValues(
                        resourceId, account.id(), requestJson, VALUE_LIMIT, now);
        boolean joined = active.isPresent();
        var accepted =
                active.orElseGet(
                        () ->
                                previews.insertRestrictedValues(
                                        resourceId, account.id(), requestJson, VALUE_LIMIT, now));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("previewKind", SourcePreviewRepository.RESTRICTED_VALUES);
        input.put("proposalId", proposalId);
        input.put("sourceResourceId", resourceId);
        input.put("column", target.column());
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.SOURCE_PREVIEW)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode(PERMISSION)
                        .targetType(QueuedJobType.SOURCE_PREVIEW.table())
                        .targetId(accepted.id())
                        .input(input)
                        .result(Map.of("joined", joined, "status", accepted.status()))
                        .build());
        return new RestrictedValueAcceptedResponse(
                accepted.id(),
                accepted.status(),
                joined,
                accepted.requestedAt(),
                accepted.expiresAt());
    }

    /** 결과를 돌려준다. 요청한 관리자만 읽는다. 끝났으면 값을 돌려주면서 열람 기록을 남기고 결과를 지운다. 다시 보려면 다시 요청한다(기록도 다시 남는다). */
    @Transactional
    public RestrictedValueResultResponse result(
            long proposalId, long requestId, LoginAccount account) {
        requirePermission(account);
        Preview preview =
                previews.findById(requestId)
                        .filter(
                                row ->
                                        SourcePreviewRepository.RESTRICTED_VALUES.equals(
                                                row.previewKind()))
                        .filter(row -> row.requestedBy() == account.id())
                        .filter(row -> row.expiresAt().isAfter(Instant.now(clock)))
                        .orElseThrow(() -> notFound(requestId));
        JsonNode request = json.readTree(preview.request());
        if (request.path("proposalId").asLong() != proposalId) {
            throw notFound(requestId);
        }
        String column = request.path("column").asText();
        // 요청한 뒤 제안이 승인·거절·개정되었거나, 정책이 바뀌어 그 컬럼이 확인 대상이 아니면 결과를 내보내지 않고 지운다(447 검토).
        if (!request.path("revision").canConvertToInt()
                || !stillEligible(
                        proposalId,
                        request.path("revision").asInt(),
                        preview.sourceResourceId(),
                        column)) {
            previews.delete(preview.id());
            return new RestrictedValueResultResponse(
                    preview.id(),
                    preview.sourceResourceId(),
                    column,
                    "FAILED",
                    null,
                    null,
                    null,
                    null,
                    INVALIDATED);
        }
        if (!"SUCCEEDED".equals(preview.status())) {
            return new RestrictedValueResultResponse(
                    preview.id(),
                    preview.sourceResourceId(),
                    column,
                    preview.status(),
                    null,
                    null,
                    null,
                    null,
                    "FAILED".equals(preview.status()) ? preview.errorMessage() : null);
        }
        JsonNode result = json.readTree(preview.result());
        List<String> values = new ArrayList<>();
        result.path("values").forEach(value -> values.add(value.asText()));
        // 값을 내보내기 전에 기록한다. 기록이 실패하면 값도 나가지 않는다(394).
        if (!values.isEmpty()) {
            restrictedViews.record(
                    account, VIEW, List.of(Seen.sourceColumn(preview.sourceResourceId(), column)));
        }
        previews.delete(preview.id());
        return new RestrictedValueResultResponse(
                preview.id(),
                preview.sourceResourceId(),
                column,
                preview.status(),
                List.copyOf(values),
                result.path("totalCount").asLong(),
                result.path("emptyCount").asLong(),
                result.path("distinctCount").asLong(),
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean stillEligible(
            long proposalId, int revision, long sourceResourceId, String column) {
        return items.findById(proposalId)
                .filter(item -> !"APPROVED".equals(item.getReviewStatus()))
                .filter(item -> !"REJECTED".equals(item.getReviewStatus()))
                .filter(item -> item.getRevision() == revision)
                .map(
                        item ->
                                restrictedColumns(item).stream()
                                        .anyMatch(
                                                target ->
                                                        target.sourceResourceId()
                                                                        == sourceResourceId
                                                                && target.column()
                                                                        .equalsIgnoreCase(column)))
                .orElse(false);
    }

    private void requirePermission(LoginAccount account) {
        if (account == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!permissions.roleHasPermission(account.roleCode(), PERMISSION)) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", PERMISSION));
        }
    }

    private ProposalItem find(long proposalId) {
        return items.findById(proposalId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "proposal_item", "id", proposalId)));
    }

    private static BusinessException notFound(long requestId) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "restricted_value_check", "id", requestId));
    }

    /**
     * 제안(지금 개정본과 그 선택지)의 payload·근거가 가리키는 원천 컬럼 중 제한 등급인 것을, 카탈로그에 있고 읽을 수 있는 원천 테이블로 바꾼다. 같은 표·컬럼이
     * 여러 원천에 있으면 원천마다 하나씩이다.
     */
    private List<RestrictedColumnResponse> restrictedColumns(ProposalItem item) {
        Set<String[]> referenced = new LinkedHashSet<>();
        collect(item.getPayload(), referenced);
        collect(item.getEvidence(), referenced);
        for (ProposalOption option :
                options.findByProposalItemIdAndRevisionOrderByOptionNo(
                        item.getId(), item.getRevision())) {
            collect(option.getPayload(), referenced);
            collect(option.getEvidence(), referenced);
        }
        Map<String, RestrictedColumnResponse> found = new LinkedHashMap<>();
        for (String[] pair : referenced) {
            String resourceKey = pair[0];
            String column = pair[1];
            if (policy.forbidsTable(resourceKey)
                    || policy.gradeOf(resourceKey, column) != SourceColumnGrade.RESTRICTED) {
                continue;
            }
            for (SourceResource resource : resources.findByResourceKeyIgnoreCase(resourceKey)) {
                if (!SourceSampleRequestService.isReadableTable(resource)) {
                    continue;
                }
                Optional<String> catalogName = catalogColumn(resource.getId(), column);
                if (catalogName.isEmpty()) {
                    continue;
                }
                String key = resource.getId() + ":" + catalogName.get().toLowerCase(Locale.ROOT);
                found.computeIfAbsent(
                        key,
                        ignored ->
                                new RestrictedColumnResponse(
                                        resource.getId(),
                                        sources.findById(resource.getDataSourceId())
                                                .map(DataSource::getName)
                                                .orElse(null),
                                        resource.getResourceKey(),
                                        catalogName.get()));
            }
        }
        return List.copyOf(found.values());
    }

    private Optional<String> catalogColumn(long resourceId, String column) {
        return columns.findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(resourceId).stream()
                .filter(SourceColumn::isPresent)
                .map(SourceColumn::getColumnName)
                .filter(name -> name.equalsIgnoreCase(column))
                .findFirst();
    }

    /**
     * JSON 트리에서 원천 컬럼을 모은다. 근거 항목의 위치({@code 스키마.표.컬럼}, 묶은 컬럼 {@code a+b}는 각각)와 payload의 원천
     * 컬럼({@code resourceKey}와 {@code column})이다. 컬럼 이름에 마침표가 있을 수 있어 표 뒤는 더 쪼개지 않는다(399와 같다).
     */
    private static void collect(Object value, Set<String[]> found) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("locator") instanceof String locator) {
                String[] parts = locator.split("\\.", 3);
                if (parts.length == 3) {
                    String table = parts[0] + "." + parts[1];
                    for (String column : parts[2].split("\\+")) {
                        add(found, table, column);
                    }
                }
            }
            if (map.get("resourceKey") instanceof String resourceKey
                    && map.get("column") instanceof String column) {
                add(found, resourceKey, column);
            }
            map.values().forEach(child -> collect(child, found));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collect(child, found));
        }
    }

    private static void add(Set<String[]> found, String resourceKey, String column) {
        if (resourceKey.isBlank() || column.isBlank()) {
            return;
        }
        for (String[] existing : found) {
            if (existing[0].equalsIgnoreCase(resourceKey) && existing[1].equalsIgnoreCase(column)) {
                return;
            }
        }
        found.add(new String[] {resourceKey, column});
    }
}

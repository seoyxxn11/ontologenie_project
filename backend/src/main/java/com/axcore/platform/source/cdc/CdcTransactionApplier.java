package com.axcore.platform.source.cdc;

import com.axcore.platform.ontology.domain.SourceChange;
import com.axcore.platform.ontology.dto.LinkRecomputeResult;
import com.axcore.platform.ontology.service.LinkRecomputeService;
import com.axcore.platform.ontology.service.ObjectSyncService;
import com.axcore.platform.ontology.service.ObjectSyncService.DeleteResult;
import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import com.axcore.platform.source.cdc.TransactionIdRestorer.UncertainTransactionIdException;
import com.axcore.platform.source.domain.FullLoadPlan;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.LoadFailure;
import com.axcore.platform.source.domain.LoadFailure.Reason;
import com.axcore.platform.source.domain.RecoveryReason;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.CdcConsumerRepository;
import com.axcore.platform.source.repository.CdcConsumerRepository.OffsetState;
import com.axcore.platform.source.repository.CdcConsumerRepository.ResourceCounts;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceLoadFailureRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.service.DebeziumRowDecoder;
import com.axcore.platform.source.service.DebeziumRowDecoder.Decoded;
import com.axcore.platform.source.service.DebeziumRowDecoder.Field;
import com.axcore.platform.source.service.DebeziumRowDecoder.UnsupportedValueException;
import com.axcore.platform.source.service.FullLoadPlanner;
import com.axcore.platform.source.service.FullLoadPlanner.PlanAttempt;
import com.axcore.platform.source.service.SourceRowConverter;
import com.axcore.platform.source.service.SourceRowConverter.Conversion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 완성된 원천 트랜잭션 하나(198-2)를 플랫폼 트랜잭션 하나로 반영한다(Jira 198-5, 결정 문서 3.3·3.4·9.2·9.3·10.2·11장). 부르는 쪽(198-6
 * 소비 루프)은 원천 트랜잭션을 END까지 모은 뒤에 부른다. 그래서 플랫폼 트랜잭션의 {@code now()}가 원천 커밋보다 늦고(R4), 원천 트랜잭션의 중간 상태가
 * 커밋되지 않는다(R3).
 *
 * <ol>
 *   <li>원천의 소비 상태({@code cdc_offset})를 잠근다. 이미 반영한 트랜잭션(END가 다음 위치보다 앞)이면 아무것도 쓰지 않는다. 복구가 필요한 원천이면
 *       반영하지 않는다.
 *   <li>txId의 epoch를 복원한다(198-3). 확신할 수 없으면 복구 필요만 표시하고 위치는 옮기지 않는다.
 *   <li>변경을 원천 커밋 순서대로 객체에 쓴다(180). 값 해석은 매핑한 컬럼만 한다(198-1). 데이터 불량은 수집 실패 기록에 남기고 진행한다.
 *   <li>변경이 있으면 {@code sync_run}(CDC, SUCCEEDED) 하나와 테이블별 {@code sync_run_resource}(INCREMENTAL)를
 *       남긴다.
 *   <li>다음 위치(END + 1), 복원한 번호, 원천 커밋 시각, 처리 지연을 저장한다.
 *   <li>마지막에 쓰거나 삭제 표시한 객체의 연결을 다시 계산한다(181 {@link LinkRecomputeService#recomputeObjects}, 198-7,
 *       결정 문서 9.4). 재계산 서비스는 관계 유형 잠금을 트랜잭션 끝까지 쌓으므로 트랜잭션당 한 번, 객체를 다 쓴 뒤에만 부른다. issue는 만들지
 *       않는다(SYNC 검사).
 * </ol>
 *
 * <p>처리 장애(DB 예외 등)는 잡지 않는다. 트랜잭션 전체가 롤백되고 위치가 그대로라 부르는 쪽이 같은 트랜잭션을 다시 반영한다(9.3). 연결 재계산도 같은 트랜잭션이라
 * 객체와 연결이 갈라지지 않는다.
 *
 * <p>TRUNCATE, 유지할 기존 값이 없는 TOAST 값, 복구는 그 테이블을 다시 읽기 대상({@code loaded_lsn = NULL})으로 표시만 한다. 다시 읽기
 * 실행은 배치 감시 작업이 넣는다(11장 1·2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CdcTransactionApplier {

    private final CdcConsumerRepository consumerRepository;
    private final SourceLoadFailureRepository failureRepository;
    private final SourceResourceRepository resourceRepository;
    private final SourceColumnRepository columnRepository;
    private final FullLoadPlanner planner;
    private final DebeziumRowDecoder decoder;
    private final SourceRowConverter converter;
    private final ObjectSyncService objectSyncService;
    private final LinkRecomputeService linkRecomputeService;
    private final TransactionIdRestorer restorer = new TransactionIdRestorer();

    /**
     * @param initialReference 처음 시작해 저장된 기준값이 없을 때 쓸 epoch 복원 기준값(원천 {@code pg_current_snapshot()}
     *     xmax). 저장된 기준값이 있으면 쓰지 않으므로 null이어도 된다
     * @param receivedAt 이 트랜잭션의 END를 받은 시각(플랫폼 시계). 마지막 메시지 시각으로 남긴다
     */
    @Transactional
    public Result apply(
            CdcSource source, SourceTransaction tx, Long initialReference, Instant receivedAt) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(receivedAt, "receivedAt");
        OffsetState state = consumerRepository.lockOffset(source.dataSourceId(), source.topic());
        if (tx.endOffset() < state.nextOffset()) {
            return Result.notApplied(Status.ALREADY_APPLIED, null);
        }
        if (state.recoveryReason() != null) {
            return Result.notApplied(Status.RECOVERY_PENDING, state.recoveryReason());
        }
        long reference;
        if (state.lastTxid() != null) {
            reference = state.lastTxid();
        } else if (initialReference != null) {
            reference = initialReference;
        } else {
            throw new IllegalArgumentException("처음 반영하는 원천이라 epoch 기준값(원천 xmax)이 필요합니다.");
        }
        long txid;
        try {
            txid = restorer.restore(tx.txId(), reference);
        } catch (UncertainTransactionIdException e) {
            log.warn(
                    "원천 트랜잭션 번호를 복원하지 못해 복구로 돌림 dataSourceId={} txId={} reference={}",
                    source.dataSourceId(),
                    tx.txId(),
                    reference);
            markRecovery(source, RecoveryReason.EPOCH_UNCERTAIN);
            return Result.notApplied(Status.RECOVERY_NEEDED, RecoveryReason.EPOCH_UNCERTAIN);
        }

        Map<String, Table> tables = resolveTables(source.dataSourceId(), tx.events());
        Long runId = null;
        List<Long> changed = new ArrayList<>();
        int applied = 0;
        int skipped = 0;
        for (ChangeEvent event : tx.events()) {
            Table table = tables.get(event.resourceKey());
            if (table == null) {
                skipped++;
                continue;
            }
            if (runId == null) {
                runId = consumerRepository.insertCdcRun(source.dataSourceId());
            }
            SourceChange change = new SourceChange(txid, event.lsn(), runId);
            table.counts.event(event.lsn());
            switch (event.op()) {
                case CREATE, UPDATE -> applyUpsert(table, event, change, changed);
                case DELETE -> applyDelete(table, event, change, changed);
                case TRUNCATE -> requireReload(table);
            }
            applied++;
        }
        if (runId != null) {
            for (Table table : tables.values()) {
                if (table.counts.events > 0) {
                    consumerRepository.insertRunResource(
                            runId, source.dataSourceId(), table.counts.toRecord(table.resourceId));
                }
            }
        }
        consumerRepository.saveApplied(
                source.topic(),
                tx.nextOffset(),
                txid,
                tx.commitTimeMs() < 0 ? null : Instant.ofEpochMilli(tx.commitTimeMs()),
                receivedAt,
                processingLag(tx));
        List<Long> changedObjects = List.copyOf(new LinkedHashSet<>(changed));
        LinkRecomputeResult links =
                changedObjects.isEmpty()
                        ? LinkRecomputeResult.empty()
                        : linkRecomputeService.recomputeObjects(changedObjects);
        return new Result(Status.APPLIED, runId, changedObjects, links, applied, skipped, null);
    }

    /**
     * 원천의 CDC 반영을 이어 갈 수 없다고 표시한다: {@code recovery_reason}, {@code healthy_since = NULL}, 그 원천의 CDC
     * 테이블 모두 다시 읽기 대상. 위치는 옮기지 않는다. 198-6(트랜잭션 크기·메시지 순서 오류·보관 초과)도 부른다.
     */
    @Transactional
    public void markRecovery(CdcSource source, RecoveryReason reason) {
        consumerRepository.lockOffset(source.dataSourceId(), source.topic());
        consumerRepository.markRecovery(source.topic(), reason);
        resourceRepository.requireFullReloadOfTables(source.dataSourceId(), SyncStrategy.CDC);
    }

    /**
     * 이 트랜잭션의 변경이 걸린 표를 찾아 공유 잠금하고 매핑 계획을 만든다. 없는 표, CDC가 아닌 표, 켜진 매핑이 없는 표, 지금 적재할 수 없는 매핑(정책 위반,
     * 행 조건 등)이 있는 표는 빠진다. 적재할 수 없는 매핑은 전체 읽기도 실패하므로 같은 상태가 된다. 빠진 표는 다시 읽기를 표시해(402), 매핑을 고치면 다시
     * 읽기로 맞춘다. 표시는 TRUNCATE와 같이 공유 잠금 안에서 쓴다.
     */
    private Map<String, Table> resolveTables(long dataSourceId, List<ChangeEvent> events) {
        Map<Long, SourceResource> resources = new TreeMap<>();
        for (String key : events.stream().map(ChangeEvent::resourceKey).distinct().toList()) {
            resourceRepository
                    .findByDataSourceIdAndResourceKey(dataSourceId, key)
                    .filter(r -> r.getSyncStrategy() == SyncStrategy.CDC)
                    .ifPresent(r -> resources.put(r.getId(), r));
        }
        if (resources.isEmpty()) {
            return Map.of();
        }
        resourceRepository.lockAllShared(resources.keySet());
        Map<String, Table> tables = new LinkedHashMap<>();
        for (SourceResource resource : resources.values()) {
            PlanAttempt attempt = planner.tryPlan(resource.getId());
            if (attempt.blocked()) {
                // 건너뛴 변경을 잃지 않게 다시 읽기를 표시한다(402). 감시 작업이 한 번 넣어 사유를 수집 실패로 남기고, 매핑을 고치면 다시 읽어 맞춘다.
                if (resourceRepository.requireFullReload(resource.getId()) > 0) {
                    log.warn(
                            "CDC 이벤트를 반영할 수 없는 매핑이라 건너뛰고 다시 읽기를 표시함 resourceId={} reason={}",
                            resource.getId(),
                            attempt.failure());
                }
                continue;
            }
            FullLoadPlan plan = attempt.plan();
            if (plan.mappings().isEmpty()) {
                continue;
            }
            Map<String, String> sourceTypes = new LinkedHashMap<>();
            for (SourceColumn column :
                    columnRepository.findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(
                            resource.getId())) {
                sourceTypes.put(column.getColumnName(), column.getSourceDataType());
            }
            tables.put(
                    resource.getResourceKey(),
                    new Table(resource.getId(), plan, sourceTypes, Set.copyOf(plan.columns())));
        }
        return tables;
    }

    private void applyUpsert(
            Table table, ChangeEvent event, SourceChange change, List<Long> changed) {
        DecodedRow after = decode(table, event.schema(), event.after());
        if (event.before() != null) {
            // 키가 바뀐 수정: 옛 키 객체를 지운다(PK 변경은 보통 d + c로 오지만 before에 옛 키가 오는 경우 대비, 10.2)
            DecodedRow before = decode(table, event.schema(), event.before());
            for (List<String> keyColumns : table.keyColumnSets()) {
                Map<String, Object> oldKey = key(before, keyColumns);
                Map<String, Object> newKey = key(after, keyColumns);
                if (oldKey != null && !oldKey.equals(newKey)) {
                    deleteKey(table, oldKey, change, changed);
                }
            }
        }
        for (MappingPlan mapping : table.plan.mappings()) {
            Conversion conversion =
                    converter.convert(
                            table.resourceId,
                            mapping,
                            after.row(),
                            after.unavailable(),
                            after.unsupported());
            if (conversion.object() == null) {
                for (LoadFailure failure : conversion.failures()) {
                    failureRepository.record(
                            table.resourceId,
                            mapping.mappingId(),
                            null,
                            failure,
                            change.syncRunId());
                }
                continue;
            }
            Map<String, Object> sourceKey = conversion.object().sourceKey();
            for (LoadFailure failure : conversion.failures()) {
                failureRepository.record(
                        table.resourceId,
                        mapping.mappingId(),
                        sourceKey,
                        failure,
                        change.syncRunId());
            }
            Set<String> keep = conversion.keptPropertyCodes();
            if (!keep.isEmpty() && !objectSyncService.exists(mapping.mappingId(), sourceKey)) {
                // 유지할 기존 값이 없다: 그 속성 없이 만들고 다시 읽기로 채운다(10.2)
                requireReload(table);
            }
            objectSyncService
                    .applyChange(conversion.object(), change, keep)
                    .ifPresent(
                            id -> {
                                changed.add(id);
                                table.counts.upserted++;
                            });
            failureRepository.closeResolved(mapping.mappingId(), sourceKey, conversion.failures());
        }
    }

    private void applyDelete(
            Table table, ChangeEvent event, SourceChange change, List<Long> changed) {
        DecodedRow before = decode(table, event.schema(), event.before());
        for (List<String> keyColumns : table.keyColumnSets()) {
            Map<String, Object> sourceKey = key(before, keyColumns);
            if (sourceKey != null) {
                deleteKey(table, sourceKey, change, changed);
                continue;
            }
            for (MappingPlan mapping : table.plan.mappings()) {
                if (mapping.keyColumns().equals(keyColumns)) {
                    failureRepository.record(
                            table.resourceId,
                            mapping.mappingId(),
                            null,
                            LoadFailure.key(
                                    missingKeyColumn(before, keyColumns), Reason.KEY_MISSING),
                            change.syncRunId());
                }
            }
        }
    }

    private void deleteKey(
            Table table, Map<String, Object> sourceKey, SourceChange change, List<Long> changed) {
        DeleteResult result = objectSyncService.applyDelete(table.resourceId, sourceKey, change);
        changed.addAll(result.markedObjectIds());
        table.counts.deleted += result.objectsMarked();
        failureRepository.closeDeleted(table.resourceId, sourceKey);
    }

    private void requireReload(Table table) {
        if (!table.reloadRequested) {
            resourceRepository
                    .findById(table.resourceId)
                    .ifPresent(SourceResource::requireFullReload);
            table.reloadRequested = true;
        }
    }

    /** 매핑한 컬럼만 해석한다. 해석하지 못하는 원천 형식의 컬럼은 빼고 다시 해석해 그 컬럼만 실패로 남긴다(행 전체를 버리지 않는다). */
    private DecodedRow decode(Table table, JsonNode schema, JsonNode row) {
        if (schema == null || row == null) {
            throw new CdcProtocolException("변경 이벤트에 스키마나 행이 없습니다: " + table.plan.resourceKey());
        }
        Map<String, Field> fields = new LinkedHashMap<>(DebeziumRowDecoder.rowFields(schema));
        fields.keySet().retainAll(table.columns);
        Set<String> unsupported = new LinkedHashSet<>();
        while (true) {
            try {
                Decoded decoded = decoder.decode(fields, row, table.sourceTypes);
                return new DecodedRow(
                        decoded.row(), decoded.unavailableColumns(), Set.copyOf(unsupported));
            } catch (UnsupportedValueException e) {
                if (fields.remove(e.column()) == null) {
                    throw e;
                }
                unsupported.add(e.column());
            }
        }
    }

    /** 키 컬럼 값이 모두 있으면 원천 키(컬럼 → 키 문자열), 아니면 null. */
    private static Map<String, Object> key(DecodedRow row, List<String> keyColumns) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (String column : keyColumns) {
            String text =
                    row.unsupported().contains(column) ? null : row.row().keyTexts().get(column);
            if (text == null) {
                return null;
            }
            key.put(column, text);
        }
        return key;
    }

    private static String missingKeyColumn(DecodedRow row, List<String> keyColumns) {
        return keyColumns.stream()
                .filter(c -> row.row().keyTexts().get(c) == null)
                .findFirst()
                .orElse(keyColumns.getFirst());
    }

    private static Integer processingLag(SourceTransaction tx) {
        return tx.events().stream()
                .mapToLong(e -> Math.max(0, e.processedTimeMs() - e.commitTimeMs()))
                .max()
                .stream()
                .mapToObj(lag -> (int) Math.min(Integer.MAX_VALUE, lag))
                .findFirst()
                .orElse(null);
    }

    /** 원천 하나의 토픽({@code {data_source.code}.all}). */
    public record CdcSource(long dataSourceId, String topic) {

        public CdcSource {
            Objects.requireNonNull(topic, "topic");
        }
    }

    public enum Status {
        /** 반영했다(변경이 0건이어도 위치는 전진). */
        APPLIED,
        /** 이미 반영한 트랜잭션이다(재전송). 아무것도 쓰지 않았다. */
        ALREADY_APPLIED,
        /** 이 호출에서 복구가 필요하다고 표시했다. 위치는 그대로다. */
        RECOVERY_NEEDED,
        /** 이미 복구가 필요한 원천이라 반영하지 않았다. */
        RECOVERY_PENDING
    }

    /**
     * @param syncRunId 남긴 CDC 수집 실행. 반영한 변경이 없으면 null
     * @param changedObjectIds 쓰거나 삭제 표시한 객체(연결 재계산 대상)
     * @param links 그 객체들로 다시 계산한 연결(보류 건 처리 238에 넘길 수 있다). 반영하지 않았으면 빈 결과
     * @param appliedEvents 반영한 변경 수
     * @param skippedEvents 매핑이 없거나 반영할 수 없는 표라 건너뛴 변경 수
     * @param recoveryReason RECOVERY_NEEDED·RECOVERY_PENDING이면 그 이유
     */
    public record Result(
            Status status,
            Long syncRunId,
            List<Long> changedObjectIds,
            LinkRecomputeResult links,
            int appliedEvents,
            int skippedEvents,
            RecoveryReason recoveryReason) {

        static Result notApplied(Status status, RecoveryReason reason) {
            return new Result(status, null, List.of(), LinkRecomputeResult.empty(), 0, 0, reason);
        }
    }

    private record DecodedRow(SourceRow row, Set<String> unavailable, Set<String> unsupported) {}

    private static final class Table {

        final long resourceId;
        final FullLoadPlan plan;
        final Map<String, String> sourceTypes;
        final Set<String> columns;
        final Counts counts = new Counts();
        boolean reloadRequested;

        Table(
                long resourceId,
                FullLoadPlan plan,
                Map<String, String> sourceTypes,
                Set<String> columns) {
            this.resourceId = resourceId;
            this.plan = plan;
            this.sourceTypes = sourceTypes;
            this.columns = columns;
        }

        /** 매핑마다 키 컬럼이 다르면(CDC 매핑 키는 원천 PK여야 하지만 어긋난 경우) 서로 다른 키 묶음마다 처리한다. */
        List<List<String>> keyColumnSets() {
            return plan.mappings().stream().map(MappingPlan::keyColumns).distinct().toList();
        }
    }

    private static final class Counts {

        int events;
        int upserted;
        int deleted;
        long fromLsn = Long.MAX_VALUE;
        long toLsn = Long.MIN_VALUE;

        void event(long lsn) {
            events++;
            fromLsn = Math.min(fromLsn, lsn);
            toLsn = Math.max(toLsn, lsn);
        }

        ResourceCounts toRecord(long resourceId) {
            return new ResourceCounts(resourceId, fromLsn, toLsn, events, upserted, deleted);
        }
    }
}

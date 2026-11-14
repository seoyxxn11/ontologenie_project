package com.axcore.platform.source.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.ontology.domain.FullLoad;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.ontology.service.ObjectSyncService;
import com.axcore.platform.source.cdc.CdcTransactionApplier.CdcSource;
import com.axcore.platform.source.cdc.CdcTransactionApplier.Result;
import com.axcore.platform.source.cdc.CdcTransactionApplier.Status;
import com.axcore.platform.source.domain.RecoveryReason;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 트랜잭션 하나를 플랫폼 트랜잭션 하나로 반영(Jira 198-5)을 실제 PostgreSQL로 확인한다. 시험 자료는 198-2의 실제 Debezium 메시지
 * {@code cdc-tx/debezium-tx-messages.jsonl}(원천 트랜잭션 756~765)을 파서·조립기에 그대로 흘려 만든다. 반영은 실제로 커밋되므로
 * 시험마다 새 원천·토픽을 만든다.
 *
 * <ul>
 *   <li>756 장비 3 추가, 작업지시 7·8 추가 / 757 작업지시 7 수정 / 758 작업지시 9 추가·8 삭제
 *   <li>761 장비 PK 3 → 30(삭제 + 추가) / 762 doc 1 추가 / 763 doc 1 수정(big은 TOAST 미제공) / 765 doc TRUNCATE
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class CdcTransactionApplierTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 처음 시작할 때의 epoch 기준값(원천 xmax 자리). 자료의 txId는 756~765다. */
    private static final long START = 750;

    private static List<String> lines;

    @Autowired CdcTransactionApplier applier;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired SourceResourceRepository resourceRepository;
    @Autowired DataSource dataSource;
    @MockitoSpyBean ObjectSyncService objectSyncService;

    private CdcSource source;
    private long equipmentResource;
    private long workOrderResource;
    private long docResource;
    private long equipmentMapping;
    private long workOrderMapping;
    private long docMapping;
    private String bigText;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in =
                CdcTransactionApplierTests.class
                        .getClassLoader()
                        .getResourceAsStream("cdc-tx/debezium-tx-messages.jsonl")) {
            lines =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(l -> !l.isBlank())
                            .toList();
        }
    }

    @BeforeEach
    void setUp() {
        String code = "CDC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, 'CDC 시험', 'POSTGRES') returning id
                                """)
                        .param("code", code)
                        .query(Long.class)
                        .single();
        source = new CdcSource(sourceId, code + ".all");
        equipmentResource = resource(sourceId, "erp.equipment", "id:bigint", "name:text");
        workOrderResource =
                resource(sourceId, "erp.work_order", "id:bigint", "eq_id:bigint", "wo_no:text");
        docResource = resource(sourceId, "erp.doc", "id:bigint", "n:integer", "big:text");
        equipmentMapping = mapping(equipmentResource, "name", "name:STRING");
        workOrderMapping = mapping(workOrderResource, "wo_no", "wo_no:STRING", "eq_id:INTEGER");
        docMapping = mapping(docResource, null, "n:INTEGER", "big:STRING");
        bigText = JSON.readTree(lines.get(24)).get("payload").get("after").get("big").asString();
    }

    @Test
    void appliesRealTransactionsInSourceCommitOrder() {
        List<Result> results = new ArrayList<>();
        for (SourceTransaction tx : transactions()) {
            results.add(apply(tx));
        }

        assertThat(results).allMatch(r -> r.status() == Status.APPLIED);
        assertThat(results).extracting(Result::appliedEvents).containsExactly(3, 1, 2, 2, 1, 1, 1);
        // 작업지시: 7은 수정값, 8은 삭제 표시, 9는 추가
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
        assertThat(property(workOrderMapping, "7", "eq_id")).isEqualTo("3");
        assertThat(status(workOrderMapping, "8")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(status(workOrderMapping, "9")).isEqualTo("ACTIVE");
        // 장비 PK 변경: 옛 키 삭제 표시, 새 키 생성
        assertThat(status(equipmentMapping, "3")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(property(equipmentMapping, "30", "name")).isEqualTo("EQ-3");
        // TOAST 미제공 값은 기존 값 유지, 받은 값은 바뀐다
        assertThat(property(docMapping, "1", "n")).isEqualTo("1");
        assertThat(property(docMapping, "1", "big")).isEqualTo(bigText);
        // TRUNCATE는 그 표만 다시 읽기 대상
        assertThat(loadedLsn(docResource)).isNull();
        assertThat(loadedLsn(workOrderResource)).isEqualTo(1L);

        Map<String, Object> offset = offset();
        assertThat(offset.get("next_offset")).isEqualTo(33L);
        assertThat(offset.get("last_txid")).isEqualTo("765");
        assertThat(offset.get("healthy_since")).isNotNull();
        assertThat(offset.get("last_commit_at")).isNotNull();
        assertThat(offset.get("recovery_reason")).isNull();
        // 원천 트랜잭션마다 CDC 실행 하나, 756은 표 둘
        assertThat(
                        count(
                                "select count(*) from sync_run where data_source_id = :s and trigger_kind = 'CDC' and status = 'SUCCEEDED'"))
                .isEqualTo(7);
        assertThat(
                        jdbc.sql(
                                        """
                                        select rows_read, rows_upserted from sync_run_resource
                                        where sync_run_id = :run and source_resource_id = :r and mode = 'INCREMENTAL'
                                        """)
                                .param("run", results.getFirst().syncRunId())
                                .param("r", workOrderResource)
                                .query((rs, row) -> List.of(rs.getInt(1), rs.getInt(2)))
                                .single())
                .containsExactly(2, 2);
        assertThat(results.get(2).changedObjectIds()).hasSize(2);
    }

    @Test
    void failureInMiddleCommitsNothing() {
        // 756의 세 번째 변경(작업지시 8)에서 처리 장애
        // 트랜잭션 프록시 안쪽의 spy에 건다(프록시에 걸면 MANDATORY 검사가 먼저 돈다)
        ObjectSyncService spy = AopTestUtils.getUltimateTargetObject(objectSyncService);
        doThrow(new IllegalStateException("시험용 장애"))
                .when(spy)
                .applyChange(
                        argThat((SyncedObject o) -> "8".equals(o.sourceKey().get("id"))),
                        any(),
                        any());

        assertThatThrownBy(() -> apply(transactions().getFirst()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        count(
                                "select count(*) from object_instance o join object_source_mapping m on m.id = o.source_mapping_id join source_resource r on r.id = m.source_resource_id where r.data_source_id = :s"))
                .isZero();
        assertThat(count("select count(*) from sync_run where data_source_id = :s")).isZero();
        assertThat(count("select count(*) from cdc_offset where data_source_id = :s")).isZero();
    }

    @Test
    void resendDoesNotRevertValues() {
        List<SourceTransaction> txs = transactions();
        apply(txs.get(0));
        apply(txs.get(1));

        // 같은 트랜잭션을 다시 받음: 위치로 거른다
        assertThat(apply(txs.get(1)).status()).isEqualTo(Status.ALREADY_APPLIED);

        // 위치를 잃고 처음부터 다시 받음: 객체의 LSN으로 거른다
        jdbc.sql("update cdc_offset set next_offset = 0 where topic = :t")
                .param("t", source.topic())
                .update();
        Result again = apply(txs.get(0));
        assertThat(again.status()).isEqualTo(Status.APPLIED);
        assertThat(again.changedObjectIds()).isEmpty();
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
    }

    @Test
    void skipsEventsAlreadyInFullLoadSnapshot() {
        // 스냅샷 757:757:에서는 756이 보인다(이미 적재값에 있음), 757은 안 보인다
        long run =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, started_at)
                                values (:s, 'RECOVERY', 'RUNNING', now()) returning id
                                """)
                        .param("s", source.dataSourceId())
                        .query(Long.class)
                        .single();
        FullLoad load = new FullLoad("757:757:", run);
        transactions.executeWithoutResult(
                status ->
                        objectSyncService.applyLoaded(
                                new SyncedObject(
                                        workOrderResource,
                                        workOrderMapping,
                                        typeOf(workOrderMapping),
                                        Map.of("id", "7"),
                                        "WO-7 적재",
                                        Map.of("wo_no", "WO-7 적재", "eq_id", 3)),
                                load));

        List<SourceTransaction> txs = transactions();
        apply(txs.get(0));
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7 적재");
        apply(txs.get(1));
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
    }

    @Test
    void recordsDataFailuresAndClosesThemWhenResolved() {
        // 장비 이름을 정수 속성으로도 매핑: "EQ-3"은 변환 실패
        long numberProperty = addProperty(equipmentMapping, "name", "name_no", "INTEGER");
        List<SourceTransaction> txs = transactions();
        apply(txs.get(0));
        assertThat(property(equipmentMapping, "3", "name")).isEqualTo("EQ-3");
        assertThat(failure("3")).containsExactly("OPEN", "VALUE_CONVERSION", 1, numberProperty);

        // 761: 키 3 삭제 → 열린 기록을 닫는다. 새 키 30은 다시 실패
        apply(txs.get(1));
        apply(txs.get(2));
        apply(txs.get(3));
        assertThat(failure("3").getFirst()).isEqualTo("CLOSED");
        assertThat(failure("30")).containsExactly("OPEN", "VALUE_CONVERSION", 1, numberProperty);
    }

    @Test
    void keepsRowWhenOneMappedColumnHasUnsupportedType() {
        // 장비 name 컬럼을 해석할 수 없는 형식(bytes)으로 바꾼 메시지
        SourceTransaction tx =
                assemble(
                        5,
                        10,
                        line ->
                                line.replace(
                                        "{\"type\":\"string\",\"optional\":true,\"field\":\"name\"}",
                                        "{\"type\":\"bytes\",\"optional\":true,\"field\":\"name\"}"));

        assertThat(apply(tx).status()).isEqualTo(Status.APPLIED);

        assertThat(status(equipmentMapping, "3")).isEqualTo("ACTIVE");
        assertThat(property(equipmentMapping, "3", "name")).isNull();
        assertThat(failure("3").subList(0, 2)).containsExactly("OPEN", "UNSUPPORTED_TYPE");
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7");
    }

    @Test
    void toastWithoutObjectAddsObjectAndRequestsReload() {
        // 762(추가)를 받지 못하고 763(TOAST 미제공 값 수정)부터 받음
        assertThat(apply(transactions().get(5)).status()).isEqualTo(Status.APPLIED);

        assertThat(property(docMapping, "1", "n")).isEqualTo("1");
        assertThat(
                        jdbc.sql(
                                        "select jsonb_exists(properties, 'big') from object_instance where source_mapping_id = :m")
                                .param("m", docMapping)
                                .query(Boolean.class)
                                .single())
                .isFalse();
        assertThat(loadedLsn(docResource)).isNull();
        assertThat(loadedLsn(equipmentResource)).isEqualTo(1L);
    }

    /** V12가 허용하는 복구 이유는 모두 읽을 수 있어야 한다. 하나라도 enum에 없으면 소비자가 끝없이 재시도한다. */
    @Test
    void everyRecoveryReasonInSchemaStopsApplying() {
        List<String> allowed =
                jdbc.sql(
                                """
                                select pg_get_constraintdef(oid) from pg_constraint
                                where conname = 'ck_cdc_offset_recovery_reason'
                                """)
                        .query(String.class)
                        .list();
        assertThat(allowed).hasSize(1);
        for (RecoveryReason reason : RecoveryReason.values()) {
            assertThat(allowed.getFirst()).contains("'" + reason.name() + "'");
        }
        assertThat(allowed.getFirst().split("'").length / 2)
                .isEqualTo(RecoveryReason.values().length);

        // 원천 접속 정보 변경(API 012)이 남긴 표시: 반영하지 않고, 다시 읽기 표시도 하지 않는다
        apply(transactions().getFirst());
        jdbc.sql(
                        """
                        update cdc_offset
                        set recovery_reason = 'SOURCE_CHANGED', recovery_at = now(), healthy_since = null
                        where topic = :t
                        """)
                .param("t", source.topic())
                .update();

        Result next = apply(transactions().get(1));

        assertThat(next.status()).isEqualTo(Status.RECOVERY_PENDING);
        assertThat(next.recoveryReason()).isEqualTo(RecoveryReason.SOURCE_CHANGED);
        assertThat(property(workOrderMapping, "7", "wo_no")).isEqualTo("WO-7");
        assertThat(loadedLsn(workOrderResource)).isEqualTo(1L);
    }

    @Test
    void uncertainEpochMarksRecoveryAndStops() {
        long far = (1L << 32) + 756 + (1L << 30) + 10;

        Result result = applier.apply(source, transactions().getFirst(), far, Instant.now());

        assertThat(result.status()).isEqualTo(Status.RECOVERY_NEEDED);
        assertThat(result.recoveryReason()).isEqualTo(RecoveryReason.EPOCH_UNCERTAIN);
        Map<String, Object> offset = offset();
        assertThat(offset.get("recovery_reason")).isEqualTo("EPOCH_UNCERTAIN");
        assertThat(offset.get("healthy_since")).isNull();
        assertThat(offset.get("next_offset")).isEqualTo(0L);
        assertThat(
                        count(
                                "select count(*) from object_instance o join object_source_mapping m on m.id = o.source_mapping_id join source_resource r on r.id = m.source_resource_id where r.data_source_id = :s"))
                .isZero();
        assertThat(loadedLsn(equipmentResource)).isNull();
        assertThat(loadedLsn(workOrderResource)).isNull();
        assertThat(loadedLsn(docResource)).isNull();

        // 복구가 끝날 때까지(199) 다음 트랜잭션도 반영하지 않는다
        Result next = apply(transactions().get(1));
        assertThat(next.status()).isEqualTo(Status.RECOVERY_PENDING);
        assertThat(property(workOrderMapping, "7", "wo_no")).isNull();
    }

    @Test
    void transactionWithoutMappedChangesOnlyMovesOffset() {
        Result empty = apply(new SourceTransaction(800, List.of(), 2, 4));
        assertThat(empty.status()).isEqualTo(Status.APPLIED);
        assertThat(empty.syncRunId()).isNull();

        // 매핑이 없는 표의 변경은 건너뛴다
        jdbc.sql("update object_source_mapping set is_active = false where id = :m")
                .param("m", workOrderMapping)
                .update();
        Result skipped = apply(transactions().get(1));
        assertThat(skipped.syncRunId()).isNull();
        assertThat(skipped.skippedEvents()).isEqualTo(1);

        assertThat(offset().get("next_offset")).isEqualTo(14L);
        assertThat(offset().get("last_txid")).isEqualTo("757");
        assertThat(count("select count(*) from sync_run where data_source_id = :s")).isZero();
    }

    /**
     * 적재 계획을 만들 수 없는 표(정책 위반, 행 조건 등)의 변경은 건너뛰되, 잃지 않게 다시 읽기를 표시한다(402). 매핑을 고치면 감시 작업이 다시 읽는다. 다른
     * 표의 반영과 오프셋 이동은 그대로 한다.
     */
    @Test
    void skippedTableThatCannotBePlannedIsMarkedForReload() {
        jdbc.sql(
                        "update object_source_mapping set row_filter = '{\"status\": \"OPEN\"}'::jsonb where id = :m")
                .param("m", workOrderMapping)
                .update();

        Result result = apply(transactions().get(1));

        assertThat(result.status()).isEqualTo(Status.APPLIED);
        assertThat(result.skippedEvents()).isEqualTo(1);
        assertThat(loadedLsn(workOrderResource)).isNull();
        assertThat(loadedLsn(equipmentResource)).isEqualTo(1L);
        assertThat(offset().get("next_offset")).isEqualTo(14L);
    }

    /**
     * 반영이 잡는 원천 테이블 잠금은 공유 잠금(FOR SHARE)이다. 다른 CDC 소비자의 공유 잠금은 통과하고, 전체 읽기 저장·매핑 변경의 FOR UPDATE는
     * 기다린다(교착 없는 잠금 순서, 결정 문서 10.1 질문 1).
     */
    @Test
    void resourceLockIsSharedWithConsumersButBlocksFullLoad() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String marker = "cdc-lock-" + UUID.randomUUID();
        Thread holder =
                new Thread(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            jdbc.sql(
                                                            "select set_config('application_name', :n, true)")
                                                    .param("n", marker)
                                                    .query()
                                                    .singleRow();
                                            resourceRepository.lockAllShared(
                                                    List.of(workOrderResource, equipmentResource));
                                            locked.countDown();
                                            try {
                                                release.await(30, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }));
        holder.start();
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            // Hibernate가 실제로 보낸 마지막 SQL
            String sql =
                    jdbc.sql("select query from pg_stat_activity where application_name = :n")
                            .param("n", marker)
                            .query(String.class)
                            .single();
            assertThat(sql).containsIgnoringCase("order by").containsIgnoringCase("for share");
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(false);
                try (Statement statement = other.createStatement()) {
                    statement.execute(
                            "select id from source_resource where id = %d for share nowait"
                                    .formatted(workOrderResource));
                    assertThatThrownBy(
                                    () ->
                                            statement.execute(
                                                    "select id from source_resource where id = %d for update nowait"
                                                            .formatted(workOrderResource)))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("55P03");
                } finally {
                    other.rollback();
                }
            }
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }

    /** 반영한 트랜잭션 안에서 바뀐 객체의 연결을 다시 계산한다(198-7). 작업지시.eq_id = 설비.id 규칙(MANY_TO_ONE)으로 실제 메시지를 흘린다. */
    @Test
    void recomputesLinksOfChangedObjectsInSameTransaction() {
        long equipmentKey = addProperty(equipmentMapping, "id", "eq_id", "INTEGER");
        long linkType = linkRule(workOrderMapping, "eq_id", equipmentMapping, equipmentKey);
        List<SourceTransaction> txs = transactions();

        // 756: 설비 3, 작업지시 7·8 → 7→3, 8→3
        Result first = apply(txs.get(0));
        assertThat(first.links().activatedLinkIds()).hasSize(2);
        assertThat(activeLinks(linkType)).containsExactlyInAnyOrder("7->3", "8->3");

        // 757 수정, 758 작업지시 9 추가·8 삭제 → 8의 연결은 꺼지고 9가 이어진다
        apply(txs.get(1));
        Result third = apply(txs.get(2));
        assertThat(third.links().activatedLinkIds()).hasSize(1);
        assertThat(third.links().inactivatedLinkIds()).hasSize(1);
        assertThat(activeLinks(linkType)).containsExactlyInAnyOrder("7->3", "9->3");

        // 761 설비 PK 3 → 30: 작업지시의 eq_id는 아직 3이라 연결이 모두 꺼진다(지우지 않고 INACTIVE)
        Result pkChange = apply(txs.get(3));
        assertThat(pkChange.links().inactivatedLinkIds()).hasSize(2);
        assertThat(activeLinks(linkType)).isEmpty();
        assertThat(
                        count(
                                "select count(*) from object_link l join link_type t on t.id = l.link_type_id"
                                        + " join object_instance o on o.id = l.source_object_id"
                                        + " join object_source_mapping m on m.id = o.source_mapping_id"
                                        + " join source_resource r on r.id = m.source_resource_id"
                                        + " where r.data_source_id = :s and l.status = 'INACTIVE'"))
                .isEqualTo(3);
        // issue는 만들지 않는다(SYNC 검사가 만든다)
        assertThat(
                        jdbc.sql(
                                        "select count(*) from issue where link_mapping_id in (select id from link_mapping where link_type_id = :t)")
                                .param("t", linkType)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private Result apply(SourceTransaction tx) {
        return applier.apply(source, tx, START, Instant.now());
    }

    /** 자료 전체를 조립한 원천 트랜잭션 7개(756, 757, 758, 761, 762, 763, 765). */
    private static List<SourceTransaction> transactions() {
        return assemble(0, lines.size() - 1, UnaryOperator.identity(), true);
    }

    private static SourceTransaction assemble(int from, int to, UnaryOperator<String> edit) {
        return assemble(from, to, edit, false).getFirst();
    }

    private static List<SourceTransaction> assemble(
            int from, int to, UnaryOperator<String> edit, boolean all) {
        CdcMessageParser parser = new CdcMessageParser(JSON);
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(100);
        List<SourceTransaction> result = new ArrayList<>();
        for (int offset = from; offset <= to; offset++) {
            assembler
                    .accept(parser.parse(edit.apply(lines.get(offset))), offset)
                    .ifPresent(result::add);
        }
        assertThat(result).isNotEmpty();
        return result;
    }

    private long resource(long sourceId, String key, String... columns) {
        long id =
                jdbc.sql(
                                """
                                insert into source_resource (data_source_id, resource_key, resource_kind,
                                                             sync_strategy, is_readable, loaded_lsn)
                                values (:s, :key, 'TABLE', 'CDC', true, 1) returning id
                                """)
                        .param("s", sourceId)
                        .param("key", key)
                        .query(Long.class)
                        .single();
        int position = 1;
        for (String column : columns) {
            String[] parts = column.split(":");
            jdbc.sql(
                            """
                            insert into source_column (source_resource_id, column_name, source_data_type,
                                                       is_nullable, is_key_part, ordinal_position)
                            values (:r, :name, :type, true, :key, :position)
                            """)
                    .param("r", id)
                    .param("name", parts[0])
                    .param("type", parts[1])
                    .param("key", parts[0].equals("id"))
                    .param("position", position++)
                    .update();
        }
        return id;
    }

    /** 키는 id. properties는 "컬럼:자료형"이고 속성 코드는 컬럼 이름과 같다. */
    private long mapping(long resourceId, String displayColumn, String... properties) {
        long type =
                jdbc.sql(
                                "insert into object_type (code, name) values ('T-' || gen_random_uuid(), '시험') returning id")
                        .query(Long.class)
                        .single();
        long mapping =
                jdbc.sql(
                                """
                                insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                                values (:r, :t, '["id"]') returning id
                                """)
                        .param("r", resourceId)
                        .param("t", type)
                        .query(Long.class)
                        .single();
        for (String property : properties) {
            String[] parts = property.split(":");
            long propertyId = addProperty(mapping, parts[0], parts[0], parts[1]);
            if (parts[0].equals(displayColumn)) {
                jdbc.sql("update object_type set display_property_id = :p where id = :t")
                        .param("p", propertyId)
                        .param("t", type)
                        .update();
            }
        }
        return mapping;
    }

    private long addProperty(long mappingId, String column, String code, String dataType) {
        long propertyId =
                jdbc.sql(
                                """
                                insert into property_definition (object_type_id, code, name, data_type)
                                select object_type_id, :code, :code, :type from object_source_mapping where id = :m
                                returning id
                                """)
                        .param("code", code)
                        .param("type", dataType)
                        .param("m", mappingId)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into property_source_mapping (source_mapping_id, source_resource_id,
                                                             property_definition_id, source_column_id)
                        select m.id, m.source_resource_id, :p, c.id
                        from object_source_mapping m
                        join source_column c on c.source_resource_id = m.source_resource_id and c.column_name = :c
                        where m.id = :m
                        """)
                .param("p", propertyId)
                .param("c", column)
                .param("m", mappingId)
                .update();
        return propertyId;
    }

    /** 출발 매핑의 속성 = 도착 매핑의 속성인 관계 유형과 규칙(MANY_TO_ONE). 관계 유형 ID를 돌려준다. */
    private long linkRule(
            long sourceMapping, String sourceCode, long targetMapping, long targetProperty) {
        long sourceProperty =
                jdbc.sql(
                                """
                                select p.id from property_definition p
                                join object_source_mapping m on m.object_type_id = p.object_type_id
                                where m.id = :m and p.code = :code
                                """)
                        .param("m", sourceMapping)
                        .param("code", sourceCode)
                        .query(Long.class)
                        .single();
        long linkType =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values ('LT-' || gen_random_uuid(), '작업지시 설비', :s, :t, 'MANY_TO_ONE', '198-7 시험')
                                returning id
                                """)
                        .param("s", typeOf(sourceMapping))
                        .param("t", typeOf(targetMapping))
                        .query(Long.class)
                        .single();
        // 비교 조건은 규칙을 만든 트랜잭션 안에서만 넣을 수 있다(DB 트리거)
        transactions.executeWithoutResult(
                status -> {
                    long rule =
                            jdbc.sql(
                                            """
                                            insert into link_mapping (link_type_id, code, revision)
                                            values (:t, 'R-' || gen_random_uuid(), 1) returning id
                                            """)
                                    .param("t", linkType)
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into link_mapping_condition
                                        (link_mapping_id, source_property_id, target_property_id, sequence_no)
                                    values (:r, :s, :t, 1)
                                    """)
                            .param("r", rule)
                            .param("s", sourceProperty)
                            .param("t", targetProperty)
                            .update();
                });
        return linkType;
    }

    /** 그 관계 유형의 활성 연결("출발 id->도착 id"). */
    private List<String> activeLinks(long linkType) {
        return jdbc.sql(
                        """
                        select (s.source_key ->> 'id') || '->' || (t.source_key ->> 'id')
                        from object_link l
                        join object_instance s on s.id = l.source_object_id
                        join object_instance t on t.id = l.target_object_id
                        where l.link_type_id = :t and l.status = 'ACTIVE'
                        """)
                .param("t", linkType)
                .query(String.class)
                .list();
    }

    private long typeOf(long mappingId) {
        return jdbc.sql("select object_type_id from object_source_mapping where id = :m")
                .param("m", mappingId)
                .query(Long.class)
                .single();
    }

    private String property(long mappingId, String id, String code) {
        return jdbc.sql(
                        """
                        select properties ->> :code from object_instance
                        where source_mapping_id = :m and source_key = jsonb_build_object('id', :id)
                        """)
                .param("code", code)
                .param("m", mappingId)
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String status(long mappingId, String id) {
        return jdbc.sql(
                        """
                        select status from object_instance
                        where source_mapping_id = :m and source_key = jsonb_build_object('id', :id)
                        """)
                .param("m", mappingId)
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private Long loadedLsn(long resourceId) {
        return jdbc.sql("select loaded_lsn from source_resource where id = :r")
                .param("r", resourceId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private Map<String, Object> offset() {
        return jdbc.sql(
                        """
                        select next_offset, last_txid::text as last_txid, last_commit_at, healthy_since,
                               recovery_reason
                        from cdc_offset where topic = :t
                        """)
                .param("t", source.topic())
                .query()
                .singleRow();
    }

    /** 장비 매핑의 그 키의 수집 실패 기록(가장 최근): 상태, 이유, 횟수, 속성. */
    private List<Object> failure(String id) {
        return jdbc.sql(
                        """
                        select status, reason, occurrences, property_definition_id
                        from source_load_failure
                        where object_source_mapping_id = :m and source_key = jsonb_build_object('id', :id)
                        order by id desc limit 1
                        """)
                .param("m", equipmentMapping)
                .param("id", id)
                .query(
                        (rs, row) ->
                                List.<Object>of(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getInt(3),
                                        rs.getLong(4)))
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).param("s", source.dataSourceId()).query(Integer.class).single();
    }
}

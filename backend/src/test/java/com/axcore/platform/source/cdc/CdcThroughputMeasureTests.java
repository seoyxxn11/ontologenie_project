package com.axcore.platform.source.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.service.AgentScenarioDataset;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.ontology.dto.LinkRecomputeResult;
import com.axcore.platform.ontology.service.LinkRecomputeService;
import com.axcore.platform.source.cdc.CdcTransactionApplier.CdcSource;
import com.axcore.platform.source.cdc.CdcTransactionApplier.Result;
import com.axcore.platform.source.service.CdcReloadJob;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CDC 소비 처리량·정합성 측정(Jira S15P31C106-335). 운영과 같은 구성을 로컬 컨테이너로 띄운다: 원천 PostgreSQL 18.6(가상 프레스
 * data-v1.0 적재, wal_level=logical) → Debezium 3.6.3(운영 커넥터 설정) → Kafka 4.2.2 → 실제 batch 소비자. 플랫폼에는
 * 같은 데이터로 만든 시연 온톨로지(agent-scenario, 객체 3,396개·연결 10,118개)를 넣어 첫 적재를 마친 상태로 시작한다.
 *
 * <p>부하를 단계별로 원천에 넣고, 원천 트랜잭션마다 반영 시간·연결 재계산 시간·원천 커밋부터 반영까지 걸린 시간을 남긴다. 끝에 원천 행과 플랫폼 객체가 같은지, 연결이
 * 전체 재계산 결과와 같은지 확인한다.
 *
 * <p>측정용이라 평소 빌드에서는 돌지 않는다. {@code AXCORE_CDC_MEASURE=true}일 때만 돈다. 결과는 {@code build/cdc-measure/}에
 * 남는다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.cdc.consumer.enabled=true",
            // 소비할 원천을 빨리 찾게만 줄인다. 나머지(poll 1s, max-lag 2m, stale-after 3m, max-events 50000)는 운영 값
            // 그대로
            "axcore.cdc.consumer.refresh-interval=2s"
        })
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_CDC_MEASURE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CdcThroughputMeasureTests {

    private static final Path DATA = Path.of("..", "ai", "data", "data-v1.0");
    private static final Path SCENARIO = Path.of("..", "ai", "data", "scenario-v1.0");
    private static final Path OUT = Path.of("build", "cdc-measure");
    private static final Duration DRAIN = Duration.ofMinutes(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 늘린 복사본 수. 9면 거래 데이터가 원래의 10배가 된다. */
    private static final int COPIES = intEnv("AXCORE_CDC_MEASURE_COPIES", 9);

    /** 작은 트랜잭션 부하의 건수 배율(기본 1). */
    private static final double LOAD = Double.parseDouble(env("AXCORE_CDC_MEASURE_LOAD", "1"));

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer SOURCE =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("source")
                    .withDatabaseName("erp")
                    .withCommand(
                            "postgres",
                            "-c",
                            "fsync=off",
                            "-c",
                            "wal_level=logical",
                            "-c",
                            "max_replication_slots=8",
                            "-c",
                            "max_wal_senders=8");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"))
                    .withNetwork(NETWORK)
                    .withListener("kafka:19092");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONNECT =
            new GenericContainer<>(DockerImageName.parse("quay.io/debezium/connect:3.6.3.Final"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8083)
                    .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
                    .withEnv("GROUP_ID", "axcore-connect")
                    .withEnv("CONFIG_STORAGE_TOPIC", "connect_configs")
                    .withEnv("OFFSET_STORAGE_TOPIC", "connect_offsets")
                    .withEnv("STATUS_STORAGE_TOPIC", "connect_statuses")
                    .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("HEAP_OPTS", "-Xms256m -Xmx768m")
                    .waitingFor(
                            Wait.forHttp("/")
                                    .forPort(8083)
                                    .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        SOURCE.start();
        KAFKA.start();
        CONNECT.dependsOn(KAFKA);
        CONNECT.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("axcore.cdc.consumer.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // 다른 배치 작업(SYNC 검사, 다시 읽기)은 끈다. 소비 루프만 잰다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;
    @MockitoBean CdcStartReference startReference;

    @MockitoSpyBean CdcTransactionApplier applier;
    @MockitoSpyBean LinkRecomputeService links;

    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transaction;
    @Autowired tools.jackson.databind.json.JsonMapper json;

    private final List<Map<String, Object>> txLog = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> samples = new CopyOnWriteArrayList<>();
    private final List<String> summary = new ArrayList<>();
    private final ThreadLocal<long[]> linkNanos = ThreadLocal.withInitial(() -> new long[1]);
    private final Map<Long, String> databaseOf = new HashMap<>();

    /** 부하 끝을 알리려고 넣은 표시 트랜잭션(32비트 번호). 요약에서 뺀다. */
    private final java.util.Set<Long> markers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile String workload = "setup";
    private ScheduledExecutorService sampler;

    @BeforeAll
    void setUp() throws Exception {
        Files.createDirectories(OUT);
        loadSource();
        registerConnector(
                "cdc-erp",
                "erp",
                "PRESS_ERP",
                "erp.attendance,erp.fixed_asset,erp.change_log",
                "erp.employee.(emp_name|position|hire_date|employment_type|status)");
        registerConnector("cdc-mes", "mes", "PRESS_MES", "mes.change_log", "mes.worker.name");

        doAnswer(
                        invocation -> {
                            long started = System.nanoTime();
                            Object result = invocation.callRealMethod();
                            linkNanos.get()[0] += System.nanoTime() - started;
                            // 스파이가 호출 인자를 쌓아 두지 않게 한다(측정 장치가 힙을 차지하지 않게)
                            org.mockito.Mockito.clearInvocations(invocation.getMock());
                            return result;
                        })
                .when((LinkRecomputeService) AopTestUtils.getUltimateTargetObject(links))
                .recomputeObjects(any());
        doAnswer(
                        invocation -> {
                            CdcSource source = invocation.getArgument(0);
                            SourceTransaction tx = invocation.getArgument(1);
                            Instant receivedAt = invocation.getArgument(3);
                            linkNanos.get()[0] = 0;
                            long started = System.nanoTime();
                            Result result = (Result) invocation.callRealMethod();
                            long applyNanos = System.nanoTime() - started;
                            long doneMs = System.currentTimeMillis();
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("workload", workload);
                            row.put("topic", source.topic());
                            row.put("txId", tx.txId());
                            row.put("events", tx.events().size());
                            row.put("status", result.status().name());
                            row.put("applied", result.appliedEvents());
                            row.put("skipped", result.skippedEvents());
                            row.put("changedObjects", result.changedObjectIds().size());
                            row.put("activated", result.links().activatedLinkIds().size());
                            row.put("updated", result.links().updatedLinkIds().size());
                            row.put("inactivated", result.links().inactivatedLinkIds().size());
                            row.put("applyMs", applyNanos / 1_000_000.0);
                            row.put("linkMs", linkNanos.get()[0] / 1_000_000.0);
                            if (tx.commitTimeMs() > 0) {
                                row.put(
                                        "commitToReceiveMs",
                                        receivedAt.toEpochMilli() - tx.commitTimeMs());
                                row.put("commitToAppliedMs", doneMs - tx.commitTimeMs());
                            }
                            txLog.add(row);
                            org.mockito.Mockito.clearInvocations(invocation.getMock());
                            return result;
                        })
                .when(applier)
                .apply(any(), any(), any(), any());
        when(startReference.read(anyLong()))
                .thenAnswer(
                        invocation -> sourceXmax(databaseOf.get((Long) invocation.getArgument(0))));

        // 토픽을 만들려고 원천마다 바뀌지 않는 수정 하나를 넣는다(플랫폼에는 같은 값이 다시 온다)
        sourceUpdate(
                "erp",
                "update erp.item set item_name = item_name where item_code = (select min(item_code) from erp.item)");
        sourceUpdate("mes", "update mes.equipment set maker = maker where code = 'P101'");
        awaitTopic("PRESS_ERP.all");
        awaitTopic("PRESS_MES.all");

        long loadStarted = System.nanoTime();
        AgentScenarioDataset.Loaded loaded =
                new AgentScenarioDataset(jdbc, jdbcTemplate, transaction, json).load();
        summary.add(
                String.format(
                        "dataset objects=%d links=%d (%.1fs)",
                        loaded.objects(), loaded.links(), (System.nanoTime() - loadStarted) / 1e9));
        alignSourceColumns();
        summary.add(
                "baseline verify (before CDC): tables with mismatch=" + verifyObjects("baseline "));
        LinkRecomputeResult baseline = recomputeAll(false);
        summary.add("baseline full recompute vs dataset: " + describe(baseline));
        if (!isEmpty(baseline)) {
            // 시험 데이터 연결과 지금 계산이 다르면 먼저 맞춰 두고 시작한다(측정 대상이 아님)
            recomputeAll(true);
            summary.add("baseline aligned: " + describe(recomputeAll(false)));
        }
        for (Map<String, Object> source :
                jdbc.sql("select id, code from data_source").query().listOfRows()) {
            databaseOf.put(
                    ((Number) source.get("id")).longValue(),
                    source.get("code").equals("PRESS_ERP") ? "erp" : "mes");
        }
        jdbc.sql("update source_resource set sync_strategy = 'CDC', loaded_lsn = 1").update();

        sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .until(
                        () ->
                                jdbc.sql("select count(*) from cdc_offset")
                                                .query(Integer.class)
                                                .single()
                                        == 2);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (sampler != null) {
            sampler.shutdownNow();
        }
        writeLines(OUT.resolve("tx.jsonl"), txLog.stream().map(JSON::writeValueAsString).toList());
        writeLines(
                OUT.resolve("samples.jsonl"),
                samples.stream().map(JSON::writeValueAsString).toList());
        writeLines(OUT.resolve("summary.txt"), summary);
        summary.forEach(System.out::println);
    }

    @Test
    void measure() throws Exception {
        run("W0 drain warm-up", () -> {});

        run(
                "W1 scenario cdc_01~04,99",
                () -> {
                    for (String file :
                            List.of(
                                    "cdc_01_insert.sql",
                                    "cdc_02_update.sql",
                                    "cdc_03_delete.sql",
                                    "cdc_04_add_column.sql",
                                    "cdc_99_restore.sql")) {
                        psql("mes", "/scenario/" + file);
                    }
                });

        List<List<Object>> operations =
                keys("mes", "select wo_no, op_seq from mes.work_order_operation");
        List<List<Object>> workOrders = keys("mes", "select wo_no from mes.work_order");
        List<List<Object>> salesOrders = keys("erp", "select so_no from erp.sales_order");

        run(
                "W2a small: work_order_operation 1 row/tx",
                () ->
                        smallUpdates(
                                "mes",
                                "update mes.work_order_operation set remaining_qty = remaining_qty + 1 where wo_no = ? and op_seq = ?",
                                operations,
                                n(500)));
        run(
                "W2b small: work_order 1 row/tx",
                () ->
                        smallUpdates(
                                "mes",
                                "update mes.work_order set produced_qty = produced_qty + 1 where wo_no = ?",
                                workOrders,
                                n(500)));
        run(
                "W2c small: erp sales_order 1 row/tx",
                () ->
                        smallUpdates(
                                "erp",
                                "update erp.sales_order set total_amount = total_amount + 1 where so_no = ?",
                                salesOrders,
                                n(300)));
        run(
                "W2d small: mes+erp at the same time",
                () -> {
                    Thread erp =
                            new Thread(
                                    () ->
                                            smallUpdates(
                                                    "erp",
                                                    "update erp.sales_order set total_amount = total_amount + 1 where so_no = ?",
                                                    salesOrders,
                                                    n(200)));
                    erp.start();
                    smallUpdates(
                            "mes",
                            "update mes.work_order set produced_qty = produced_qty + 1 where wo_no = ?",
                            workOrders,
                            n(200));
                    erp.join();
                });
        run("W3 big: all production_result + operations in 1 tx", () -> bigUpdate());

        run("W4 scale x" + (COPIES + 1) + " inserts (1 tx per table per copy)", this::scaleUp);
        summary.add(
                "objects after scale: "
                        + jdbc.sql("select count(*) from object_instance where status = 'ACTIVE'")
                                .query(Long.class)
                                .single()
                        + ", active links: "
                        + jdbc.sql("select count(*) from object_link where status = 'ACTIVE'")
                                .query(Long.class)
                                .single());

        List<List<Object>> operations10 =
                keys("mes", "select wo_no, op_seq from mes.work_order_operation");
        List<List<Object>> workOrders10 = keys("mes", "select wo_no from mes.work_order");
        List<List<Object>> salesOrders10 = keys("erp", "select so_no from erp.sales_order");
        run(
                "W5a small x10: work_order_operation 1 row/tx",
                () ->
                        smallUpdates(
                                "mes",
                                "update mes.work_order_operation set remaining_qty = remaining_qty + 1 where wo_no = ? and op_seq = ?",
                                operations10,
                                n(200)));
        run(
                "W5b small x10: work_order 1 row/tx",
                () ->
                        smallUpdates(
                                "mes",
                                "update mes.work_order set produced_qty = produced_qty + 1 where wo_no = ?",
                                workOrders10,
                                n(200)));
        run(
                "W5c small x10: erp sales_order 1 row/tx",
                () ->
                        smallUpdates(
                                "erp",
                                "update erp.sales_order set total_amount = total_amount + 1 where so_no = ?",
                                salesOrders10,
                                n(100)));
        run("W5d big x10: all production_result + operations in 1 tx", () -> bigUpdate());
        run("W6 delete last copy (1 tx per table)", () -> deleteCopy(COPIES));

        assertThat(verifyObjects("final ")).as("원천 행과 플랫폼 객체가 달라진 표").isZero();
        LinkRecomputeResult finalCheck = recomputeAll(false);
        summary.add("final full recompute vs CDC result: " + describe(finalCheck));
        assertThat(isEmpty(finalCheck)).as("CDC로 맞춘 연결이 전체 재계산과 같아야 한다").isTrue();
        assertThat(
                        jdbc.sql(
                                        "select count(*) from cdc_offset where recovery_reason is not null")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    // ---- 부하 ----

    private interface Load {
        void run() throws Exception;
    }

    /** 부하를 넣고, 원천마다 표시 트랜잭션을 하나 더 넣어 그것까지 반영될 때까지 기다린 뒤 요약한다. */
    private void run(String name, Load load) throws Exception {
        workload = name;
        int before = txLog.size();
        long started = System.currentTimeMillis();
        load.run();
        long loaded = System.currentTimeMillis();
        long erpMark =
                marker(
                        "erp",
                        "update erp.item set item_name = item_name where item_code = (select min(item_code) from erp.item)");
        long mesMark = marker("mes", "update mes.equipment set maker = maker where code = 'P101'");
        await().atMost(DRAIN)
                .pollInterval(Duration.ofMillis(500))
                .until(
                        () ->
                                lastTxid("PRESS_ERP.all") >= erpMark
                                        && lastTxid("PRESS_MES.all") >= mesMark);
        long drained = System.currentTimeMillis();
        summarize(name, txLog.subList(before, txLog.size()), started, loaded, drained);
    }

    private long marker(String database, String sql) throws SQLException {
        long txid = sourceUpdate(database, sql);
        markers.add(txid & 0xFFFFFFFFL);
        return txid;
    }

    private int n(int base) {
        return Math.max(1, (int) Math.round(base * LOAD));
    }

    private void smallUpdates(String database, String sql, List<List<Object>> keys, int count) {
        Random random = new Random(count * 31L + sql.hashCode());
        try (Connection connection = source(database);
                var statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            for (int i = 0; i < count; i++) {
                List<Object> key = keys.get(random.nextInt(keys.size()));
                for (int p = 0; p < key.size(); p++) {
                    statement.setObject(p + 1, key.get(p));
                }
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void bigUpdate() throws SQLException {
        try (Connection connection = source("mes");
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("update mes.production_result set good_qty = good_qty + 1");
            statement.executeUpdate(
                    "update mes.work_order_operation set remaining_qty = remaining_qty + 1");
            connection.commit();
        }
    }

    /** 거래 데이터(주문·작업지시·실적 등)를 복사해 늘린다. 기준 정보(품목·설비·금형·작업자 등)는 그대로 둔다. 연결 값은 복사본끼리 맞게 바꾼다. */
    private void scaleUp() throws SQLException {
        try (Connection mes = source("mes");
                Connection erp = source("erp");
                Statement m = mes.createStatement();
                Statement e = erp.createStatement()) {
            // 임시 표는 WAL 논리 복제에 나가지 않는다
            for (String table :
                    List.of(
                            "work_order",
                            "work_order_operation",
                            "production_result",
                            "material_issue",
                            "downtime",
                            "sensor_reading",
                            "defect",
                            "inspection",
                            "handover_note")) {
                m.execute("create temp table o_" + table + " as select * from mes." + table);
            }
            for (String table : List.of("sales_order", "sales_order_line", "shipment")) {
                e.execute("create temp table o_" + table + " as select * from erp." + table);
            }
            mes.setAutoCommit(true);
            erp.setAutoCommit(true);
            for (int k = 1; k <= COPIES; k++) {
                String s = "'-9" + k + "'";
                long n = k * 10_000_000L;
                e.execute(
                        "insert into erp.sales_order select so_no || "
                                + s
                                + ", customer_code, so_date, due_date, status, total_amount, sales_rep from o_sales_order");
                e.execute(
                        "insert into erp.sales_order_line select so_no || "
                                + s
                                + ", line_no, item_code, qty, unit_price, shipped_qty from o_sales_order_line");
                e.execute(
                        "insert into erp.shipment select ship_no || "
                                + s
                                + ", so_no || "
                                + s
                                + ", item_code, ship_date, qty, carrier, lot_no || "
                                + s
                                + " from o_shipment");
                m.execute(
                        "insert into mes.work_order select wo_no || "
                                + s
                                + ", product_code, die_drawing_code, status, priority, line, planned_qty, produced_qty, defect_qty, planned_start, planned_end, actual_start, actual_end, so_ref || "
                                + s
                                + " from o_work_order");
                m.execute(
                        "insert into mes.work_order_operation select wo_no || "
                                + s
                                + ", op_seq, equipment_code, status, remaining_qty from o_work_order_operation");
                m.execute(
                        "insert into mes.production_result select result_id + "
                                + n
                                + ", wo_no || "
                                + s
                                + ", op_seq, equipment_code, worker_id, shift, started_at, ended_at, good_qty, ng_qty from o_production_result");
                m.execute(
                        "insert into mes.material_issue select issue_id + "
                                + n
                                + ", wo_no || "
                                + s
                                + ", item_code, lot_no, qty, unit, issued_at from o_material_issue");
                m.execute(
                        "insert into mes.downtime select downtime_id || "
                                + s
                                + ", equipment_code, wo_no || "
                                + s
                                + ", reason_code, reason, started_at, ended_at, minutes from o_downtime");
                m.execute(
                        "insert into mes.sensor_reading select equipment_code, measured_at - make_interval(years => "
                                + k
                                + "), metric, value, threshold_high from o_sensor_reading");
                m.execute(
                        "insert into mes.defect select defect_id + "
                                + n
                                + ", wo_no || "
                                + s
                                + ", op_seq, equipment_code, defect_type, qty, disposition, detected_at, detected_by from o_defect");
                m.execute(
                        "insert into mes.inspection select inspection_id + "
                                + n
                                + ", wo_no || "
                                + s
                                + ", lot_no || "
                                + s
                                + ", inspection_type, item, spec_low, spec_high, measured, result, inspected_at from o_inspection");
                m.execute(
                        "insert into mes.handover_note select note_id || "
                                + s
                                + ", shift_date, shift, author_id, written_at, content from o_handover_note");
            }
        }
    }

    private void deleteCopy(int k) throws SQLException {
        String s = "'%-9" + k + "'";
        long n = k * 10_000_000L;
        try (Connection mes = source("mes");
                Connection erp = source("erp");
                Statement m = mes.createStatement();
                Statement e = erp.createStatement()) {
            m.execute("delete from mes.handover_note where note_id like " + s);
            m.execute(
                    "delete from mes.inspection where inspection_id between "
                            + n
                            + " and "
                            + (n + 9_999_999));
            m.execute(
                    "delete from mes.defect where defect_id between "
                            + n
                            + " and "
                            + (n + 9_999_999));
            m.execute(
                    "delete from mes.sensor_reading where extract(year from measured_at) = "
                            + (2026 - k));
            m.execute("delete from mes.downtime where downtime_id like " + s);
            m.execute(
                    "delete from mes.material_issue where issue_id between "
                            + n
                            + " and "
                            + (n + 9_999_999));
            m.execute(
                    "delete from mes.production_result where result_id between "
                            + n
                            + " and "
                            + (n + 9_999_999));
            m.execute("delete from mes.work_order_operation where wo_no like " + s);
            m.execute("delete from mes.work_order where wo_no like " + s);
            e.execute("delete from erp.shipment where ship_no like " + s);
            e.execute("delete from erp.sales_order_line where so_no like " + s);
            e.execute("delete from erp.sales_order where so_no like " + s);
        }
    }

    // ---- 확인 ----

    /** 매핑한 원천 표마다 원천 행과 플랫폼 활성 객체가 키·속성 값까지 같은지 본다. */
    private int verifyObjects(String label) throws SQLException {
        List<Map<String, Object>> mappings =
                jdbc.sql(
                                """
                                select m.id, r.resource_key, ds.code as source_code
                                from object_source_mapping m
                                join source_resource r on r.id = m.source_resource_id
                                join data_source ds on ds.id = r.data_source_id
                                order by r.resource_key
                                """)
                        .query()
                        .listOfRows();
        int mismatchedTables = 0;
        for (Map<String, Object> mapping : mappings) {
            long mappingId = ((Number) mapping.get("id")).longValue();
            String table = (String) mapping.get("resource_key");
            String database = "PRESS_ERP".equals(mapping.get("source_code")) ? "erp" : "mes";
            List<String> keyColumns = keyColumns(mappingId);
            Map<String, String[]> columns =
                    new LinkedHashMap<>(); // property code -> [column, data type]
            for (Map<String, Object> row :
                    jdbc.sql(
                                    """
                                    select p.code, p.data_type, c.column_name
                                    from property_source_mapping psm
                                    join property_definition p on p.id = psm.property_definition_id
                                    join source_column c on c.id = psm.source_column_id
                                    where psm.source_mapping_id = :m
                                    """)
                            .param("m", mappingId)
                            .query()
                            .listOfRows()) {
                columns.put(
                        (String) row.get("code"),
                        new String[] {
                            (String) row.get("column_name"), (String) row.get("data_type")
                        });
            }
            Map<String, JsonNode> sourceRows = new HashMap<>();
            try (Connection connection = source(database);
                    Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "select row_to_json(t)::text from " + table + " t")) {
                while (rs.next()) {
                    JsonNode row = JSON.readTree(rs.getString(1));
                    sourceRows.put(keyText(keyColumns, row), row);
                }
            }
            Map<String, JsonNode> objects = new HashMap<>();
            for (Map<String, Object> row :
                    jdbc.sql(
                                    """
                                    select source_key::text as k, properties::text as p from object_instance
                                    where source_mapping_id = :m and status = 'ACTIVE'
                                    """)
                            .param("m", mappingId)
                            .query()
                            .listOfRows()) {
                objects.put(
                        keyText(keyColumns, JSON.readTree((String) row.get("k"))),
                        JSON.readTree((String) row.get("p")));
            }
            int missing = 0;
            int extra = 0;
            int different = 0;
            List<String> examples = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : sourceRows.entrySet()) {
                JsonNode properties = objects.get(entry.getKey());
                if (properties == null) {
                    missing++;
                    if (examples.size() < 3) examples.add("missing " + entry.getKey());
                    continue;
                }
                for (Map.Entry<String, String[]> column : columns.entrySet()) {
                    JsonNode sourceValue = entry.getValue().get(column.getValue()[0]);
                    JsonNode platformValue = properties.get(column.getKey());
                    if (!sameValue(sourceValue, platformValue, column.getValue()[1])) {
                        different++;
                        if (examples.size() < 3)
                            examples.add(
                                    entry.getKey()
                                            + "."
                                            + column.getKey()
                                            + " source="
                                            + sourceValue
                                            + " platform="
                                            + platformValue);
                        break;
                    }
                }
            }
            for (String key : objects.keySet()) {
                if (!sourceRows.containsKey(key)) {
                    extra++;
                    if (examples.size() < 3) examples.add("extra " + key);
                }
            }
            String line =
                    String.format(
                            label
                                    + "verify %-28s source=%5d objects=%5d missing=%d extra=%d different=%d %s",
                            table,
                            sourceRows.size(),
                            objects.size(),
                            missing,
                            extra,
                            different,
                            examples);
            summary.add(line);
            if (missing + extra + different > 0) {
                mismatchedTables++;
            }
        }
        return mismatchedTables;
    }

    private List<String> keyColumns(long mappingId) {
        String keys =
                jdbc.sql("select key_columns::text from object_source_mapping where id = :m")
                        .param("m", mappingId)
                        .query(String.class)
                        .single();
        List<String> columns = new ArrayList<>();
        JSON.readTree(keys).forEach(node -> columns.add(node.asString()));
        return columns;
    }

    private static String keyText(List<String> keyColumns, JsonNode row) {
        return keyColumns.stream()
                .map(
                        c -> {
                            JsonNode value = row.get(c);
                            return value == null || value.isNull()
                                    ? "<null>"
                                    : value.isString() ? value.asString() : value.toString();
                        })
                .collect(Collectors.joining("|"));
    }

    private static boolean sameValue(JsonNode source, JsonNode platform, String dataType) {
        boolean sourceNull = source == null || source.isNull();
        boolean platformNull = platform == null || platform.isNull();
        if (sourceNull || platformNull) {
            return sourceNull == platformNull;
        }
        String a = source.isString() ? source.asString() : source.toString();
        String b = platform.isString() ? platform.asString() : platform.toString();
        return switch (dataType) {
            case "DECIMAL", "INTEGER" ->
                    new BigDecimal(a.strip()).compareTo(new BigDecimal(b.strip())) == 0;
            case "DATETIME" -> normalizeTime(a).equals(normalizeTime(b));
            case "DATE" -> a.substring(0, 10).equals(b.substring(0, 10));
            default -> a.equals(b);
        };
    }

    /** 원천 timestamp(시간대 없음)는 플랫폼에 서울 시각 +09:00 표기로 저장된다. 둘 다 그 지역 시각으로 맞춰 비교한다. */
    private static java.time.LocalDateTime normalizeTime(String value) {
        String v = value.strip().replace(' ', 'T');
        try {
            return java.time.OffsetDateTime.parse(v)
                    .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
                    .toLocalDateTime();
        } catch (java.time.format.DateTimeParseException e) {
            return java.time.LocalDateTime.parse(v);
        }
    }

    /** 모든 유형으로 연결을 다시 계산한다. keep=false면 결과만 보고 되돌린다. */
    private LinkRecomputeResult recomputeAll(boolean keep) {
        List<Long> types = jdbc.sql("select id from object_type").query(Long.class).list();
        return transaction.execute(
                status -> {
                    LinkRecomputeResult result = links.recomputeObjectTypes(types);
                    if (!keep) {
                        status.setRollbackOnly();
                    }
                    return result;
                });
    }

    private static boolean isEmpty(LinkRecomputeResult result) {
        return result.activatedLinkIds().isEmpty()
                && result.updatedLinkIds().isEmpty()
                && result.inactivatedLinkIds().isEmpty();
    }

    private static String describe(LinkRecomputeResult result) {
        return "activated="
                + result.activatedLinkIds().size()
                + " updated="
                + result.updatedLinkIds().size()
                + " inactivated="
                + result.inactivatedLinkIds().size()
                + " skippedRules="
                + result.skippedRuleIds().size();
    }

    // ---- 요약 ----

    private void summarize(
            String name, List<Map<String, Object>> rows, long started, long loaded, long drained) {
        List<Map<String, Object>> applied =
                rows.stream()
                        .filter(
                                r ->
                                        "APPLIED".equals(r.get("status"))
                                                && ((Number) r.get("applied")).intValue() > 0
                                                && !markers.contains(
                                                        ((Number) r.get("txId")).longValue()))
                        .toList();
        int events = applied.stream().mapToInt(r -> ((Number) r.get("events")).intValue()).sum();
        double applyTotal =
                applied.stream().mapToDouble(r -> ((Number) r.get("applyMs")).doubleValue()).sum();
        double linkTotal =
                applied.stream().mapToDouble(r -> ((Number) r.get("linkMs")).doubleValue()).sum();
        List<Double> apply =
                applied.stream()
                        .map(r -> ((Number) r.get("applyMs")).doubleValue())
                        .sorted()
                        .toList();
        List<Double> lag =
                applied.stream()
                        .filter(r -> r.get("commitToAppliedMs") != null)
                        .map(r -> ((Number) r.get("commitToAppliedMs")).doubleValue())
                        .sorted()
                        .toList();
        long heapPeak =
                samples.stream()
                        .filter(s -> name.equals(s.get("workload")))
                        .mapToLong(s -> ((Number) s.get("heapUsedMb")).longValue())
                        .max()
                        .orElse(0);
        long oldPeak =
                samples.stream()
                        .filter(s -> name.equals(s.get("workload")))
                        .mapToLong(s -> ((Number) s.get("oldAfterGcMb")).longValue())
                        .max()
                        .orElse(0);
        boolean unhealthy =
                samples.stream()
                        .filter(s -> name.equals(s.get("workload")))
                        .anyMatch(s -> Boolean.TRUE.equals(s.get("unhealthy")));
        String line =
                String.format(
                        "%s | tx=%d events=%d | load %.1fs, drained +%.1fs after load | apply p50=%.0fms p95=%.0fms max=%.0fms sum=%.1fs (link %.0f%%) | events/s=%.0f | commit->applied p50=%.1fs max=%.1fs | unhealthy=%s | heap peak %dMB, old after GC %dMB | statuses=%s",
                        name,
                        applied.size(),
                        events,
                        (loaded - started) / 1000.0,
                        (drained - loaded) / 1000.0,
                        pct(apply, 50),
                        pct(apply, 95),
                        pct(apply, 100),
                        applyTotal / 1000.0,
                        applyTotal == 0 ? 0 : 100 * linkTotal / applyTotal,
                        applyTotal == 0 ? 0 : events / (applyTotal / 1000.0),
                        pct(lag, 50) / 1000.0,
                        pct(lag, 100) / 1000.0,
                        unhealthy,
                        heapPeak,
                        oldPeak,
                        rows.stream()
                                .collect(
                                        Collectors.groupingBy(
                                                r -> (String) r.get("status"),
                                                Collectors.counting())));
        summary.add(line);
        System.out.println(line);
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 마지막 GC 뒤 old 영역 사용량(살아 있는 객체의 근사치). */
    private static long oldGenAfterGcMb() {
        long max = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("Old") && pool.getCollectionUsage() != null) {
                max = Math.max(max, pool.getCollectionUsage().getUsed() >> 20);
            }
        }
        return max;
    }

    private void sample() {
        try {
            for (Map<String, Object> row :
                    jdbc.sql(
                                    "select topic, next_offset, healthy_since is null as unhealthy, processing_lag_ms, recovery_reason from cdc_offset")
                            .query()
                            .listOfRows()) {
                Map<String, Object> sample = new LinkedHashMap<>(row);
                sample.put(
                        "heapUsedMb",
                        java.lang.management.ManagementFactory.getMemoryMXBean()
                                        .getHeapMemoryUsage()
                                        .getUsed()
                                >> 20);
                sample.put("oldAfterGcMb", oldGenAfterGcMb());
                sample.put("workload", workload);
                sample.put("at", Instant.now().toString());
                samples.add(sample);
            }
        } catch (RuntimeException ignored) {
            // 컨텍스트가 닫히는 중
        }
    }

    // ---- 원천 ----

    private static void loadSource() throws Exception {
        SOURCE.copyFileToContainer(MountableFile.forHostPath(DATA), "/data");
        SOURCE.copyFileToContainer(MountableFile.forHostPath(SCENARIO), "/scenario");
        exec(
                "psql -v ON_ERROR_STOP=1 -U "
                        + SOURCE.getUsername()
                        + " -d postgres -c 'create database mes'");
        exec(
                "cd /data && psql -q -v ON_ERROR_STOP=1 -U "
                        + SOURCE.getUsername()
                        + " -d erp -f load_erp.psql");
        exec(
                "cd /data && psql -q -v ON_ERROR_STOP=1 -U "
                        + SOURCE.getUsername()
                        + " -d mes -f load_mes.psql");
        for (String database : List.of("erp", "mes")) {
            exec(
                    "psql -v ON_ERROR_STOP=1 -U "
                            + SOURCE.getUsername()
                            + " -d "
                            + database
                            + " -c 'create publication axcore_cdc for all tables'");
        }
    }

    private static void psql(String database, String file) throws Exception {
        exec(
                "psql -q -v ON_ERROR_STOP=1 -U "
                        + SOURCE.getUsername()
                        + " -d "
                        + database
                        + " -f "
                        + file);
    }

    private static void exec(String command) throws Exception {
        ExecResult result = SOURCE.execInContainer("bash", "-c", command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(command + " → " + result.getStderr());
        }
    }

    private static Connection source(String database) throws SQLException {
        String url =
                "jdbc:postgresql://"
                        + SOURCE.getHost()
                        + ":"
                        + SOURCE.getMappedPort(5432)
                        + "/"
                        + database;
        return DriverManager.getConnection(url, SOURCE.getUsername(), SOURCE.getPassword());
    }

    /** 원천에 수정 하나를 커밋하고 그 트랜잭션 번호를 돌려준다. */
    private static long sourceUpdate(String database, String sql) throws SQLException {
        try (Connection connection = source(database);
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(sql);
            long txid;
            try (ResultSet rs =
                    statement.executeQuery("select pg_current_xact_id()::text::bigint")) {
                rs.next();
                txid = rs.getLong(1);
            }
            connection.commit();
            return txid;
        }
    }

    private static long sourceXmax(String database) throws SQLException {
        try (Connection connection = source(database);
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "select pg_snapshot_xmax(pg_current_snapshot())::text::bigint")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<List<Object>> keys(String database, String sql) throws SQLException {
        List<List<Object>> keys = new ArrayList<>();
        try (Connection connection = source(database);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> key = new ArrayList<>();
                for (int i = 1; i <= columns; i++) key.add(rs.getObject(i));
                keys.add(key);
            }
        }
        Collections.sort(keys, (a, b) -> a.toString().compareTo(b.toString()));
        return keys;
    }

    /** 플랫폼 원천 컬럼의 원천 형식을 실제 원천 카탈로그 표기(format_type)로 맞춘다. 시험 데이터는 플랫폼 자료형을 넣어 두었다. */
    private void alignSourceColumns() throws SQLException {
        for (String database : List.of("erp", "mes")) {
            try (Connection connection = source(database);
                    Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    """
                            select n.nspname || '.' || c.relname, a.attname, format_type(a.atttypid, a.atttypmod)
                            from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
                            where n.nspname in ('erp', 'mes') and c.relkind = 'r' and a.attnum > 0 and not a.attisdropped
                            """)) {
                while (rs.next()) {
                    jdbc.sql(
                                    """
                                    update source_column c set source_data_type = :type
                                    from source_resource r
                                    where r.id = c.source_resource_id and r.resource_key = :resource and c.column_name = :column
                                    """)
                            .param("type", rs.getString(3))
                            .param("resource", rs.getString(1))
                            .param("column", rs.getString(2))
                            .update();
                }
            }
        }
    }

    private long lastTxid(String topic) {
        return jdbc.sql(
                        "select coalesce(last_txid::text::bigint, -1) from cdc_offset where topic = :t")
                .param("t", topic)
                .query(Long.class)
                .optional()
                .orElse(-1L);
    }

    private static void registerConnector(
            String name,
            String database,
            String prefix,
            String excludeTables,
            String excludeColumns)
            throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        config.put("tasks.max", "1");
        config.put("plugin.name", "pgoutput");
        config.put("database.hostname", "source");
        config.put("database.port", "5432");
        config.put("database.dbname", database);
        config.put("database.user", SOURCE.getUsername());
        config.put("database.password", SOURCE.getPassword());
        config.put("database.sslmode", "disable");
        config.put("topic.prefix", prefix);
        config.put("slot.name", "axcore_" + database);
        config.put("publication.name", "axcore_cdc");
        config.put("publication.autocreate.mode", "disabled");
        config.put("snapshot.mode", "no_data");
        config.put("table.exclude.list", excludeTables);
        config.put("column.exclude.list", excludeColumns);
        config.put("decimal.handling.mode", "string");
        config.put("tombstones.on.delete", "false");
        config.put("heartbeat.interval.ms", "60000");
        config.put("provide.transaction.metadata", "true");
        config.put("skipped.operations", "none");
        config.put("topic.creation.default.replication.factor", "1");
        config.put("topic.creation.default.partitions", "1");
        config.put("transforms", "route");
        config.put("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter");
        config.put(
                "transforms.route.regex",
                "(" + prefix + "[.].*|__debezium-heartbeat[.]" + prefix + ")");
        config.put("transforms.route.replacement", prefix + ".all");
        String body = JSON.writeValueAsString(Map.of("name", name, "config", config));
        String base = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> created =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/connectors"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() >= 300) {
            throw new IllegalStateException(
                    "커넥터 등록 실패 " + created.statusCode() + " " + created.body());
        }
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .until(
                        () -> {
                            String status =
                                    http.send(
                                                    HttpRequest.newBuilder(
                                                                    URI.create(
                                                                            base
                                                                                    + "/connectors/"
                                                                                    + name
                                                                                    + "/status"))
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString())
                                            .body();
                            JsonNode node = JSON.readTree(status);
                            return node.path("connector")
                                            .path("state")
                                            .asString("")
                                            .equals("RUNNING")
                                    && node.path("tasks")
                                            .path(0)
                                            .path("state")
                                            .asString("")
                                            .equals("RUNNING");
                        });
    }

    private static void awaitTopic(String topic) throws Exception {
        try {
            await().atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> topics().contains(topic));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            String base = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
            HttpClient http = HttpClient.newHttpClient();
            String status = "";
            for (String name : List.of("cdc-erp", "cdc-mes")) {
                status +=
                        http.send(
                                                HttpRequest.newBuilder(
                                                                URI.create(
                                                                        base
                                                                                + "/connectors/"
                                                                                + name
                                                                                + "/status"))
                                                        .build(),
                                                HttpResponse.BodyHandlers.ofString())
                                        .body()
                                + " | ";
            }
            String logs = CONNECT.getLogs();
            throw new IllegalStateException(
                    "토픽 "
                            + topic
                            + " 없음. topics="
                            + topics()
                            + " status="
                            + status
                            + " connect logs tail="
                            + logs.substring(Math.max(0, logs.length() - 6000)),
                    e);
        }
    }

    private static List<String> topics() throws Exception {
        ExecResult result =
                KAFKA.execInContainer(
                        "/opt/kafka/bin/kafka-topics.sh",
                        "--bootstrap-server",
                        "kafka:19092",
                        "--list");
        if (result.getExitCode() != 0) {
            return List.of("<list failed: " + result.getStderr().strip() + ">");
        }
        return result.getStdout().lines().toList();
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.write(
                file,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, String.valueOf(fallback)));
    }
}

package com.axcore.platform.source.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.source.service.CdcReloadJob;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * CDC 소비 루프(Jira 198-6)를 실제 Kafka(서버와 같은 apache/kafka 4.2.2)와 PostgreSQL로 확인한다. 198-2의 실제 Debezium
 * 메시지 37건({@code cdc-tx/debezium-tx-messages.jsonl}, 원천 트랜잭션 756~765)을 같은 순서로 원천 토픽에 넣는다. 줄 번호 - 1이
 * Kafka offset이다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.cdc.consumer.enabled=true",
            "axcore.cdc.consumer.poll-timeout=200ms",
            "axcore.cdc.consumer.refresh-interval=300ms",
            "axcore.cdc.consumer.retry-delay=300ms",
            "axcore.cdc.consumer.max-apply-failures=3",
            // 시험 자료의 메시지 시각은 자료를 받은 날이다. 처리 지연으로 비정상이 되지 않게 넉넉히 둔다
            "axcore.cdc.consumer.max-lag=36500d",
            "axcore.cdc.consumer.stale-after=1h"
        })
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class CdcConsumerLoopTests {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static List<String> lines;

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("axcore.cdc.consumer.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // 다른 배치 작업이 시험 중에 끼어들지 않게 막는다. 감시 작업이 시험 원천에 다시 읽기를 넣지 않게 한다(architecture.md 배치).
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    // 처음 소비할 때의 epoch 기준값(원천 xmax 자리). 시험 원천에는 접속하지 않는다. 자료의 txId는 756~765다.
    @MockitoBean CdcStartReference startReference;

    // 364: 한 시험 원천의 반영만 실패하게 한다. 다른 시험 원천은 실제 반영기를 그대로 쓴다.
    @MockitoSpyBean CdcTransactionApplier applier;

    @Autowired CdcConsumerSupervisor supervisor;
    @Autowired JdbcClient jdbc;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in =
                CdcConsumerLoopTests.class
                        .getClassLoader()
                        .getResourceAsStream("cdc-tx/debezium-tx-messages.jsonl")) {
            lines =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(l -> !l.isBlank())
                            .toList();
        }
    }

    @Test
    void appliesSourceTopicInOrderAndMovesPastTrailingHeartbeats() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        produce(f.topic, lines);
        f.register();

        // 마지막 END(offset 32) 뒤의 heartbeat 4건까지 지나 위치가 37이 된다
        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 37L);

        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
        assertThat(f.status(f.workOrderMapping, "8")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(f.status(f.workOrderMapping, "9")).isEqualTo("ACTIVE");
        assertThat(f.status(f.equipmentMapping, "3")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(f.property(f.equipmentMapping, "30", "name")).isEqualTo("EQ-3");
        assertThat(f.property(f.docMapping, "1", "n")).isEqualTo("1");
        Map<String, Object> offset = offset(f.topic);
        assertThat(offset.get("last_txid")).isEqualTo("765");
        assertThat(offset.get("last_message_at")).isNotNull();
        assertThat(offset.get("healthy_since")).isNotNull();
        assertThat(
                        jdbc.sql(
                                        "select count(*) from sync_run where data_source_id = :s and trigger_kind = 'CDC'")
                                .param("s", f.sourceId)
                                .query(Integer.class)
                                .single())
                .isEqualTo(7);
    }

    @Test
    void restartContinuesFromStoredOffsetAndResendKeepsValues() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        // 757 END(offset 13)까지만 넣는다
        produce(f.topic, lines.subList(0, 14));
        f.register();
        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 14L);
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");

        supervisor.stop();
        // 멈춘 사이 나머지가 쌓이고, 위치도 잃었다고 하자(처음부터 다시 받음 = 재전송)
        produce(f.topic, lines.subList(14, lines.size()));
        jdbc.sql("update cdc_offset set next_offset = 0 where topic = :t")
                .param("t", f.topic)
                .update();
        supervisor.start();

        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 37L);
        // 다시 받은 756이 수정값을 되돌리지 않는다(LSN)
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
        assertThat(f.status(f.workOrderMapping, "8")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(f.property(f.equipmentMapping, "30", "name")).isEqualTo("EQ-3");
    }

    @Test
    void brokenMessageStopsSourceUntilRecoveryIsCleared() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        // 756 다음(offset 11)에 모양이 깨진 메시지가 끼었다
        List<String> broken = new java.util.ArrayList<>(lines.subList(0, 11));
        broken.add("{\"payload\": {\"what\": 1}}");
        produce(f.topic, broken);
        f.register();

        // 소비자가 아직 cdc_offset 행을 만들기 전이면 행이 없다. 그때도 계속 기다린다
        await().atMost(WAIT)
                .until(
                        () ->
                                "PROTOCOL_ERROR"
                                        .equals(
                                                jdbc.sql(
                                                                "select recovery_reason from cdc_offset where topic = :t")
                                                        .param("t", f.topic)
                                                        .query(String.class)
                                                        .optional()
                                                        .orElse(null)));
        assertThat(nextOffset(f.topic)).isEqualTo(11L);
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7");
        assertThat(offset(f.topic).get("healthy_since")).isNull();

        // 복구 중에는 뒤에 온 트랜잭션(757)도 반영하지 않는다
        produce(f.topic, lines.subList(11, 14));
        sleep(Duration.ofSeconds(2));
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7");

        // 199가 복구를 마치고 깨진 메시지 다음으로 위치를 옮겨 표시를 풀었다고 하자
        jdbc.sql(
                        """
                        update cdc_offset set recovery_reason = null, recovery_at = null, next_offset = 12
                        where topic = :t
                        """)
                .param("t", f.topic)
                .update();
        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 15L);
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
    }

    /** 364: 같은 위치의 반영이 계속 실패하면 끝없이 다시 시도하지 않고 정해진 횟수 뒤 복구(APPLY_FAILED)로 돌린다. */
    @Test
    void repeatedApplyFailureAtSamePositionTurnsIntoRecovery() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("반영 실패 시험"))
                .when(applier)
                .apply(argThat(s -> s != null && s.topic().equals(f.topic)), any(), any(), any());
        produce(f.topic, lines.subList(0, 14));
        f.register();

        await().atMost(WAIT)
                .until(() -> "APPLY_FAILED".equals(offsetOrEmpty(f.topic).get("recovery_reason")));
        Map<String, Object> offset = offset(f.topic);
        assertThat(offset.get("healthy_since")).isNull();
        // 위치는 첫 트랜잭션 앞에 그대로다. 복구가 풀리면 다시 읽은 스냅샷 뒤부터 이어 받는다
        assertThat(nextOffset(f.topic)).isLessThan(14L);
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isNull();
    }

    /** 383: 반영 중 일시 장애(교착)는 같은 위치에서 상한보다 많이 나도 세지 않는다. 풀리면 이어서 반영하고 복구로 가지 않는다. */
    @Test
    void transientApplyFailuresAreRetriedWithoutRecovery() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        CannotAcquireLockException deadlock = new CannotAcquireLockException("교착 시험");
        doThrow(deadlock)
                .doThrow(deadlock)
                .doThrow(deadlock)
                .doThrow(deadlock)
                .doThrow(deadlock)
                .doCallRealMethod()
                .when(applier)
                .apply(argThat(s -> s != null && s.topic().equals(f.topic)), any(), any(), any());
        produce(f.topic, lines);
        f.register();

        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 37L);
        assertThat(offset(f.topic).get("recovery_reason")).isNull();
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7b");
    }

    /** 383: 위치를 잡다가(처음 소비할 때 원천에서 시작 기준을 읽음) 계속 실패해도 반영 실패가 아니라 세지 않는다. 원천에 닿으면 이어서 반영한다. */
    @Test
    void positioningFailuresAreRetriedWithoutRecovery() {
        Fixture f = new Fixture();
        AtomicInteger failed = new AtomicInteger();
        when(startReference.read(anyLong()))
                .thenAnswer(
                        invocation -> {
                            long sourceId = invocation.getArgument(0);
                            String code =
                                    jdbc.sql("select code from data_source where id = :id")
                                            .param("id", sourceId)
                                            .query(String.class)
                                            .optional()
                                            .orElse("");
                            if (code.equals(f.code) && failed.getAndIncrement() < 5) {
                                throw new IllegalStateException("원천 DB 접속 실패 시험");
                            }
                            return 750L;
                        });
        produce(f.topic, lines);
        f.register();

        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 37L);
        assertThat(failed.get()).isGreaterThan(3);
        assertThat(offset(f.topic).get("recovery_reason")).isNull();
    }

    private Map<String, Object> offsetOrEmpty(String topic) {
        return jdbc
                .sql("select recovery_reason from cdc_offset where topic = :t")
                .param("t", topic)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(Map.of());
    }

    /**
     * 199: 복구가 풀리면 저장된 위치가 아니라 이어 받을 기준 시각 뒤에 쓰인 메시지부터 읽는다. 복구를 일으킨 메시지를 다시 만나지 않고, 기준 시각 앞에 시작한
     * 트랜잭션의 나머지(757 END)와 heartbeat는 건너뛰며, 처음 만나는 BEGIN(758)부터 반영하고 그 위치를 저장한다.
     */
    @Test
    void resumesFromFirstBeginAfterResumeTimeWhenRecoveryIsReleased() {
        when(startReference.read(anyLong())).thenReturn(750L);
        Fixture f = new Fixture();
        List<String> before = new java.util.ArrayList<>(lines.subList(0, 11));
        before.add("{\"payload\": {\"what\": 1}}");
        // 757의 BEGIN과 변경까지(offset 12·13)
        before.addAll(lines.subList(11, 13));
        produce(f.topic, before);
        f.register();
        await().atMost(WAIT)
                .until(() -> "PROTOCOL_ERROR".equals(offset(f.topic).get("recovery_reason")));

        sleep(Duration.ofMillis(200));
        Instant resumeAfter = Instant.now();
        sleep(Duration.ofMillis(200));
        // 757 END(14), 트랜잭션 밖이 아닐 수도 있는 heartbeat(15), 758(16~19)
        List<String> after = new java.util.ArrayList<>();
        after.add(lines.get(13));
        after.add(lines.get(0));
        after.addAll(lines.subList(14, 18));
        produce(f.topic, after);
        // 다시 읽기를 마친 전체 읽기 저장 트랜잭션이 복구를 풀었다고 하자
        jdbc.sql(
                        """
                        update cdc_offset
                        set recovery_reason = null, recovery_at = null, last_txid = null,
                            resume_after = :resumeAfter
                        where topic = :t
                        """)
                .param("resumeAfter", java.sql.Timestamp.from(resumeAfter))
                .param("t", f.topic)
                .update();

        await().atMost(WAIT).until(() -> nextOffset(f.topic) == 20L);
        // 757(WO-7 → WO-7b)은 기준 시각 앞에 시작했으므로 반영하지 않고, 758(8 삭제, 9 추가)은 반영한다
        assertThat(f.property(f.workOrderMapping, "7", "wo_no")).isEqualTo("WO-7");
        assertThat(f.status(f.workOrderMapping, "8")).isEqualTo("DELETED_AT_SOURCE");
        assertThat(f.status(f.workOrderMapping, "9")).isEqualTo("ACTIVE");
        Map<String, Object> offset = offset(f.topic);
        assertThat(offset.get("resume_after")).isNull();
        assertThat(offset.get("recovery_reason")).isNull();
        assertThat(offset.get("last_txid")).isEqualTo("758");
        assertThat(offset.get("healthy_since")).isNotNull();
    }

    private static void produce(String topic, List<String> values) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (String value : values) {
                producer.send(new ProducerRecord<>(topic, 0, null, value));
            }
            producer.flush();
        }
    }

    private Long nextOffset(String topic) {
        return jdbc.sql("select next_offset from cdc_offset where topic = :t")
                .param("t", topic)
                .query(Long.class)
                .optional()
                .orElse(-1L);
    }

    private Map<String, Object> offset(String topic) {
        return jdbc
                .sql(
                        """
                        select next_offset, last_txid::text as last_txid, last_message_at, healthy_since,
                               recovery_reason, resume_after
                        from cdc_offset where topic = :t
                        """)
                .param("t", topic)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(new java.util.HashMap<>());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 시험마다 새 원천(새 토픽). 표 세 개와 매핑은 {@link #register()}에서 만든다. 만들면 감시 루프가 소비를 시작한다. */
    private final class Fixture {

        final String code = "CDC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        final String topic = code + ".all";
        long sourceId;
        long equipmentMapping;
        long workOrderMapping;
        long docMapping;

        void register() {
            sourceId =
                    jdbc.sql(
                                    """
                                    insert into data_source (code, name, source_kind)
                                    values (:code, 'CDC 소비 시험', 'POSTGRES') returning id
                                    """)
                            .param("code", code)
                            .query(Long.class)
                            .single();
            long equipment = resource("erp.equipment", "id:bigint", "name:text");
            long workOrder = resource("erp.work_order", "id:bigint", "eq_id:bigint", "wo_no:text");
            long doc = resource("erp.doc", "id:bigint", "n:integer", "big:text");
            equipmentMapping = mapping(equipment, "name:STRING");
            workOrderMapping = mapping(workOrder, "wo_no:STRING", "eq_id:INTEGER");
            docMapping = mapping(doc, "n:INTEGER", "big:STRING");
        }

        private long resource(String key, String... columns) {
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

        private long mapping(long resourceId, String... properties) {
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
                long propertyId =
                        jdbc.sql(
                                        """
                                        insert into property_definition (object_type_id, code, name, data_type)
                                        values (:t, :code, :code, :type) returning id
                                        """)
                                .param("t", type)
                                .param("code", parts[0])
                                .param("type", parts[1])
                                .query(Long.class)
                                .single();
                jdbc.sql(
                                """
                                insert into property_source_mapping (source_mapping_id, source_resource_id,
                                                                     property_definition_id, source_column_id)
                                values (:m, :r, :p,
                                        (select id from source_column where source_resource_id = :r and column_name = :c))
                                """)
                        .param("m", mapping)
                        .param("r", resourceId)
                        .param("p", propertyId)
                        .param("c", parts[0])
                        .update();
            }
            return mapping;
        }

        String property(long mappingId, String id, String code) {
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

        String status(long mappingId, String id) {
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
    }
}

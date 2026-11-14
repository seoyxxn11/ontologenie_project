package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobPoller;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * CDC 테이블 자동 다시 읽기 감시(S15P31C106-330)가 어떤 테이블에 실행을 넣는지 실제 PostgreSQL로 확인한다. 원천 테이블과 매핑은 행으로 직접 넣는다.
 * 같은 DB를 다른 시험과 함께 쓰므로 이 시험이 만든 테이블의 결과만 본다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class CdcReloadServiceTests {

    // 주기 실행이 시험 도중 작업을 넣거나 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired CdcReloadService service;
    @Autowired FullLoadPlanner planner;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    private long sourceId;
    private long typeId;

    @BeforeEach
    void prepare() {
        sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, '감시 시험 원천', 'POSTGRES') returning id
                                """)
                        .param(
                                "code",
                                "RW_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name) values ('T-' || gen_random_uuid(), '작업지시') returning id")
                        .query(Long.class)
                        .single();
    }

    @AfterEach
    void removeQueuedRuns() {
        jdbc.sql("delete from sync_run where trigger_kind = 'RECOVERY' and status = 'QUEUED'")
                .update();
    }

    @Test
    void queuesFirstLoadAsRecoveryWithReasonInAuditLine() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);

        List<Long> queued = service.queueDueReloads();

        long runId = queuedRun(table);
        assertThat(queued).contains(runId);
        assertThat(
                        jdbc.sql(
                                        """
                                        select data_source_id, trigger_kind, status, requested_by
                                        from sync_run where id = :id
                                        """)
                                .param("id", runId)
                                .query()
                                .singleRow())
                .containsEntry("data_source_id", sourceId)
                .containsEntry("trigger_kind", "RECOVERY")
                .containsEntry("status", "QUEUED")
                .containsEntry("requested_by", null);
        assertThat(
                        jdbc.sql(
                                        """
                                        select mode, status from sync_run_resource
                                        where sync_run_id = :id and source_resource_id = :r
                                        """)
                                .param("id", runId)
                                .param("r", table)
                                .query()
                                .singleRow())
                .containsEntry("mode", "FULL")
                .containsEntry("status", "QUEUED");
        Map<String, Object> audit = auditLine(runId);
        assertThat(audit)
                .containsEntry("event_kind", "SYNC_RUN")
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "SUCCEEDED");
        assertThat(json.readValue((String) audit.get("result"), Map.class))
                .containsEntry("reason", "FIRST_LOAD")
                .containsEntry("status", "QUEUED");
    }

    @Test
    void reasonIsRecoveryThenFirstLoadThenReloadRequested() {
        long loadedBefore = cdcTable("erp.loaded_before");
        mapping(loadedBefore, true);
        markLoadedOnce(loadedBefore);
        long neverLoaded = cdcTable("erp.never_loaded");
        mapping(neverLoaded, true);

        service.queueDueReloads();

        assertThat(reason(queuedRun(loadedBefore))).isEqualTo("RELOAD_REQUESTED");
        assertThat(reason(queuedRun(neverLoaded))).isEqualTo("FIRST_LOAD");

        removeQueuedRuns();
        recovery("SLOT_LOST");

        service.queueDueReloads();

        assertThat(reason(queuedRun(loadedBefore))).isEqualTo("RECOVERY");
        assertThat(reason(queuedRun(neverLoaded))).isEqualTo("RECOVERY");
    }

    @Test
    void skipsTablesThatNeedNoReloadOrCannotBeReadYet() {
        long noActiveMapping = cdcTable("erp.inactive");
        mapping(noActiveMapping, false);
        long noMapping = cdcTable("erp.unmapped");
        long alreadyLoaded = cdcTable("erp.loaded");
        mapping(alreadyLoaded, true);
        jdbc.sql("update source_resource set loaded_lsn = 10 where id = :id")
                .param("id", alreadyLoaded)
                .update();
        long fullCompare = resource("erp.view", "VIEW", "FULL_COMPARE");
        mapping(fullCompare, true);

        service.queueDueReloads();

        assertThat(activeRuns(noActiveMapping)).isZero();
        assertThat(activeRuns(noMapping)).isZero();
        assertThat(activeRuns(alreadyLoaded)).isZero();
        assertThat(activeRuns(fullCompare)).isZero();
    }

    @Test
    void doesNotQueueTwiceWhileRunIsWaiting() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);

        service.queueDueReloads();
        service.queueDueReloads();

        assertThat(activeRuns(table)).isOne();
        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from sync_run s
                                        where s.data_source_id = :s and s.trigger_kind = 'RECOVERY'
                                        """)
                                .param("s", sourceId)
                                .query(Long.class)
                                .single())
                .as("넣지 못한 실행 행이 남지 않는다")
                .isOne();
    }

    @Test
    void waitsUntilConnectorIsAlignedWhenSourceChanged() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);
        recovery("SOURCE_CHANGED");

        service.queueDueReloads();

        assertThat(activeRuns(table)).isZero();
    }

    @Test
    void retriesFailedTableOnlyAfterRetryDelay() {
        long recent = cdcTable("erp.failed_recently");
        mapping(recent, true);
        failedRun(recent, "1 minute", "원천에 접속하지 못했습니다.");
        long old = cdcTable("erp.failed_before");
        mapping(old, true);
        failedRun(old, "6 minutes", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();

        assertThat(activeRuns(recent)).isZero();
        assertThat(activeRuns(old)).isOne();
    }

    @Test
    void stopsAfterThreeFailuresSinceLastSuccessUntilManualSuccess() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);
        for (int i = 0; i < 3; i++) {
            failedRun(table, (10 + i) + " minutes", "원천에 접속하지 못했습니다.");
        }

        service.queueDueReloads();
        assertThat(activeRuns(table)).isZero();

        // 관리자가 073으로 넣은 실행이 성공하면 횟수가 다시 시작된다. 성공 뒤 매핑이 바뀌어 다시 표시가 붙었다.
        succeededManualRun(table, "8 minutes");
        failedRun(table, "7 minutes", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();
        assertThat(activeRuns(table)).isOne();
    }

    /** 관리자 수동 실행은 여러 테이블을 묶어 다른 테이블 때문에 함께 실패할 수 있어 멈춤 횟수에 넣지 않는다. 다시 넣기를 미루는 데는 쓴다. */
    @Test
    void manualFailuresDelayRetryButDoNotStopAutoReload() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);
        for (int i = 0; i < 3; i++) {
            failedManualRun(table, (10 + i) + " minutes", "원천에 접속하지 못했습니다.");
        }

        service.queueDueReloads();
        assertThat(activeRuns(table)).isOne();

        long recent = cdcTable("erp.equipment");
        mapping(recent, true);
        failedManualRun(recent, "1 minute", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();
        assertThat(activeRuns(recent)).isZero();
    }

    /** 감시 작업 실패와 수동 실패가 섞이면 감시 작업 실패만 세어 멈춘다. */
    @Test
    void countsOnlyWatcherFailuresWhenMixedWithManualFailures() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);
        failedRun(table, "14 minutes", "원천에 접속하지 못했습니다.");
        failedManualRun(table, "13 minutes", "원천에 접속하지 못했습니다.");
        failedRun(table, "12 minutes", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();
        assertThat(activeRuns(table)).isOne();

        removeQueuedRuns();
        failedRun(table, "11 minutes", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();
        assertThat(activeRuns(table)).isZero();
    }

    @Test
    void mappingChangedDuringReadIsNotCountedAsFailure() {
        long table = cdcTable("erp.work_order");
        mapping(table, true);
        for (int i = 0; i < 3; i++) {
            failedRun(
                    table, i + " minutes", SyncRunJob.MAPPING_CHANGED.formatted("erp.work_order"));
        }

        service.queueDueReloads();

        assertThat(activeRuns(table)).isOne();
    }

    /**
     * 적재 계획을 만들 수 없는 테이블(402)은 한 번 넣어 실행이 사유를 수집 실패로 남기게 한다. 같은 사유로 막혀 있는 동안은 다시 넣지 않아 실패 기록이 쌓이지
     * 않고 멈춤 횟수도 오르지 않는다. 매핑을 고치면 다음 주기에 넣는다.
     */
    @Test
    void queuesBlockedTableOnceThenWaitsUntilMappingIsFixed() {
        long table = cdcTable("erp.employee");
        long mapping = mappingWithKey(table, "emp_name");

        service.queueDueReloads();
        assertThat(activeRuns(table)).as("처음에는 넣어 사유를 남긴다").isOne();

        removeQueuedRuns();
        failedRun(table, "6 minutes", planFailure(table));
        service.queueDueReloads();
        assertThat(activeRuns(table)).as("같은 사유로 막혀 있으면 넣지 않는다").isZero();

        jdbc.sql(
                        "update object_source_mapping set key_columns = '[\"emp_no\"]'::jsonb where id = :id")
                .param("id", mapping)
                .update();
        service.queueDueReloads();
        assertThat(activeRuns(table)).as("고치면 넣는다").isOne();
    }

    /**
     * 원천 데이터 정책 때문에 계획을 만들지 못한 실패는 멈춤 횟수에 세지 않는다(402). 세면 다른 실패와 합쳐 3번이 되어, 매핑을 고친 뒤에도 자동으로 넣지
     * 않는다(복구 중이면 원천 전체 CDC가 관리자 실행 전까지 멈춘다). 재시도 대기에는 쓴다.
     */
    @Test
    void policyBlockedFailuresDoNotStopAutoReloadAfterFix() {
        long table = cdcTable("erp.employee");
        long mapping = mappingWithKey(table, "emp_name");
        String reason = planFailure(table);
        failedRun(table, "20 minutes", "원천에 접속하지 못했습니다.");
        failedRun(table, "15 minutes", reason);
        failedRun(table, "10 minutes", reason.replace("emp_name", "emp_name, position"));
        jdbc.sql(
                        "update object_source_mapping set key_columns = '[\"emp_no\"]'::jsonb where id = :id")
                .param("id", mapping)
                .update();

        service.queueDueReloads();

        assertThat(activeRuns(table)).isOne();
    }

    /** 사유가 바뀌면(다른 위반, 전에는 다른 이유로 실패) 다시 한 번 넣어 새 사유를 남긴다. */
    @Test
    void queuesBlockedTableAgainWhenReasonDiffers() {
        long table = cdcTable("erp.employee");
        mappingWithKey(table, "emp_name");
        failedRun(table, "6 minutes", "원천에 접속하지 못했습니다.");

        service.queueDueReloads();

        assertThat(activeRuns(table)).isOne();
    }

    /**
     * 복구 중인 원천은 적재 계획을 만들 수 없는 테이블을 기다리지 않고 푼다(402). 기다리면 그 테이블의 다시 읽기가 끝나지 않아 원천 전체의 CDC가 멈춘다. 막힌
     * 테이블은 다시 읽기 표시가 남는다.
     */
    @Test
    void releasesRecoveryWithoutWaitingForBlockedTable() {
        recovery("SLOT_LOST");
        long loaded = cdcTable("erp.work_order");
        mapping(loaded, true);
        long runAfterRecovery = finishedRun(loaded, "-1 minute", "SUCCEEDED", null, null);
        jdbc.sql(
                        "update source_resource set loaded_lsn = 10, full_load_done_gen = :gen where id = :id")
                .param("gen", runAfterRecovery)
                .param("id", loaded)
                .update();
        long blocked = cdcTable("erp.employee");
        mappingWithKey(blocked, "emp_name");

        service.queueDueReloads();

        assertThat(recoveryReason()).isNull();
        assertThat(
                        jdbc.sql("select loaded_lsn is null from source_resource where id = :id")
                                .param("id", blocked)
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    /** 막힌 테이블이 없으면 이 경로로 풀지 않는다. 다시 읽기가 끝나지 않은 테이블이 있는 동안은 복구가 남는다. */
    @Test
    void keepsRecoveryWhileLoadableTableIsNotReloaded() {
        recovery("SLOT_LOST");
        long pending = cdcTable("erp.work_order");
        mapping(pending, true);

        service.queueDueReloads();

        assertThat(recoveryReason()).isEqualTo("SLOT_LOST");
    }

    @Test
    void policyBlockedPatternMatchesEveryPolicyMessage() {
        assertThat(
                        jdbc.sql("select :m like :p escape '\'")
                                .param("m", "원천 데이터 정책상 민감 컬럼을 매핑 키로 씁니다: mes.x [a_b]")
                                .param("p", CdcReloadService.POLICY_BLOCKED_PATTERN)
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void likePatternKeepsTextLiteralAndMatchesOnlyTablePart() {
        String pattern = CdcReloadService.likePattern("a_b %s 100%");

        assertThat(pattern).isEqualTo("a\\_b % 100\\%");
        assertThat(
                        jdbc.sql("select :v like :p escape '\\'")
                                .param("v", "a_b erp.x 100%")
                                .param("p", pattern)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc.sql("select :v like :p escape '\\'")
                                .param("v", "aXb erp.x 100%")
                                .param("p", pattern)
                                .query(Boolean.class)
                                .single())
                .isFalse();
    }

    private long cdcTable(String key) {
        return resource(key, "TABLE", "CDC");
    }

    private long resource(String key, String kind, String strategy) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy)
                        values (:s, :key, :kind, true, :strategy) returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .param("kind", kind)
                .param("strategy", strategy)
                .query(Long.class)
                .single();
    }

    private void mapping(long resourceId, boolean active) {
        jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns,
                                                           is_active)
                        values (:r, :t, '["id"]'::jsonb, :active)
                        """)
                .param("r", resourceId)
                .param("t", typeId)
                .param("active", active)
                .update();
    }

    private long mappingWithKey(long resourceId, String key) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns,
                                                           is_active)
                        values (:r, :t, jsonb_build_array(:key), true) returning id
                        """)
                .param("r", resourceId)
                .param("t", typeId)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private String planFailure(long resourceId) {
        try {
            planner.plan(resourceId);
        } catch (QueuedJobFailure e) {
            return e.getMessage();
        }
        throw new AssertionError("적재 계획이 만들어졌습니다: " + resourceId);
    }

    private String recoveryReason() {
        return jdbc.sql("select recovery_reason from cdc_offset where data_source_id = :s")
                .param("s", sourceId)
                .query((rs, row) -> rs.getString(1))
                .list()
                .getFirst();
    }

    /** 예전에 전체 읽기가 끝났고, 그 뒤 다시 읽기 표시가 붙은 상태. */
    private void markLoadedOnce(long resourceId) {
        long runId = succeededRun(resourceId, "1 hour");
        jdbc.sql("update source_resource set full_load_done_gen = :gen where id = :id")
                .param("gen", runId)
                .param("id", resourceId)
                .update();
    }

    private void recovery(String reason) {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset,
                                                recovery_reason, recovery_at)
                        values (:topic, 0, :s, 0, :reason, now())
                        on conflict (topic, partition_no)
                        do update set recovery_reason = excluded.recovery_reason, recovery_at = now()
                        """)
                .param("topic", "rw-" + sourceId + ".all")
                .param("s", sourceId)
                .param("reason", reason)
                .update();
    }

    private void failedRun(long resourceId, String ago, String message) {
        finishedRun(resourceId, ago, "FAILED", message, null);
    }

    /** 관리자가 073으로 넣었다가 실패한 실행. */
    private void failedManualRun(long resourceId, String ago, String message) {
        finishedRun(resourceId, ago, "FAILED", message, admin());
    }

    /** 관리자가 073으로 넣어 성공한 실행. */
    private void succeededManualRun(long resourceId, String ago) {
        finishedRun(resourceId, ago, "SUCCEEDED", null, admin());
    }

    private long admin() {
        return jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id,
                                             must_change_password)
                        values ('reload-' || gen_random_uuid(), '감시 시험 관리자', 'x',
                                (select id from role where code = 'ADMIN'), false)
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private long succeededRun(long resourceId, String ago) {
        return finishedRun(resourceId, ago, "SUCCEEDED", null, null);
    }

    /** requestedBy가 있으면 관리자 수동 실행(MANUAL), 없으면 감시 작업 실행(RECOVERY)이다. */
    private long finishedRun(
            long resourceId, String ago, String status, String message, Long requestedBy) {
        long runId =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, requested_at,
                                                      started_at, finished_at, error_message, requested_by)
                                values (:s, case when cast(:by as bigint) is null then 'RECOVERY' else 'MANUAL' end, :status,
                                        now() - cast(:ago as interval), now() - cast(:ago as interval),
                                        now() - cast(:ago as interval), :message, cast(:by as bigint))
                                returning id
                                """)
                        .param("s", sourceId)
                        .param("by", requestedBy)
                        .param("status", status)
                        .param("ago", ago)
                        .param("message", message)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode,
                                                       status, started_at, finished_at, error_message)
                        values (:run, :s, :r, 'FULL', :status, now() - cast(:ago as interval),
                                now() - cast(:ago as interval), :message)
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .param("status", status)
                .param("ago", ago)
                .param("message", message)
                .update();
        return runId;
    }

    private long queuedRun(long resourceId) {
        return jdbc.sql(
                        """
                        select sync_run_id from sync_run_resource
                        where source_resource_id = :r and status = 'QUEUED'
                        """)
                .param("r", resourceId)
                .query(Long.class)
                .single();
    }

    private long activeRuns(long resourceId) {
        return jdbc.sql(
                        """
                        select count(*) from sync_run_resource
                        where source_resource_id = :r and status in ('QUEUED', 'RUNNING')
                        """)
                .param("r", resourceId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> auditLine(long runId) {
        return jdbc.sql(
                        """
                        select event_kind, actor_type, entry_point, status, result::text as result
                        from action_execution where target_type = 'sync_run' and target_id = :id
                        """)
                .param("id", runId)
                .query()
                .singleRow();
    }

    private String reason(long runId) {
        return (String)
                json.readValue((String) auditLine(runId).get("result"), Map.class).get("reason");
    }
}

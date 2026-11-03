package com.axcore.platform.source;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 원천 자원·컬럼 목록(API 014·015). 목록은 연결 검사(배치)가 채우므로 여기서는 직접 넣는다. */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourceCatalogApiTests extends SourceApiTestSupport {

    @Test
    void listsResourcesWithStrategyAndLastSync() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long workOrder = insertResource(sourceId, "erp.work_order", "TABLE", true, "CDC", 4096L);
        long employee = insertResource(sourceId, "erp.employee", "TABLE", true, "CDC", null);
        insertResource(sourceId, "erp.v_stock", "VIEW", false, "FULL_COMPARE", null);
        long syncRunId = insertSucceededSync(sourceId, workOrder);
        String path = BASE_PATH + "/" + sourceId + "/resources";

        mockMvc.perform(get(path).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].resourceKey",
                                contains("erp.employee", "erp.v_stock", "erp.work_order")))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.data[0].id").value(employee))
                .andExpect(jsonPath("$.data[0].syncStrategy").value("CDC"))
                .andExpect(jsonPath("$.data[0].loadedLsn").value(nullValue()))
                .andExpect(jsonPath("$.data[0].lastSyncRunId").value(nullValue()))
                .andExpect(jsonPath("$.data[2].isReadable").value(true))
                .andExpect(jsonPath("$.data[2].loadedLsn").value(4096))
                .andExpect(jsonPath("$.data[2].lastSyncRunId").value(syncRunId))
                .andExpect(jsonPath("$.data[2].lastSyncFinishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[2].sourceMetadata.comment").value("작업지시"));
        mockMvc.perform(get(path).param("resourceKind", "VIEW").cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].resourceKey", contains("erp.v_stock")))
                .andExpect(jsonPath("$.data[0].isReadable").value(false))
                .andExpect(jsonPath("$.data[0].syncStrategy").value("FULL_COMPARE"));
        mockMvc.perform(
                        get(path)
                                .param("isReadable", "true")
                                .param("q", "WORK")
                                .cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].resourceKey", contains("erp.work_order")));
        mockMvc.perform(get(path).param("q", "%").cookie(admin.session()))
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get(BASE_PATH + "/999999999/resources").cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsColumnsInSourceOrder() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long otherSourceId = createPostgresSource(admin, uniqueCode());
        long resourceId = insertResource(sourceId, "erp.employee", "TABLE", true, "CDC", null);
        insertColumn(resourceId, "emp_name", 2, false, true);
        insertColumn(resourceId, "emp_no", 1, true, true);
        insertColumn(resourceId, "old_code", 3, false, false);
        String path = BASE_PATH + "/" + sourceId + "/resources/" + resourceId + "/columns";

        mockMvc.perform(get(path).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].columnName", contains("emp_no", "emp_name", "old_code")))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.data[0].isKeyPart").value(true))
                .andExpect(jsonPath("$.data[0].isNullable").value(false))
                .andExpect(jsonPath("$.data[0].sourceDataType").value("varchar"))
                .andExpect(jsonPath("$.data[0].sourceComment").value("emp_no 설명"))
                .andExpect(jsonPath("$.data[2].isPresent").value(false));
        mockMvc.perform(
                        get(BASE_PATH
                                        + "/"
                                        + otherSourceId
                                        + "/resources/"
                                        + resourceId
                                        + "/columns")
                                .cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    private long insertResource(
            long sourceId,
            String key,
            String kind,
            boolean readable,
            String strategy,
            Long loadedLsn) {
        return jdbcClient
                .sql(
                        """
                        insert into source_resource
                            (data_source_id, resource_key, resource_kind, source_metadata, is_readable,
                             sync_strategy, loaded_lsn)
                        values (:sourceId, :key, :kind, '{"comment": "작업지시"}', :readable, :strategy, :lsn)
                        returning id
                        """)
                .param("sourceId", sourceId)
                .param("key", key)
                .param("kind", kind)
                .param("readable", readable)
                .param("strategy", strategy)
                .param("lsn", loadedLsn, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    private void insertColumn(
            long resourceId, String name, int position, boolean key, boolean present) {
        jdbcClient
                .sql(
                        """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, is_nullable, is_key_part,
                             ordinal_position, source_comment, is_present)
                        values (:resourceId, :name, 'varchar', :nullable, :key, :position, :comment, :present)
                        """)
                .param("resourceId", resourceId)
                .param("name", name)
                .param("nullable", !key)
                .param("key", key)
                .param("position", position)
                .param("comment", name + " 설명")
                .param("present", present)
                .update();
    }

    /** CDC 소비 묶음 하나가 이 테이블을 반영한 것처럼 성공 기록을 넣는다. */
    private long insertSucceededSync(long sourceId, long resourceId) {
        long runId =
                jdbcClient
                        .sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, started_at, finished_at)
                                values (:sourceId, 'CDC', 'SUCCEEDED', now(), now())
                                returning id
                                """)
                        .param("sourceId", sourceId)
                        .query(Long.class)
                        .single();
        jdbcClient
                .sql(
                        """
                        insert into sync_run_resource
                            (sync_run_id, data_source_id, source_resource_id, mode, status, started_at, finished_at)
                        values (:runId, :sourceId, :resourceId, 'INCREMENTAL', 'SUCCEEDED', now(), now())
                        """)
                .param("runId", runId)
                .param("sourceId", sourceId)
                .param("resourceId", resourceId)
                .update();
        return runId;
    }
}
